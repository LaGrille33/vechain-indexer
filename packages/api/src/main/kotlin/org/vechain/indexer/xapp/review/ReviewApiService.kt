package org.vechain.indexer.xapp.review

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.event.utils.FunctionReturnDecoder
import org.vechain.indexer.exception.ResourceNotFoundException
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.rest.paginatedResponse
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.BlockRevision
import org.vechain.indexer.transaction.TransactionUtils.isSuccessWithData
import org.vechain.indexer.utils.ContractUtils
import org.vechain.indexer.xapp.review.repository.ReviewRepository

@Profile("b3tr", "xapp-review")
@Service
open class ReviewApiService(
    private val reviewRepository: ReviewRepository,
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

    open fun getAppReviews(
        appId: String,
        author: String?,
        pageable: Pageable,
    ): PaginatedResponse<Review> {
        val results =
            if (author != null) {
                reviewRepository.findAllByAppIdAndAuthor(appId, author, pageable)
            } else {
                reviewRepository.findAllByAppId(appId, pageable)
            }
        return paginatedResponse(results)
    }

    open fun getReview(reviewId: Long): Review =
        reviewRepository.findByIdOrNull(reviewId.toString())
            ?: throw ResourceNotFoundException("Review not found for id $reviewId")

    open fun getUserReviews(author: String, pageable: Pageable): PaginatedResponse<Review> {
        val results = reviewRepository.findAllByAuthor(author, pageable)
        return paginatedResponse(results)
    }

    open fun getReviewVotes(
        reviewId: Long,
        search: String?,
        pageable: Pageable,
    ): PaginatedResponse<ReviewVote> {
        val abi = loadAbiFunction("getVoteHistory")
        val clause =
            ContractUtils.createClause(
                reviewManagerContractAddress,
                abi,
                BigInteger.valueOf(reviewId),
            )
        val response =
            kotlinx.coroutines
                .runBlocking {
                    thorClient.inspectClauses(listOf(clause), BlockRevision.Keyword.BEST)
                }
                .firstOrNull()
                ?: throw ResourceNotFoundException("No response from contract for review $reviewId")

        if (!isSuccessWithData(response)) {
            throw ResourceNotFoundException(
                "Failed to fetch vote history or review not found for id $reviewId"
            )
        }

        val decoded = FunctionReturnDecoder.decode(response.data, abi.outputs)
        val list = (decoded[""] as? List<*>) ?: emptyList<Any>()

        val allVotes =
            list
                .mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val voter =
                        map["voter"]?.toString()?.let { normalise(it) } ?: return@mapNotNull null
                    val voteType = (map["voteType"] as? Number)?.toInt() ?: return@mapNotNull null
                    val timestamp =
                        (map["timestamp"] as? Number)?.toLong() ?: return@mapNotNull null
                    ReviewVote(voter = voter, voteType = voteType, timestamp = timestamp)
                }
                .sortedByDescending { it.timestamp }

        val filteredVotes =
            if (!search.isNullOrBlank()) {
                val query = search.trim().lowercase()
                allVotes.filter { it.voter.lowercase().contains(query) }
            } else {
                allVotes
            }

        // Paginate in-memory
        val pageSize = pageable.pageSize
        val pageNumber = pageable.pageNumber
        val start = pageNumber * pageSize
        val end = minOf(start + pageSize, filteredVotes.size)

        val pagedList =
            if (start < filteredVotes.size) {
                filteredVotes.subList(start, end)
            } else {
                emptyList()
            }

        val hasNext = end < filteredVotes.size
        val slice = org.springframework.data.domain.SliceImpl(pagedList, pageable, hasNext)
        return paginatedResponse(slice)
    }

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}
