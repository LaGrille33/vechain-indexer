package org.vechain.indexer.xapp.review

import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.IndexerService
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.exception.ResourceNotFoundException
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.rest.paginatedResponse
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.xapp.rating.RatingApiService
import org.vechain.indexer.xapp.review.repository.ReviewRepository
import org.vechain.indexer.xapp.review.repository.ReviewVoteRepository

@Profile("b3tr", "xapp-review", "xapp-review-vote")
@Service
open class ReviewApiService(
    private val reviewRepository: ReviewRepository,
    private val reviewVoteRepository: ReviewVoteRepository,
    private val mongoTemplate: MongoTemplate,
    private val domainResolver: VoteDomainResolver?,
    private val ratingApiService: RatingApiService,
    private val thorClient: ThorClient,
    @Value(
        "\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT:\${XAPP_REVIEW_MANAGER_CONTRACT:0xd369454a6fdca471831ef87f488b1f67418a336a}}"
    )
    private val reviewManagerContractAddress: String,
) : IndexerService {

    private val abiCache = ConcurrentHashMap<String, AbiElement>()

    private fun loadAbiFunction(name: String): AbiElement =
        abiCache.computeIfAbsent(name) {
            AbiLoader.loadFunctions("abis/b3tr", listOf(name)).firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Function '$name' not found in b3tr ABIs")
        }

    /**
     * Returns reviews for an app enriched with vote percentages, domain resolution, and user
     * context. Supports sorting by newest (default), most_upvotes, most_downvotes, most_reports, or
     * my_reviews (filter to viewer's own reviews).
     *
     * If [viewer] is provided, hidden reviews authored by [viewer] are included and
     * [ReviewWithVotes.myVoteType] is populated.
     */
    open fun getAppReviews(
        appId: String,
        author: String?,
        viewer: String?,
        sortBy: String?,
        pageable: Pageable,
    ): PaginatedResponse<ReviewWithVotes> {
        val isMyReviews = sortBy == "my_reviews"
        val effectiveAuthor = if (isMyReviews) viewer else author

        val slice =
            when {
                effectiveAuthor != null && viewer != null ->
                    reviewRepository.findAllByAppIdAndAuthorWithViewerHidden(
                        appId,
                        effectiveAuthor,
                        viewer,
                        pageable,
                    )
                effectiveAuthor != null ->
                    reviewRepository.findAllByAppIdAndAuthor(appId, effectiveAuthor, pageable)
                viewer != null ->
                    reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, pageable)
                else -> reviewRepository.findAllByAppId(appId, pageable)
            }

        val reviews = slice.content
        if (reviews.isEmpty()) {
            return paginatedResponse(
                org.springframework.data.domain.SliceImpl(emptyList(), pageable, false)
            )
        }

        val reviewIds = reviews.map { it.reviewId }
        val voteStatsMap = batchVoteStats(reviewIds)
        val userVotesMap = if (viewer != null) batchUserVotes(reviewIds, viewer) else emptyMap()
        val authors = reviews.map { it.author }.distinct()
        val domainsMap =
            try {
                domainResolver?.resolveDomains(authors) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

        val enriched =
            reviews.map { review ->
                val stats = voteStatsMap[review.reviewId] ?: zeroStats
                review.toWithVotes(
                    authorDomain = domainsMap[review.author],
                    stats = stats,
                    myVoteType = userVotesMap[review.reviewId] ?: 0,
                )
            }

        return paginatedResponse(
            org.springframework.data.domain.SliceImpl(enriched, pageable, slice.hasNext())
        )
    }

    /** App detail: rating summary only. */
    open fun getAppDetail(appId: String, wallet: String?): AppDetailResponse {
        val ratingSummary = ratingApiService.getAppRatingSummary(appId, wallet)
        return AppDetailResponse(
            appId = appId,
            average = ratingSummary.average,
            count = ratingSummary.count,
            userRating = ratingSummary.userRating,
            hasRated = ratingSummary.hasRated,
        )
    }

    open fun getReview(reviewId: Long): Review =
        reviewRepository.findByIdOrNull(reviewId.toString())
            ?: throw ResourceNotFoundException("Review not found for id $reviewId")

    open fun getUserReviews(author: String, pageable: Pageable): PaginatedResponse<Review> {
        val results = reviewRepository.findAllByAuthor(author, pageable)
        return paginatedResponse(results)
    }

    open fun getVoteStats(reviewId: Long): ReviewVoteStats {
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("reviewId").`is`(reviewId)),
                Aggregation.group("voteType").count().`as`("count"),
            )
        val results =
            mongoTemplate
                .aggregate(
                    aggregation,
                    IndexerNames.XAPP_REVIEW_VOTE.COLLECTION,
                    org.bson.Document::class.java,
                )
                .mappedResults

        var upvotes = 0L
        var downvotes = 0L
        var reports = 0L

        for (doc in results) {
            val voteType = (doc["_id"] as? Number)?.toInt() ?: continue
            val count = (doc["count"] as? Number)?.toLong() ?: 0L
            when (voteType) {
                1 -> upvotes = count
                2 -> downvotes = count
                3 -> reports = count
            }
        }

        val total = upvotes + downvotes + reports
        fun pct(count: Long): Double =
            if (total > 0) {
                Math.round(count * 1000.0 / total) / 10.0
            } else {
                0.0
            }

        return ReviewVoteStats(
            total = total,
            upvotes = VoteTypeStats(upvotes, pct(upvotes)),
            downvotes = VoteTypeStats(downvotes, pct(downvotes)),
            reports = VoteTypeStats(reports, pct(reports)),
        )
    }

    open fun getReviewVotes(
        reviewId: Long,
        search: String?,
        pageable: Pageable,
    ): PaginatedResponse<ReviewVoteDetail> {
        // If search looks like a domain (contains '.'), resolve to address first
        var addressSearch: String? = null
        var domainSearch: String? = null
        if (!search.isNullOrBlank()) {
            val trimmed = search.trim()
            if (trimmed.contains(".")) {
                domainSearch = trimmed
            } else {
                addressSearch = trimmed
            }
        }

        val slice =
            if (addressSearch != null) {
                reviewVoteRepository.findByReviewIdAndVoterContaining(
                    reviewId,
                    normalise(addressSearch),
                    pageable,
                )
            } else {
                reviewVoteRepository.findAllByReviewId(reviewId, pageable)
            }

        var votes = slice.content.map { it.toDetail() }

        // Batch-resolve domains for the current page
        if (votes.isNotEmpty()) {
            try {
                val addresses = votes.map { it.voter }
                val domains = domainResolver?.resolveDomains(addresses) ?: emptyMap()
                votes = votes.map { it.copy(domain = domains[it.voter]) }
            } catch (_: Exception) {
                // Domain resolution is best-effort
            }
        }

        // If searching by domain, filter results after resolution
        if (domainSearch != null) {
            val query = domainSearch.lowercase()
            votes = votes.filter { it.domain?.lowercase()?.contains(query) == true }
        }

        val result = org.springframework.data.domain.SliceImpl(votes, pageable, slice.hasNext())
        return paginatedResponse(result)
    }

    /** Batch-compute vote stats for a list of review IDs in a single aggregation. */
    private fun batchVoteStats(reviewIds: List<Long>): Map<Long, ReviewVoteStats> {
        if (reviewIds.isEmpty()) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("reviewId").`in`(reviewIds)),
                Aggregation.group("reviewId", "voteType").count().`as`("count"),
            )
        val results =
            mongoTemplate
                .aggregate(
                    aggregation,
                    IndexerNames.XAPP_REVIEW_VOTE.COLLECTION,
                    org.bson.Document::class.java,
                )
                .mappedResults

        // reviewId → (voteType → count)
        val raw = mutableMapOf<Long, MutableMap<Int, Long>>()
        for (doc in results) {
            val group = doc["_id"] as? org.bson.Document ?: continue
            val reviewId = (group["reviewId"] as? Number)?.toLong() ?: continue
            val voteType = (group["voteType"] as? Number)?.toInt() ?: continue
            val count = (doc["count"] as? Number)?.toLong() ?: 0L
            raw.getOrPut(reviewId) { mutableMapOf() }[voteType] = count
        }

        return reviewIds.associateWith { rid ->
            val m = raw[rid] ?: emptyMap()
            val up = m[1] ?: 0L
            val down = m[2] ?: 0L
            val rep = m[3] ?: 0L
            val total = up + down + rep
            fun pct(c: Long) = if (total > 0) Math.round(c * 1000.0 / total) / 10.0 else 0.0
            ReviewVoteStats(
                total = total,
                upvotes = VoteTypeStats(up, pct(up)),
                downvotes = VoteTypeStats(down, pct(down)),
                reports = VoteTypeStats(rep, pct(rep)),
            )
        }
    }

    /** Batch-load a user's vote type for a list of review IDs. */
    private fun batchUserVotes(reviewIds: List<Long>, voter: String): Map<Long, Int> {
        if (reviewIds.isEmpty()) return emptyMap()
        val ids = reviewIds.map { ReviewVoteDoc.documentId(it, normalise(voter)) }
        return reviewVoteRepository.findAllById(ids).associate { it.reviewId to it.voteType }
    }

    private val zeroStats =
        ReviewVoteStats(
            total = 0,
            upvotes = VoteTypeStats(0, 0.0),
            downvotes = VoteTypeStats(0, 0.0),
            reports = VoteTypeStats(0, 0.0),
        )

    private fun Review.toWithVotes(
        authorDomain: String?,
        stats: ReviewVoteStats,
        myVoteType: Int,
    ): ReviewWithVotes =
        ReviewWithVotes(
            reviewId = reviewId,
            appId = appId,
            author = author,
            authorDomain = authorDomain,
            title = title,
            content = content,
            blockTimestamp = blockTimestamp,
            isHidden = isHidden,
            upvotes = stats.upvotes,
            downvotes = stats.downvotes,
            reports = stats.reports,
            myVoteType = myVoteType,
        )

    private fun ReviewVoteDoc.toDetail(): ReviewVoteDetail =
        ReviewVoteDetail(
            voter = voter,
            domain = null,
            voteType = voteType,
            voteTypeName =
                when (voteType) {
                    1 -> "UPVOTE"
                    2 -> "DOWNVOTE"
                    3 -> "REPORT"
                    else -> "UNKNOWN"
                },
            timestamp = timestamp,
        )

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}
