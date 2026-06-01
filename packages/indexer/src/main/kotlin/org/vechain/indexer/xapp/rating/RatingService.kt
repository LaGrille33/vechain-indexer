package org.vechain.indexer.xapp.rating

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
import org.vechain.indexer.xapp.rating.repository.AppRatingRepository

@Profile("b3tr", "xapp-rating")
@Service
open class RatingService(
    private val repository: AppRatingRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
) {

    open suspend fun processEvents(
        events: List<IndexedEvent>
    ): Pair<List<AppRating>, List<AppRating>> {
        if (events.isEmpty()) return emptyList<AppRating>() to emptyList()

        // RatingSubmitted and RatingUpdated are both upserts keyed by (appId, rater); preload the
        // documents they touch so the accumulator can resolve the current version in one round
        // trip.
        val idsToPreload =
            events
                .filter { it.eventType == "RatingSubmitted" || it.eventType == "RatingUpdated" }
                .mapNotNull { recordId(it) }
                .toSet()

        val preloaded =
            if (idsToPreload.isNotEmpty()) {
                repository.findAllById(idsToPreload).associateBy { it.getDocumentId() }
            } else {
                emptyMap()
            }

        val accumulator =
            VersionedDocumentAccumulator<AppRating>(
                findById = { id -> preloaded[id] ?: repository.findByIdOrNull(id) }
            )

        for (event in events) {
            if (event.eventType != "RatingSubmitted" && event.eventType != "RatingUpdated") continue
            accumulator.startBlock()

            val values = event.params.getReturnValues()
            val appId = values["appId"]?.toString()?.let { normalise(it) } ?: continue
            val rater = values["rater"]?.toString()?.let { normalise(it) } ?: continue
            val rating = (values["rating"] as? Number)?.toInt() ?: continue

            val recordId = AppRating.documentId(appId, rater)
            val (existing, nextVersion) = accumulator.resolve(recordId)

            val updated =
                AppRating(
                    version = nextVersion,
                    blockId = event.blockId,
                    blockNumber = event.blockNumber,
                    blockTimestamp = event.blockTimestamp,
                    appId = appId,
                    rater = rater,
                    rating = rating,
                )
            accumulator.put(recordId, existing, updated)
        }

        return accumulator.results()
    }

    @Transactional(rollbackFor = [Exception::class])
    open fun save(updated: List<AppRating>, existing: List<AppRating>) {
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
        val appId = values["appId"]?.toString()?.let { normalise(it) } ?: return null
        val rater = values["rater"]?.toString()?.let { normalise(it) } ?: return null
        return AppRating.documentId(appId, rater)
    }
}
