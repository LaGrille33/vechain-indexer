package org.vechain.indexer.xapp.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClients
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.config.CachingThorClient
import org.vechain.indexer.config.InlineVersioningProperties
import org.vechain.indexer.event.model.generic.AbiEventParameters
import org.vechain.indexer.fixtures.IndexedEventsFixtures
import org.vechain.indexer.xapp.metadata.repository.AppMetadataRepository

/**
 * Manual integration test for the first-run backfill in [AppMetadataService]: it verifies that
 * `X2EarnApps.apps()` is decoded correctly (the `tuple[]` path) and that [bootstrapIfEmpty]
 * snapshots every registered app into [xapp_metadata][IndexerNames.XAPP_METADATA] when the
 * collection is empty.
 *
 * This is a no-Spring, hand-wired test mirroring [AppRoundScoreServiceTest]: a [MongoTemplate] is
 * built from [mongoUri], the service is `new`ed directly, and the public methods are invoked. No
 * Spring context is booted.
 *
 * ## Scratch database
 * The backfill test writes into the [databaseName] scratch database (NOT the indexer's `vechain`
 * db) and drops its `xapp_metadata` collection first, so the backfill runs every time and never
 * touches real indexer data.
 *
 * ## Prerequisites
 * - A VeBetterDAO Thor node reachable at [thorUrl] (its `apps()` is read at the best block) and the
 *   IPFS gateway reachable at [ipfsGateway] (each app's metadata JSON is fetched).
 * - A MongoDB reachable at [mongoUri] (it only needs to accept writes to [databaseName]).
 *
 * ## Running
 * - [Disabled] so it never runs in `make test` / CI. Remove the `@Disabled` line (or comment it
 *   out) and run the methods from the IDE.
 */
// @Disabled("Manual integration test against a real Thor node + MongoDB. Remove @Disabled to run.")
internal class AppMetadataBootstrapTest {

    /** Connection string of a reachable MongoDB — the test only writes to [databaseName]. */
    private val mongoUri = "mongodb://root:password@165.154.61.63:27018/vechain?authSource=admin"

    /**
     * Scratch database the backfill test owns end-to-end (kept separate from the live `vechain`
     * db).
     */
    private val databaseName = "vechain_bootstrap_test"

    /** VeBetterDAO test chain backing [thorUrl] — `apps()` is read here. */
    private val thorUrl = "https://vebetterdaothortest.smartcarbonlink.com"

    /** X2EarnApps proxy address on [thorUrl] (dev value, see packages/indexer/.env.dev). */
    private val x2EarnAppsContract = "0xd0d5dc52b273dbed064dd61d980c92462865f5dc"

    /** IPFS gateway used to resolve each app's metadataURI (dev value, see application.yaml). */
    private val ipfsGateway = "https://api.gateway-proxy.vechain.org/ipfs/"

    private val mongoTemplate = MongoTemplate(MongoClients.create(mongoUri), databaseName)

    private val thorClient = CachingThorClient(thorUrl, "X-Project-Id" to "veworld-indexer")

    /** Builds the service by hand, wiring a Spring Data repository proxy around [mongoTemplate]. */
    private fun newService(): AppMetadataService {
        val repository =
            MongoRepositoryFactory(mongoTemplate).getRepository(AppMetadataRepository::class.java)
        return AppMetadataService(
            repository = repository,
            mongoTemplate = mongoTemplate,
            inlineVersioningProperties = InlineVersioningProperties(),
            ipfsMetadataClient = IpfsMetadataClient(ObjectMapper(), ipfsGateway),
            thorClient = thorClient,
            x2EarnAppsContract = x2EarnAppsContract,
        )
    }

    /**
     * Decodes `X2EarnApps.apps()` against the live chain and prints every registered app's id and
     * metadataURI. Verifies the `tuple[]` decode path ([fetchAllApps]) in isolation — no MongoDB
     * read or write happens here.
     */
    @Test
    fun `fetchAllApps decodes the apps tuple and prints id and metadataURI`() = runBlocking {
        val apps = newService().fetchAllApps()

        println("apps() decoded ${apps.size} registered app(s):")
        apps.entries
            .sortedBy { it.key }
            .forEach { (appId, uri) -> println("  $appId  metadataURI=$uri") }

        assertTrue(
            apps.isNotEmpty(),
            "apps() returned no apps — check the contract address / chain",
        )
        apps.keys.forEach {
            assertTrue(
                it.matches(Regex("^0x[0-9a-f]{64}$")),
                "appId is not a lower-cased bytes32 hex: $it",
            )
        }
        apps.values.forEach { assertTrue(it.isNotBlank(), "blank metadataURI") }
    }

    /**
     * Runs [bootstrapIfEmpty] against an empty [xapp_metadata][IndexerNames.XAPP_METADATA]
     * collection (dropped first in the scratch db), persists the result, and asserts that every
     * registered app — except the dummy triggering app — was written as a first-version snapshot.
     *
     * The triggering event carries [TRIGGER_APP_ID], a bytes32 that is not a registered app, so no
     * real app is excluded and the entire `apps()` set is backfilled.
     */
    @Test
    fun `bootstrapIfEmpty backfills every registered app when the collection is empty`() =
        runBlocking {
            val collection = IndexerNames.XAPP_METADATA.COLLECTION
            mongoTemplate.dropCollection(collection)
            val before = mongoTemplate.count(Query(), collection)
            println("$databaseName.$collection count after drop: $before")

            val trigger =
                IndexedEventsFixtures.buildIndexedEvent(
                    eventType = "AppMetadataURIUpdated",
                    blockId = "0xbootstrap",
                    blockNumber = 0L,
                    blockTimestamp = System.currentTimeMillis() / 1000,
                    params =
                        AbiEventParameters(
                            mapOf("appId" to TRIGGER_APP_ID, "newMetadataURI" to "ipfs://dummy"),
                            "AppMetadataURIUpdated",
                        ),
                )

            val service = newService()
            val (updated, existing) = service.bootstrapIfEmpty(listOf(trigger))
            assertTrue(
                updated.isNotEmpty(),
                "bootstrapIfEmpty wrote nothing — was the collection non-empty after drop, " +
                    "or is the IPFS gateway ($ipfsGateway) unreachable? Every app whose metadata " +
                    "can't be resolved is skipped.",
            )
            service.save(updated, existing)

            val after = mongoTemplate.count(Query(), collection)
            println(
                "bootstrapIfEmpty built + saved ${updated.size} doc(s); " +
                    "$databaseName.$collection count: $before -> $after"
            )
            assertEquals(updated.size.toLong(), after, "not all backfilled docs were persisted")

            mongoTemplate
                .find(Query(), Document::class.java, collection)
                .sortedBy { it.getString("appId") }
                .take(10)
                .forEach { println(it.toJson()) }

            // Every backfilled snapshot is a first version (collection was empty) with update
            // flags 0, and the dummy triggering app must NOT appear (it is excluded).
            updated.forEach {
                assertEquals(1, it.version)
                assertEquals(0, it.versionHistoryUpdated)
                assertEquals(0, it.tweetsUpdated)
            }
            assertTrue(
                updated.none { it.appId == TRIGGER_APP_ID },
                "the dummy triggering app was backfilled — it should be excluded",
            )
            // sanity: scores/flags that feed the Profile Score are populated
            updated.forEach { assertTrue(it.optionalFieldsCount in 0..8) }
        }

    companion object {
        // A bytes32 that is not a registered app, so it is excluded from the backfill and every
        // real app from apps() is written.
        private const val TRIGGER_APP_ID =
            "0x0000000000000000000000000000000000000000000000000000000000000001"
    }
}
