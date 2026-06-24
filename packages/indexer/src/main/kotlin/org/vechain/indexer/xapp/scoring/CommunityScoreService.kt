package org.vechain.indexer.xapp.scoring

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.round.AppRoundScoreService
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.event.utils.FunctionReturnDecoder
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.utils.ContractUtils

/** Result of the Community Score computation for a single app. */
data class CommunityScoreResult(
    val appId: String,
    val avgRating: BigDecimal,
    val numRatings: Long,
    val helpfulVotes: Long,
    val unhelpfulVotes: Long,
    val confidenceFactor: BigDecimal,
    val helpfulRatio: BigDecimal,
    val engagementFactor: BigDecimal,
    val adjustedRating: BigDecimal,
    val communityScore: BigDecimal,
)

/**
 * Computes the Community Score for apps based on star ratings and review votes, as defined in the
 * VBD Reputation Layer Proposal (Layer 4, 30% weight). This is a pure calculation over the current
 * rating/review state — no dependency on cycle scores or rounds.
 *
 * NOTE: the production scoring pipeline inlines this logic in [AppRoundScoreService]; this service
 * is kept in sync (and exercised by its unit test) as the canonical, documented implementation.
 *
 * Other services call [computeCommunityScore] to obtain the score for a single app or
 * [computeCommunityScores] for all apps.
 */
