package org.vechain.indexer.b3tr.round

import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.config.CachingThorClient

/**
 * Manual integration test that runs [AppRoundScoreService.awardBadges] in isolation against a real
 * MongoDB instance.
 *
 * Unlike [AppRoundScoreServiceTest] (which runs the whole `computeScores` pipeline via
 * `onFirstSeenCompleted`), this only exercises badge awarding: it reads the already-computed
 * `b3tr_app_round_scores` for [roundId] (and the navigator ranking, plus `b3tr_cycle_info` for the
 * round timestamp), then upserts the three xApp badge types into `xapp_badge_awards` and prints the
 * result grouped by badge type.
 *
 * This is a no-Spring, hand-wired test, mirroring [AppRoundScoreServiceTest]: a [MongoTemplate] is
 * built from [mongoUri], the service is `new`ed directly (bypassing `@Profile` / `@EventListener`),
 * and the private `awardBadges(roundId)` is invoked via reflection — no production visibility
 * change is needed. `awardBadges` only touches Mongo, so [thorClient] / [xAllocVotingContract] are
 * irrelevant here but required by the constructor.
 *
 * ## Prerequisites
 * - `b3tr_app_round_scores` populated for [roundId] (run the scoring pipeline first, or pick a
 *   round the backfill already covered — the VeBetterDAO test chain has rounds 7–12).
 * - Optionally `b3tr_navigator_dapp_vote_round_rankings` for [roundId] (else NAVIGATORS_PICK is a
 *   no-op) and `b3tr_cycle_info` (else `roundBlockTimestamp` is `null` on the badges).
 *
 * ## Running
 * - `@Disabled` so it never runs in `make test` / CI — it writes to the real `xapp_badge_awards`
 *   collection. Comment out the `@Disabled` line below and run the method from the IDE.
 * - Idempotent: badges are upserted on `_id = appId_badgeType_round`, so re-running produces no
 *   duplicates (the "newly awarded" count will be `0` on a second run).
 */
// @Disabled(
//    "Manual integration test against a real MongoDB. Set `roundId` and remove @Disabled to run."
// )
internal class AppRoundScoreAwardBadgesTest {

    /**
     * Connection string of the real MongoDB — mirrors packages/indexer/application-test.properties.
     */
    private val mongoUri = "mongodb://indexer:password@165.154.61.63:27018/vechain?authSource=admin"

    /** Database name to connect to (the db segment of [mongoUri]). */
    private val databaseName = "vechain"

    /**
     * VeBetterDAO test chain backing [mongoUri] — unused by `awardBadges` but required by the
     * [AppRoundScoreService] constructor.
     */
    private val thorUrl = "https://vebetterdaothortest.smartcarbonlink.com"

    /** XAllocationVoting proxy address on [thorUrl] (dev value, see packages/indexer/.env.dev). */
    private val xAllocVotingContract = "0x9819ba0daaeae450e3d0f5cd4e3adafb2475a632"

    /** Round to award badges for — must have rows in `b3tr_app_round_scores`. */
    private val roundId = 12

    private val mongoTemplate = MongoTemplate(MongoClients.create(mongoUri), databaseName)

    private val thorClient = CachingThorClient(thorUrl, "X-Project-Id" to "veworld-indexer")

    @Test
    fun `awardBadges awards xApp badges for a real round and prints results`() {
        val service = AppRoundScoreService(mongoTemplate, thorClient, xAllocVotingContract)

        val before = badgesForRound(roundId)
        println(
            "Before: ${before.size} badge(s) for round $roundId in " +
                "${IndexerNames.XAPP_BADGE_AWARD.COLLECTION}"
        )

        invokeAwardBadges(service, roundId)

        val after = badgesForRound(roundId)
        val existingIds = before.map { it["_id"] }.toSet()
        val newlyAwarded = after.filter { it["_id"] !in existingIds }

        println(
            "After: ${after.size} badge(s) for round $roundId " +
                "(${newlyAwarded.size} newly awarded this run)"
        )
        after
            .groupBy { it.getString("badgeType") }
            .toSortedMap()
            .forEach { (type, docs) ->
                println("---- $type (${docs.size}) ----")
                docs
                    .sortedBy { it.getInteger("rank") ?: Int.MAX_VALUE }
                    .forEach { println(it.toJson()) }
            }
    }

    /**
     * Reads every badge award persisted for [roundId] (queried as Long to match the stored BSON
     * Int64).
     */
    private fun badgesForRound(roundId: Int): List<Document> =
        mongoTemplate.find(
            Query(Criteria.where("round").`is`(roundId.toLong())),
            Document::class.java,
            IndexerNames.XAPP_BADGE_AWARD.COLLECTION,
        )

    /**
     * Invokes the private `awardBadges(roundId: Int)` via reflection so this test can exercise the
     * badge logic in isolation without changing the method's visibility.
     */
    private fun invokeAwardBadges(service: AppRoundScoreService, roundId: Int) {
        val method =
            AppRoundScoreService::class
                .java
                .getDeclaredMethod("awardBadges", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(service, roundId)
    }
}
