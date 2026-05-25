package org.vechain.indexer.xapp.scoring

import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.vechain.indexer.VersionedDocumentAccumulator
import org.vechain.indexer.config.InlineVersioningProperties
import org.vechain.indexer.event.model.generic.IndexedEvent
import org.vechain.indexer.saveVersionedDocuments
import org.vechain.indexer.xapp.scoring.repository.ScoringRoundRepository

@Profile("b3tr", "xapp-scoring")
@Service
open class ScoringService(
    private val repository: ScoringRoundRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
) {
    open suspend fun processEvents(
        events: List<IndexedEvent>
    ): Pair<List<ScoringRound>, List<ScoringRound>> {
        if (events.isEmpty()) return emptyList<ScoringRound>() to emptyList()

        val idsToPreload =
            events
                .filter { it.eventType in setOf("RoundFinalized", "RoundRankingsUpdated") }
                .mapNotNull { getRound(it)?.toString() }
                .toSet()

        val preloaded =
            if (idsToPreload.isNotEmpty()) {
                repository.findAllById(idsToPreload).associateBy { it.getDocumentId() }
            } else {
                emptyMap()
            }

        val accumulator =
            VersionedDocumentAccumulator<ScoringRound>(
                findById = { id -> preloaded[id] ?: repository.findByIdOrNull(id) }
            )

        for (event in events) {
            accumulator.startBlock()

            when (event.eventType) {
                "ScoresRecorded" -> {
                    val round = getRound(event) ?: continue
                    val appCount =
                        (event.params.getReturnValues()["appCount"] as? Number)?.toLong()
                            ?: continue

                    val recordId = round.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)

                    if (existing != null && existing.appCount >= appCount) continue

                    val scoringRound =
                        if (existing != null) {
                            existing.copy(
                                version = nextVersion,
                                appCount = appCount,
                                blockId = event.blockId,
                                blockNumber = event.blockNumber,
                                blockTimestamp = event.blockTimestamp,
                            )
                        } else {
                            ScoringRound(
                                version = nextVersion,
                                blockId = event.blockId,
                                blockNumber = event.blockNumber,
                                blockTimestamp = event.blockTimestamp,
                                round = round,
                                appCount = appCount,
                            )
                        }
                    accumulator.put(recordId, existing, scoringRound)
                }

                "RoundFinalized" -> {
                    val round = getRound(event) ?: continue
                    val rankedAppCount =
                        (event.params.getReturnValues()["rankedAppCount"] as? Number)?.toLong()
                            ?: continue

                    val recordId = round.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue

                    accumulator.put(
                        recordId,
                        current,
                        current.copy(
                            version = nextVersion,
                            rankedAppCount = rankedAppCount,
                            isFinalized = true,
                            blockId = event.blockId,
                            blockNumber = event.blockNumber,
                            blockTimestamp = event.blockTimestamp,
                        ),
                    )
                }

                "RoundRankingsUpdated" -> {
                    val round = getRound(event) ?: continue

                    val recordId = round.toString()
                    val (existing, nextVersion) = accumulator.resolve(recordId)
                    val current = existing ?: continue

                    accumulator.put(
                        recordId,
                        current,
                        current.copy(
                            version = nextVersion,
                            hasRankingsUpdated = true,
                            blockId = event.blockId,
                            blockNumber = event.blockNumber,
                            blockTimestamp = event.blockTimestamp,
                        ),
                    )
                }
            }
        }

        return accumulator.results()
    }

    @Transactional(rollbackFor = [Exception::class])
    open fun save(updated: List<ScoringRound>, existing: List<ScoringRound>) {
        saveVersionedDocuments(
            updated = updated,
            existing = existing,
            mongoTemplate = mongoTemplate,
            blockWindow = inlineVersioningProperties.blockWindow,
            maxVersions = inlineVersioningProperties.maxVersions,
            minVersions = inlineVersioningProperties.minVersions,
        )
    }

    private fun getRound(event: IndexedEvent): Long? {
        val value = event.params.getReturnValues()["round"]
        return when (value) {
            is Number -> value.toLong()
            else -> value?.toString()?.toLongOrNull()
        }
    }
}
