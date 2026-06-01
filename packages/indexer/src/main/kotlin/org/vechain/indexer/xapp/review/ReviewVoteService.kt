package org.vechain.indexer.xapp.review

import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.vechain.indexer.VersionedDocumentAccumulator
import org.vechain.indexer.config.InlineVersioningProperties
import org.vechain.indexer.event.model.generic.IndexedEvent
import org.vechain.indexer.saveVersionedDocuments
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.xapp.review.repository.ReviewVoteRepository

@Profile("b3tr", "xapp-review-vote")
@Service
open class ReviewVoteService(
    private val repository: ReviewVoteRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
) {

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

            val docId = ReviewVoteDoc.documentId(reviewId, voter)
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

        return accumulator.results()
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
        return ReviewVoteDoc.documentId(reviewId, voter)
    }
}
