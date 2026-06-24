package org.vechain.indexer.xapp.review

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
import org.vechain.indexer.thor.AddressUtils
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.BlockRevision
import org.vechain.indexer.transaction.TransactionUtils.isSuccessWithData
import org.vechain.indexer.utils.ContractUtils
import org.vechain.indexer.xapp.review.repository.ReviewVoteRepository

@Profile("b3tr", "xapp-review-vote")
@Service
open class ReviewVoteService(
    private val repository: ReviewVoteRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
    private val thorClient: ThorClient,
    @Value(
        "\${business-event.substitutions.VET_DOMAINS_RESOLVE_UTILS_CONTRACT:\${VET_DOMAINS_RESOLVE_UTILS_CONTRACT:}}"
    )
    private val resolveUtilsContract: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val abiCache = ConcurrentHashMap<String, AbiElement>()

    open suspend fun processEvents(
        events: List<IndexedEvent>
    ): Pair<List<ReviewVoteDoc>, List<ReviewVoteDoc>> {
        if (events.isEmpty()) return emptyList<ReviewVoteDoc>() to emptyList()

        val idsToPreload =
            events.filter { it.eventType == "ReviewVoted" }.mapNotNull { recordId(it) }.toSet()

        val preloaded =
            if (idsToPreload.isNotEmpty()) {
                repository.findAllById(idsToPreload).associateBy { it.getDocumentId() }
            } else {
                emptyMap()
            }

        val accumulator =
            VersionedDocumentAccumulator<ReviewVoteDoc>(
                findById = { id -> preloaded[id] ?: repository.findByIdOrNull(id) }
            )

        for (event in events) {
            if (event.eventType != "ReviewVoted") continue
            accumulator.startBlock()

            val values = event.params.getReturnValues()
            val reviewId =
                (values["reviewId"] as? Number)?.toLong()
                    ?: values["reviewId"]?.toString()?.toLongOrNull()
                    ?: continue
            val voter = values["voter"]?.toString()?.let { normalise(it) } ?: continue
            val voteType = (values["voteType"] as? Number)?.toInt() ?: continue

            val docId = ReviewVoteDoc.documentId(reviewId, voter, voteType)
            val (existing, nextVersion) = accumulator.resolve(docId)

            val updated =
                ReviewVoteDoc(
                    version = nextVersion,
                    blockId = event.blockId,
                    blockNumber = event.blockNumber,
                    blockTimestamp = event.blockTimestamp,
                    reviewId = reviewId,
                    voter = voter,
                    voteType = voteType,
                    timestamp = event.blockTimestamp,
                )
            accumulator.put(docId, existing, updated)
        }

        val (updated, existing) = accumulator.results()
        val enriched = if (updated.isNotEmpty()) enrichWithDomainData(updated) else updated
        return enriched to existing
    }

    /**
     * Batch-resolves voter domains via the vet-domains getNames contract call and attaches them to
     * the vote documents. Follows the same enrichment pattern as
     * [ReviewService.enrichWithContractData].
     */
    private suspend fun enrichWithDomainData(votes: List<ReviewVoteDoc>): List<ReviewVoteDoc> {
        if (resolveUtilsContract.isBlank()) return votes
        val domainsMap = batchResolveDomains(votes.map { it.voter }.distinct())
        if (domainsMap.isEmpty()) return votes
        return votes.map { vote -> vote.copy(domain = domainsMap[vote.voter]) }
    }

    private suspend fun batchResolveDomains(addresses: List<String>): Map<String, String?> {
        if (addresses.isEmpty()) return emptyMap()
        return try {
            callGetNames(addresses)
        } catch (e: Exception) {
            logger.warn("Failed to batch-resolve domains: {}", e.message, e)
            emptyMap()
        }
    }

    private suspend fun callGetNames(addresses: List<String>): Map<String, String?> {
        val abi = loadAbiFunction("getNames")
        val bigints = addresses.map { AddressUtils.toBigInt(it) }
        @Suppress("UNCHECKED_CAST")
        val addressArray =
            java.lang.reflect.Array.newInstance(java.math.BigInteger::class.java, bigints.size)
                as Array<java.math.BigInteger>
        bigints.forEachIndexed { i, v -> addressArray[i] = v }
        val clause = ContractUtils.createClause(resolveUtilsContract, abi, addressArray)

        val response =
            thorClient.inspectClauses(listOf(clause), BlockRevision.Keyword.BEST).firstOrNull()
                ?: throw IllegalStateException("No response from resolveUtils contract")

        if (!isSuccessWithData(response)) {
            throw IllegalStateException("getNames call failed: ${response.vmError}")
        }

        val decoded = FunctionReturnDecoder.decode(response.data, abi.outputs)
        val names = (decoded["names"] as? List<*>) ?: (decoded[""] as? List<*>) ?: emptyList<Any>()

        return addresses
            .zip(names) { addr, name ->
                val domain = name?.toString()?.takeIf { it.isNotEmpty() }
                normalise(addr) to domain
            }
            .toMap()
    }

    private fun loadAbiFunction(name: String): AbiElement =
        abiCache.computeIfAbsent(name) {
            AbiLoader.loadFunctions("abis/vet-domains", listOf(name)).firstOrNull {
                it.name == name
            } ?: throw IllegalArgumentException("Function '$name' not found in vet-domains ABIs")
        }

    @Transactional(rollbackFor = [Exception::class])
    open fun save(updated: List<ReviewVoteDoc>, existing: List<ReviewVoteDoc>) {
        saveVersionedDocuments(
            updated = updated,
            existing = existing,
            mongoTemplate = mongoTemplate,
            blockWindow = inlineVersioningProperties.blockWindow,
            maxVersions = inlineVersioningProperties.maxVersions,
            minVersions = inlineVersioningProperties.minVersions,
        )
    }

    private fun recordId(event: IndexedEvent): String? {
        val values = event.params.getReturnValues()
        val reviewId =
            (values["reviewId"] as? Number)?.toLong()
                ?: values["reviewId"]?.toString()?.toLongOrNull()
                ?: return null
        val voter = values["voter"]?.toString()?.let { normalise(it) } ?: return null
        val voteType = (values["voteType"] as? Number)?.toInt() ?: return null
        return ReviewVoteDoc.documentId(reviewId, voter, voteType)
    }
}
