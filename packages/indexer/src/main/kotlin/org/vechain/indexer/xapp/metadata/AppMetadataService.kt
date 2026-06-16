package org.vechain.indexer.xapp.metadata

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
import org.vechain.indexer.xapp.metadata.repository.AppMetadataRepository

/**
 * Turns `AppMetadataURIUpdated` events from the X2EarnApps contract into [AppMetadata] documents.
 *
 * For each event it fetches the new IPFS metadata JSON via [IpfsMetadataClient] and keeps only the
 * `version_history` and `tweets` portions. Events whose metadata cannot be fetched are skipped (not
 * written) so a transient IPFS outage never overwrites an app's previously-good snapshot.
 */
@Profile("b3tr", "xapp-metadata")
@Service
open class AppMetadataService(
    private val repository: AppMetadataRepository,
    private val mongoTemplate: MongoTemplate,
    private val inlineVersioningProperties: InlineVersioningProperties,
    private val ipfsMetadataClient: IpfsMetadataClient,
) {

    open suspend fun processEvents(
        events: List<IndexedEvent>
    ): Pair<List<AppMetadata>, List<AppMetadata>> {
        if (events.isEmpty()) return emptyList<AppMetadata>() to emptyList()

        val idsToPreload =
            events
                .filter { it.eventType == "AppMetadataURIUpdated" }
                .mapNotNull { appIdOf(it) }
                .toSet()

        val preloaded =
            if (idsToPreload.isNotEmpty()) {
                repository.findAllById(idsToPreload).associateBy { it.getDocumentId() }
            } else {
                emptyMap()
            }

        val accumulator =
            VersionedDocumentAccumulator<AppMetadata>(
                findById = { id -> preloaded[id] ?: repository.findByIdOrNull(id) }
            )

        for (event in events) {
            if (event.eventType != "AppMetadataURIUpdated") continue

            val values = event.params.getReturnValues()
            val appId = values["appId"]?.toString()?.let { normalise(it) } ?: continue
            val newMetadataURI = values["newMetadataURI"]?.toString() ?: continue

            val resolved = ipfsMetadataClient.resolve(newMetadataURI) ?: continue

            accumulator.startBlock()
            val recordId = AppMetadata.documentId(appId)
            val (existing, nextVersion) = accumulator.resolve(recordId)

            // An update is signalled only when the new snapshot contains an item that was NOT
            // present
            // in the previous snapshot (additive diff via Set). First snapshot (no previous doc) ->
            // 0.
            val versionHistoryUpdated =
                hasNewItem(resolved.versionHistory, existing?.versionHistory)
            val tweetsUpdated = hasNewItem(resolved.tweets, existing?.tweets)

            val updated =
                AppMetadata(
                    version = nextVersion,
                    blockId = event.blockId,
                    blockNumber = event.blockNumber,
                    blockTimestamp = event.blockTimestamp,
                    appId = appId,
                    metadataURI = newMetadataURI,
                    metadataResolved = true,
                    metadataFetchedAt = System.currentTimeMillis(),
                    versionHistory = resolved.versionHistory,
                    tweets = resolved.tweets,
                    versionHistoryUpdated = versionHistoryUpdated,
                    tweetsUpdated = tweetsUpdated,
                    optionalFieldsCount = resolved.optionalFieldsCount,
                )
            accumulator.put(recordId, existing, updated)
        }

        return accumulator.results()
    }

    @Transactional(rollbackFor = [Exception::class])
    open fun save(updated: List<AppMetadata>, existing: List<AppMetadata>) {
        saveVersionedDocuments(
            updated = updated,
            existing = existing,
            mongoTemplate = mongoTemplate,
            blockWindow = inlineVersioningProperties.blockWindow,
            maxVersions = inlineVersioningProperties.maxVersions,
            minVersions = inlineVersioningProperties.minVersions,
        )
    }

    private fun appIdOf(event: IndexedEvent): String? =
        event.params.getReturnValues()["appId"]?.toString()?.let { normalise(it) }

    /**
     * Returns 1 iff [current] holds at least one item not contained in [previous]; 0 otherwise.
     * [previous] == null (no prior snapshot for this app) -> 0. Comparison is content-based, so the
     * element type must have value semantics ([AppMetadataVersionEntry] is a data class, tweets are
     * [String]). Order and pure removals/reorders do not count as an update.
     */
    private fun <T> hasNewItem(current: List<T>, previous: List<T>?): Int {
        if (previous == null) return 0
        val previousSet = previous.toSet()
        return if (current.any { it !in previousSet }) 1 else 0
    }
}
