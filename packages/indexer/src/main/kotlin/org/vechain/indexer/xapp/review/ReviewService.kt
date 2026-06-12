package org.vechain.indexer.xapp.review

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.vechain.indexer.VersionedDocumentAccumulator
import org.vechain.indexer.config.InlineVersioningProperties
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.event.model.generic.IndexedEvent
import org.vechain.indexer.event.utils.FunctionReturnDecoder
import org.vechain.indexer.saveVersionedDocuments
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.BlockRevision
import org.vechain.indexer.transaction.TransactionUtils.isSuccessWithData
import org.vechain.indexer.utils.ContractUtils
import org.vechain.indexer.xapp.review.repository.ReviewRepository

@Profile("b3tr", "xapp-review")
@Service
open class ReviewService(
    private val repository: ReviewRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
    private val thorClient: ThorClient,
    @Value("\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT}")
    private val reviewManagerContractAddress: String,
) {
    private var runtimeState: ReviewRuntimeState? = null
    private val abiCache: ConcurrentHashMap<String, AbiElement> = ConcurrentHashMap()

    data class ReviewRuntimeState(val latestKnownReviewId: Long = 0)

    open fun findByReviewId(reviewId: Long): Review? =
        repository.findByIdOrNull(reviewId.toString())

    open suspend fun processEvents(events: List<IndexedEvent>): Pair<List<Review>, List<Review>> {
        if (events.isEmpty()) return emptyList<Review>() to emptyList()

        val idsToPreload =
            events
                .filter {
                    it.eventType in
                        setOf("ReviewUpdated", "ReviewVoted", "ReviewHidden", "ReviewUnhidden")
                }
                .mapNotNull { getReviewId(it) }
                .map { it.toString() }
                .toSet()

        val preloaded =
            if (idsToPreload.isNotEmpty()) {
                repository.findAllById(idsToPreload).associateBy { it.getDocumentId() }
            } else {
                emptyMap()
            }

        val accumulator =
            VersionedDocumentAccumulator<Review>(
                findById = { id -> preloaded[id] ?: repository.findByIdOrNull(id) }
            )

        for (event in events) {
            accumulator.startBlock()

            when (event.eventType) {
                "ReviewCreated" -> {
                    val reviewId = getReviewId(event) ?: continue
                    val appId =
                        event.params.getReturnValues()["appId"]?.toString()?.let { normalise(it) }
                            ?: continue
                    val author =
                        event.params.getReturnValues()["reviewer"]?.toString()?.let {
                            normalise(it)
                        } ?: continue

                    val recordId = reviewId.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    if (existing != null) continue

                    val review =
                        Review(
                            version = nextVersion,
                            blockId = event.blockId,
                            blockNumber = event.blockNumber,
                            blockTimestamp = event.blockTimestamp,
                            reviewId = reviewId,
                            appId = appId,
                            author = author,
                            title = "",
                            content = "",
                        )
                    accumulator.put(recordId, null, review)
                }

                "ReviewUpdated" -> {
                    val reviewId = getReviewId(event) ?: continue

                    val recordId = reviewId.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue

                    // Title/content are refreshed from the contract in enrichWithContractData; here
                    // we only bump the version and block metadata so the update is persisted.
                    accumulator.put(
                        recordId,
                        current,
                        current.copy(
                            version = nextVersion,
                            blockId = event.blockId,
                            blockNumber = event.blockNumber,
                            blockTimestamp = event.blockTimestamp,
                        ),
                    )
                }

                "ReviewVoted" -> {
                    // Vote counts are overwritten by enrichWithContractData (which reads
                    // authoritative values from the contract), so we only bump version/block
                    // metadata here to trigger the enrichment pass.
                    val reviewId = getReviewId(event) ?: continue

                    val recordId = reviewId.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue

                    accumulator.put(
                        recordId,
                        current,
                        current.copy(
                            version = nextVersion,
                            blockId = event.blockId,
                            blockNumber = event.blockNumber,
                            blockTimestamp = event.blockTimestamp,
                        ),
                    )
                }

                "ReviewHidden" -> {
                    val reviewId = getReviewId(event) ?: continue
                    val recordId = reviewId.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue
                    accumulator.put(
                        recordId,
                        current,
                        current.copy(version = nextVersion, isHidden = true),
                    )
                }

                "ReviewUnhidden" -> {
                    val reviewId = getReviewId(event) ?: continue
                    val recordId = reviewId.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue
                    accumulator.put(
                        recordId,
                        current,
                        current.copy(version = nextVersion, isHidden = false),
                    )
                }
            }
        }

        val (updated, existing) = accumulator.results()
        val enriched = if (updated.isNotEmpty()) enrichWithContractData(updated) else updated
        return enriched to existing
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    private suspend fun enrichWithContractData(reviews: List<Review>): List<Review> {
        val abi = loadAbiFunction("getReview")
        val revision = BlockRevision.Number(reviews.last().blockNumber)
        val clauses =
            reviews.map { review ->
                ContractUtils.createClause(
                    reviewManagerContractAddress,
                    abi,
                    BigInteger.valueOf(review.reviewId),
                )
            }

        val responses = thorClient.inspectClauses(clauses, revision)

        return reviews.mapIndexed { index, review ->
            val response =
                responses.getOrNull(index)
                    ?: run {
                        logger.warn("No response for reviewId={}", review.reviewId)
                        return@mapIndexed review
                    }
            if (!isSuccessWithData(response)) {
                logger.warn(
                    "getReview reverted for reviewId={} vmError={}",
                    review.reviewId,
                    response.vmError,
                )
                return@mapIndexed review
            }
            try {
                val decoded = FunctionReturnDecoder.decode(response.data, abi.outputs)
                val inner = (decoded[""] as? Map<*, *>) ?: decoded
                val title = (inner["title"] as? String) ?: review.title
                val content = (inner["content"] as? String) ?: review.content
                val upvotes = (inner["upvotes"] as? Number)?.toLong() ?: review.upvotes
                val downvotes = (inner["downvotes"] as? Number)?.toLong() ?: review.downvotes
                val reports = (inner["reports"] as? Number)?.toLong() ?: review.reports
                val hidden = (inner["hidden"] as? Boolean) ?: review.isHidden
                review.copy(
                    title = title,
                    content = content,
                    upvotes = upvotes,
                    downvotes = downvotes,
                    reports = reports,
                    isHidden = hidden,
                )
            } catch (e: Exception) {
                logger.error("Failed to decode getReview for reviewId={}", review.reviewId, e)
                review
            }
        }
    }

    open fun invalidateRuntimeState() {
        runtimeState = null
    }

    @Transactional(rollbackFor = [Exception::class])
    open fun save(updated: List<Review>, existing: List<Review>) {
        saveVersionedDocuments(
            updated = updated,
            existing = existing,
            mongoTemplate = mongoTemplate,
            blockWindow = inlineVersioningProperties.blockWindow,
            maxVersions = inlineVersioningProperties.maxVersions,
            minVersions = inlineVersioningProperties.minVersions,
        )
    }

    private fun getReviewId(event: IndexedEvent): Long? {
        val value = event.params.getReturnValues()["reviewId"]
        return when (value) {
            is Number -> value.toLong()
            else -> value?.toString()?.toLongOrNull()
        }
    }

    private fun loadAbiFunction(name: String): AbiElement =
        abiCache.computeIfAbsent(name) {
            AbiLoader.loadFunctions("abis/b3tr", listOf(name)).firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Function '$name' not found in b3tr ABIs")
        }
}
