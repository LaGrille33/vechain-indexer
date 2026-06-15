package org.vechain.indexer.b3tr.round

import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.event.UserFirstSeenAggregationCompletedEvent
import org.vechain.indexer.config.CachingThorClient

/**
 * Manual integration test that runs [AppRoundScoreService.onFirstSeenCompleted] against a real
 * MongoDB instance whose source collections are already populated, then prints the resulting
 * `b3tr_app_round_scores` documents.
 *
 * This is intentionally a no-Spring, hand-wired test: we build a [MongoTemplate] from [mongoUri],
 * `new` the service directly (bypassing `@Profile` / `@EventListener`), and invoke the method. No
 * Spring context is booted, so nothing else (Thor node, IndexManager, other indexers) starts.
 *
 * ## Prerequisites
 * - A MongoDB reachable at [mongoUri] with rows in `b3tr_app_action_summaries_round` for [roundId]
 *   (and optionally `b3tr_user_first_seen`, `b3tr_x_alloc_results`, `xapp_ratings`,
 *   `xapp_review_votes`).
 * - Set [roundId] to a round that actually has action-summary rows; otherwise `scoreRound`
 *   short-circuits and writes nothing.
 *
 * ## Running
 * - This test is [Disabled] so it never runs in `make test` / CI. To run it, remove the `@Disabled`
 *   line (or comment it out) and execute the method from the IDE.
 * - It upserts into `b3tr_app_round_scores` with `_id = appId_roundId`; re-running is idempotent.
 */
// @Disabled("Manual integration test against a real MongoDB. Set `roundId` and remove @Disabled to
// run.")
internal class AppRoundScoreServiceTest {

    /**
     * Connection string of the real MongoDB — mirrors packages/indexer/application-test.properties.
     */
    private val mongoUri = "mongodb://indexer:password@165.154.61.63:27018/vechain?authSource=admin"

    /** Database name to connect to (the db segment of [mongoUri]). */
    private val databaseName = "vechain"

    /**
     * VeBetterDAO test chain backing [mongoUri] — adjust if your Mongo data comes from a different
     * chain. `getAppIdsOfRound` is called here to determine which apps belong to [roundId].
     */
    private val thorUrl = "https://vebetterdaothortest.smartcarbonlink.com"

    /** XAllocationVoting proxy address on [thorUrl] (dev value, see packages/indexer/.env.dev). */
    private val xAllocVotingContract = "0x9819ba0daaeae450e3d0f5cd4e3adafb2475a632"

    /** Round to score — change to a round that has data in `b3tr_app_action_summaries_round`. */
    private val roundId = 7

    private val mongoTemplate = MongoTemplate(MongoClients.create(mongoUri), databaseName)

    private val thorClient = CachingThorClient(thorUrl, "X-Project-Id" to "veworld-indexer")

    @Test
    fun `onFirstSeenCompleted scores a real round and prints results`() {
        val service = AppRoundScoreService(mongoTemplate, thorClient, xAllocVotingContract)

        // Hint: print the rounds that actually have action-summary data, in case roundId needs
        // adjusting before running.
        val availableRounds =
            mongoTemplate.findDistinct(
                Query(),
                "roundId",
                IndexerNames.APP_ROUND_ACTION_SUMMARY.COLLECTION,
                Document::class.java,
                Int::class.java,
            )
        println(
            "Available roundIds in ${IndexerNames.APP_ROUND_ACTION_SUMMARY.COLLECTION}: $availableRounds"
        )

        service.onFirstSeenCompleted(UserFirstSeenAggregationCompletedEvent(roundId))

        val results =
            mongoTemplate.find(
                Query(Criteria.where("roundId").`is`(roundId)),
                Document::class.java,
                IndexerNames.APP_ROUND_SCORE.COLLECTION,
            )

        println(
            "Round $roundId → ${results.size} app(s) scored in ${IndexerNames.APP_ROUND_SCORE.COLLECTION}"
        )
        results
            .sortedBy { it.getString("appId") }
            .forEach { doc ->
                println("---- appId=${doc.getString("appId")} ----")
                println(doc.toJson())
            }
    }
}