@Profile("b3tr")
@Service
open class CommunityScoreService(
    private val mongoTemplate: MongoTemplate,
    private val thorClient: ThorClient,
    @Value("\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT}")
    private val reviewManagerContractAddress: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val R_MIN = 20L // Minimum ratings to be eligible (fallback default)
        private val K = BigDecimal("100") // Smoothing constant for ConfidenceFactor
        private val MC = MathContext(20, RoundingMode.HALF_UP)
    }

    /** ABI for `XAppReviewManager.communityScoreMinRatings() -> uint256`. */
    private val communityScoreMinRatingsAbi: AbiElement by lazy {
        AbiLoader.loadFunctions("abis/b3tr", listOf("communityScoreMinRatings")).firstOrNull {
            it.name == "communityScoreMinRatings"
        } ?: throw IllegalArgumentException("Function 'communityScoreMinRatings' not found in ABI")
    }

    /** Compute the Community Score for a specific app, or null if it has no rating data. */
    open fun computeCommunityScore(appId: String): CommunityScoreResult? =
        computeCommunityScores().find { it.appId == appId }

    /**
     * Compute Community Scores for all apps that have rating data. Returns results sorted by
     * communityScore descending.
     */
    open fun computeCommunityScores(): List<CommunityScoreResult> {
        val ratingStats = aggregateRatingStats()
        if (ratingStats.isEmpty()) return emptyList()

        // R_min is governance-adjustable on-chain (XAppReviewManager.communityScoreMinRatings).
        val rMin = readCommunityScoreMinRatings()
        val voteStats = aggregateVoteStats()

        // Compute adjusted rating for each app with rating data
        val appDataList = mutableListOf<AppData>()
        for (rs in ratingStats.values) {
            val vs = voteStats[rs.appId]
            val data =
                AppData(
                    appId = rs.appId,
                    avgRating = rs.avgRating,
                    numRatings = rs.numRatings,
                    helpfulVotes = vs?.helpfulVotes ?: 0L,
                    unhelpfulVotes = vs?.unhelpfulVotes ?: 0L,
                )
            data.adjustedRating = computeAdjustedRating(data, rMin)
            appDataList.add(data)
        }

        // Percentile rank across eligible apps only (NumRatings >= rMin), per the Community Score
        // spec: "Percentile Rank of AdjustedRating among all ELIGIBLE dApps". Ineligible apps get a
        // score of 0 and must NOT dilute the ranking pool — otherwise every ineligible app added to
        // the system inflates all eligible apps' scores. (reuses the shared mid-rank
        // implementation)
        val allAdjustedRatings =
            appDataList.filter { it.numRatings >= rMin }.map { it.adjustedRating }
        for (data in appDataList) {
            data.communityScore =
                if (data.numRatings < rMin) BigDecimal.ZERO
                else AppRoundScoreService.percentileRank(data.adjustedRating, allAdjustedRatings)
        }

        return appDataList
            .sortedByDescending { it.communityScore }
            .map { data ->
                val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
                CommunityScoreResult(
                    appId = data.appId,
                    avgRating = data.avgRating,
                    numRatings = data.numRatings,
                    helpfulVotes = data.helpfulVotes,
                    unhelpfulVotes = data.unhelpfulVotes,
                    confidenceFactor = computeConfidenceFactor(data.numRatings),
                    helpfulRatio = helpfulRatio,
                    engagementFactor = computeEngagementFactor(helpfulRatio),
                    adjustedRating = data.adjustedRating,
                    communityScore = data.communityScore,
                )
            }
    }

    // ── Data aggregation ──────────────────────────────────────────────────

    private data class AppRatingStats(
        val appId: String,
        val avgRating: BigDecimal,
        val numRatings: Long,
    )

    private data class AppVoteStats(
        val appId: String,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
    )

    private class AppData(
        val appId: String,
        val avgRating: BigDecimal,
        val numRatings: Long,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
        var adjustedRating: BigDecimal = BigDecimal.ZERO,
        var communityScore: BigDecimal = BigDecimal.ZERO,
    )

    private fun aggregateRatingStats(): Map<String, AppRatingStats> {
        val collection = IndexerNames.XAPP_RATING.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("_id").ne(null)),
                Aggregation.group("appId").count().`as`("count").avg("rating").`as`("average"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppRatingStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String) ?: return@forEach
            val count = toLong(doc.get("count"))
            val average = BigDecimal.valueOf((doc.get("average") as? Number)?.toDouble() ?: 0.0)
            map[appId] = AppRatingStats(appId = appId, avgRating = average, numRatings = count)
        }
        return map
    }

    private fun aggregateVoteStats(): Map<String, AppVoteStats> {
        val collection = IndexerNames.XAPP_REVIEW_VOTE.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                // Join votes with reviews to get appId and check isHidden
                Aggregation.lookup(
                    IndexerNames.XAPP_REVIEW.COLLECTION,
                    "reviewId",
                    "reviewId",
                    "review",
                ),
                Aggregation.unwind("review"),
                Aggregation.match(Criteria.where("review.isHidden").`is`(false)),
                // voteType 1 = upvote (helpful), 2 = downvote (unhelpful)
                Aggregation.group("review.appId")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(1))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("helpfulVotes")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(2))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("unhelpfulVotes"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppVoteStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String) ?: return@forEach
            val helpful = toLong(doc.get("helpfulVotes"))
            val unhelpful = toLong(doc.get("unhelpfulVotes"))
            map[appId] =
                AppVoteStats(appId = appId, helpfulVotes = helpful, unhelpfulVotes = unhelpful)
        }
        return map
    }

    // ── On-chain R_min ─────────────────────────────────────────────────────

    /**
     * Reads the governance-adjustable Community Score minimum-ratings threshold
     * (`XAppReviewManager.communityScoreMinRatings()`) at the best block. Falls back to [R_MIN]
     * (20) when the call reverts/fails or returns 0 (e.g. right after an upgrade, before
     * `setCommunityScoreMinRatings` is called), so scoring never treats every app as eligible.
     */
    private fun readCommunityScoreMinRatings(): Long =
        try {
            runBlocking {
                val clause =
                    ContractUtils.createClause(
                        reviewManagerContractAddress,
                        communityScoreMinRatingsAbi,
                    )
                val response = thorClient.inspectClauses(listOf(clause)).first()
                if (response.reverted) {
                    logger.warn("communityScoreMinRatings() reverted: {}", response.vmError)
                    return@runBlocking R_MIN
                }
                val data = response.data
                if (data.isBlank() || data == "0x") return@runBlocking R_MIN
                val decoded =
                    FunctionReturnDecoder.decode(data, communityScoreMinRatingsAbi.outputs)
                val value = (decoded[""] as? Number)?.toLong() ?: return@runBlocking R_MIN
                if (value <= 0L) R_MIN else value
            }
        } catch (e: Exception) {
            logger.warn("Failed to read communityScoreMinRatings; falling back to {}", R_MIN, e)
            R_MIN
        }

    // ── Formula components ────────────────────────────────────────────────

    /**
     * ConfidenceFactor = 1 - e^(-NumRatings / K). The exponential is transcendental, so it is
     * evaluated via Double then wrapped in BigDecimal to preserve all remaining arithmetic.
     */
    private fun computeConfidenceFactor(numRatings: Long): BigDecimal {
        val exponent = -numRatings.toDouble() / K.toDouble()
        return BigDecimal.ONE.subtract(exp(exponent).toBigDecimal(), MC)
    }

    /** HelpfulRatio = helpfulVotes / (helpfulVotes + unhelpfulVotes), default 0.5 if no votes. */
    private fun computeHelpfulRatio(helpfulVotes: Long, unhelpfulVotes: Long): BigDecimal {
        val total = helpfulVotes + unhelpfulVotes
        return if (total == 0L) {
            BigDecimal("0.5")
        } else {
            BigDecimal(helpfulVotes).divide(BigDecimal(total), MC)
        }
    }

    /** EngagementFactor = 0.5 + 0.5 * HelpfulRatio, range [0.5, 1.0] */
    private fun computeEngagementFactor(helpfulRatio: BigDecimal): BigDecimal =
        BigDecimal("0.5").add(BigDecimal("0.5").multiply(helpfulRatio, MC), MC)

    /**
     * AdjustedRating = AvgRating * ConfidenceFactor * EngagementFactor (0 when NumRatings < rMin).
     */
    private fun computeAdjustedRating(data: AppData, rMin: Long): BigDecimal {
        if (data.numRatings < rMin) return BigDecimal.ZERO
        val confidenceFactor = computeConfidenceFactor(data.numRatings)
        val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
        val engagementFactor = computeEngagementFactor(helpfulRatio)
        return data.avgRating.multiply(confidenceFactor, MC).multiply(engagementFactor, MC)
    }
}

private fun toLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }
