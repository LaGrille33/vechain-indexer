package org.vechain.indexer.xapp.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.vechain.indexer.utils.ContractUtils
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
    private val thorClient: ThorClient,
    @param:Value("\${business-event.substitutions.X2EARN_APPS_CONTRACT}")
    private val x2EarnAppsContract: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * ABI for `X2EarnApps.apps() -> tuple[]` (id, teamWalletAddress, name, metadataURI,
     * createdAtTimestamp, appAvailableForAllocationVoting). Used to enumerate EVERY registered app
     * — including ineligible ones — and its current `metadataURI` when backfilling xapp_metadata on
     * first run. Loaded lazily from `abis/b3tr`.
     */
    private val appsAbi: AbiElement by lazy {
        AbiLoader.loadFunctions("abis/b3tr", listOf("apps")).firstOrNull { it.name == "apps" }
            ?: throw IllegalArgumentException("Function 'apps' not found in b3tr ABIs")
    }

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
                    badgeSettings = resolved.badgeSettings,
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

    /**
     * One-time backfill: if xapp_metadata is empty, snapshot metadata for every registered app
     * EXCEPT the ones [processEvents] is about to handle in this batch (the triggering apps).
     * Called from [AppMetadataProcessor.processEntry] before normal event processing, so the first
     * `AppMetadataURIUpdated` event on a fresh database populates the whole app set instead of
     * leaving the collection with a single app.
     *
     * Every registered app — including ineligible ones — and its `metadataURI` are read from the
     * X2EarnApps contract (`apps()`); the IPFS JSON is then resolved through [IpfsMetadataClient]
     * exactly as [processEvents] does. Backfilled snapshots are first versions (no prior document),
     * so their `versionHistoryUpdated` / `tweetsUpdated` flags are `0`, and they are stamped with
     * the triggering event's block so they fit the same versioning window.
     *
     * Best-effort: any failure (contract revert, IPFS outage) is logged and swallowed so it never
     * blocks normal event processing. Apps whose metadata can't be resolved are simply skipped —
     * they'll be written later when their own `AppMetadataURIUpdated` event arrives. No-op (returns
     * empty lists) once the collection is non-empty (`repository.count() > 0`). Returns the built
     * `(updated, existing)` pair — like [processEvents] — for the caller to persist via [save] (so
     * the write goes through the `@Transactional` proxy rather than a self-invocation).
     */
    open suspend fun bootstrapIfEmpty(
        events: List<IndexedEvent>
    ): Pair<List<AppMetadata>, List<AppMetadata>> {
        val skip: Pair<List<AppMetadata>, List<AppMetadata>> =
            emptyList<AppMetadata>() to emptyList()
        try {
            if (events.none { it.eventType == "AppMetadataURIUpdated" }) return skip
            // Only backfill when the collection is truly empty; afterwards this is a cheap no-op.
            if (repository.count() > 0L) return skip

            val triggeringAppIds =
                events
                    .filter { it.eventType == "AppMetadataURIUpdated" }
                    .mapNotNull { appIdOf(it) }
                    .toSet()
            val triggerEvent = events.first { it.eventType == "AppMetadataURIUpdated" }

            // Every registered app (including ineligible ones) and its current metadataURI, read in
            // a single apps() call. Exclude the apps this batch is about to handle normally.
            val metadataByApp = fetchAllApps().filterKeys { it !in triggeringAppIds }
            if (metadataByApp.isEmpty()) return skip

            // Resolve IPFS concurrently: this is a one-time backfill but the app set can be large
            // and each fetch has retries, so parallelize to avoid stalling block processing.
            val resolvedByApp: Map<String, IpfsMetadataClient.ResolvedAppMetadata> =
                coroutineScope {
                    metadataByApp.entries
                        .map { (appId, uri) -> async { appId to ipfsMetadataClient.resolve(uri) } }
                        .awaitAll()
                        .mapNotNull { (appId, resolved) -> resolved?.let { appId to it } }
                        .toMap()
                }
            if (resolvedByApp.isEmpty()) return skip

            // Same versioned build path as processEvents, so the backfilled documents are shaped
            // identically to event-driven ones. existing is null for every app (collection was
            // empty), so each is a first version with update flags 0. The caller persists the
            // result via save() so it goes through the @Transactional proxy.
            val accumulator =
                VersionedDocumentAccumulator<AppMetadata>(
                    findById = { repository.findByIdOrNull(it) }
                )
            for (appId in resolvedByApp.keys) {
                val metadataURI = metadataByApp[appId] ?: continue
                val resolved = resolvedByApp[appId] ?: continue
                accumulator.startBlock()
                val recordId = AppMetadata.documentId(appId)
                val (existing, nextVersion) = accumulator.resolve(recordId)
                val updated =
                    AppMetadata(
                        version = nextVersion,
                        blockId = triggerEvent.blockId,
                        blockNumber = triggerEvent.blockNumber,
                        blockTimestamp = triggerEvent.blockTimestamp,
                        appId = appId,
                        metadataURI = metadataURI,
                        metadataResolved = true,
                        metadataFetchedAt = System.currentTimeMillis(),
                        versionHistory = resolved.versionHistory,
                        tweets = resolved.tweets,
                        versionHistoryUpdated =
                            hasNewItem(resolved.versionHistory, existing?.versionHistory),
                        tweetsUpdated = hasNewItem(resolved.tweets, existing?.tweets),
                        optionalFieldsCount = resolved.optionalFieldsCount,
                        badgeSettings = resolved.badgeSettings,
                    )
                accumulator.put(recordId, existing, updated)
            }
            val (updated, existing) = accumulator.results()
            if (updated.isNotEmpty()) {
                logger.info(
                    "Bootstrapped xapp_metadata with {} app(s) (collection was empty)",
                    updated.size,
                )
            }
            return updated to existing
        } catch (e: Exception) {
            logger.warn("xapp_metadata bootstrap failed", e)
            return skip
        }
    }

    /**
     * Reads every registered app's `id` and `metadataURI` from `X2EarnApps.apps()` (a `tuple[]`),
     * returning a lower-cased `0x`-hex app id → metadataURI map. Every registered app is included
     * (eligible or not); apps whose `metadataURI` is blank are omitted. Returns an empty map if the
     * call reverts or yields nothing.
     */
    internal suspend fun fetchAllApps(): Map<String, String> {
        val clause = ContractUtils.createClause(x2EarnAppsContract, appsAbi)
        val response = thorClient.inspectClauses(listOf(clause)).firstOrNull() ?: return emptyMap()
        if (response.reverted) {
            logger.warn("apps() reverted on {}: {}", x2EarnAppsContract, response.vmError)
            return emptyMap()
        }
        val decoded = FunctionReturnDecoder.decode(response.data, appsAbi.outputs)
        val rows = decoded[""] as? List<*> ?: emptyList<Any?>()
        val result = mutableMapOf<String, String>()
        for (row in rows) {
            // Each row decodes to a Map keyed by the tuple component names
            // (id, teamWalletAddress, name, metadataURI, createdAtTimestamp,
            // appAvailableForAllocationVoting).
            val fields = row as? Map<*, *> ?: continue
            val appId = normalizeAppId(fields["id"]) ?: continue
            val uri = (fields["metadataURI"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            result[appId] = uri
        }
        return result
    }

    /**
     * Normalizes a decoded `bytes32` app id to a lower-cased `0x`-hex string, or null if not a
     * string.
     */
    private fun normalizeAppId(value: Any?): String? {
        if (value !is String) return null
        val hex = value.lowercase()
        return if (hex.startsWith("0x")) hex else "0x$hex"
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
