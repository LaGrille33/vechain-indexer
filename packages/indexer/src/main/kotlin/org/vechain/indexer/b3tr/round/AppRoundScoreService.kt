package org.vechain.indexer.b3tr.round

import jakarta.annotation.PostConstruct
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln1p
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.event.UserFirstSeenAggregationCompletedEvent

/**
 * Computes the Activity Score for each dApp per round, as defined in the VBD Reputation Layer
 * Proposal. Triggered by [UserFirstSeenAggregationCompletedEvent] to ensure
 * [b3tr_user_first_seen][IndexerNames.USER_FIRST_SEEN] data is up-to-date.
 *
 * ## Algorithm
 * For each round, per dApp:
 * 1. **WAU** = distinct users with rewarded actions (row count in
 *    `b3tr_app_action_summaries_round`)
 * 2. **NewUsers** = users whose `firstRoundId` equals the target round (from
 *    `b3tr_user_first_seen`)
 * 3. **Actions** = total rewarded actions (sum of `actionsRewarded`)
 * 4. Apply **log scaling**: `ln(1 + value)`
 * 5. Compute **percentile rank** across all dApps (with tie handling)
 * 6. `ActivityScore = 0.5 × P_WAU + 0.3 × P_NewUsers + 0.2 × P_Actions`
 */
@Service
@Profile("b3tr", "b3tr-app-round-score")
class AppRoundScoreService(private val mongoTemplate: MongoTemplate) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Weights from the proposal
        private const val WEIGHT_WAU = 0.5
        private const val WEIGHT_NEW_USERS = 0.3
        private const val WEIGHT_ACTIONS = 0.2
    }

    @PostConstruct
    fun initIndexes() {
        val collectionName = IndexerNames.APP_ROUND_SCORE.COLLECTION
        if (!mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.createCollection(collectionName)
        }
        val indexOps = mongoTemplate.indexOps(collectionName)
        indexOps.ensureIndex(
            Index().on("appId", Sort.Direction.ASC).on("roundId", Sort.Direction.ASC).unique()
        )
        indexOps.ensureIndex(Index().on("roundId", Sort.Direction.ASC))
        logger.info("Indexes ensured for collection {}", collectionName)
    }

    data class AppMetrics(val actions: Long, val wau: Long)

    // ──────────────────────────── Entry point ────────────────────────────

    @EventListener
    fun onFirstSeenCompleted(event: UserFirstSeenAggregationCompletedEvent) {
        if (event.processedUpToRound <= 0) {
            logger.info("something is error for get round by Thor, skipping AppRoundScore")
            return
        }
        try {
            computeScores(event.processedUpToRound)
        } catch (e: Exception) {
            logger.error("AppRoundScore computation failed", e)
        }
    }

    // ──────────────────────────── Core logic ────────────────────────────

    private fun computeScores(targetRound: Int) {
        val started = System.currentTimeMillis()
        logger.info("Starting AppRoundScore computation for round {}...", targetRound)

        val sourceCollection = IndexerNames.APP_ROUND_ACTION_SUMMARY.COLLECTION
        val firstSeenCollection = IndexerNames.USER_FIRST_SEEN.COLLECTION
        val targetCollection = IndexerNames.APP_ROUND_SCORE.COLLECTION

        val firstSeenAvailable = mongoTemplate.collectionExists(firstSeenCollection)
        if (!firstSeenAvailable) {
            logger.warn(
                "Collection {} does not exist, newUsers will be 0 for all apps",
                firstSeenCollection,
            )
        }

        val totalScored =
            scoreRound(
                sourceCollection = sourceCollection,
                firstSeenCollection = firstSeenCollection,
                targetCollection = targetCollection,
                roundId = targetRound,
                firstSeenAvailable = firstSeenAvailable,
            )

        val elapsed = System.currentTimeMillis() - started
        logger.info(
            "AppRoundScore completed: round {}, {} apps scored, {}ms elapsed",
            targetRound,
            totalScored,
            elapsed,
        )
    }

    private fun scoreRound(
        sourceCollection: String,
        firstSeenCollection: String,
        targetCollection: String,
        roundId: Int,
        firstSeenAvailable: Boolean,
    ): Int {
        // 1. Aggregate per-app metrics from action summaries
        val appMetrics = aggregateAppMetrics(sourceCollection, roundId)
        if (appMetrics.isEmpty()) return 0

        // 2. Count new users per app from first-seen collection
        val newUsersData =
            if (firstSeenAvailable) {
                countNewUsers(firstSeenCollection, roundId)
            } else {
                emptyMap()
            }

        val allAppIds = appMetrics.keys

        // 3. Collect raw values for percentile computation
        val allWau = allAppIds.map { appMetrics[it]?.wau?.toDouble() ?: 0.0 }
        val allNewUsers = allAppIds.map { (newUsersData[it] ?: 0L).toDouble() }
        val allActions = allAppIds.map { appMetrics[it]?.actions?.toDouble() ?: 0.0 }

        // 4. Build and persist per-app score documents
        val now = System.currentTimeMillis()
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, targetCollection)
        var count = 0

        for (appId in allAppIds) {
            val metrics = appMetrics[appId] ?: continue
            val wau = metrics.wau.toDouble()
            val newUsers = (newUsersData[appId] ?: 0L).toDouble()
            val actions = metrics.actions.toDouble()

            // Log scaling
            val logWau = ln1p(wau)
            val logNew = ln1p(newUsers)
            val logAct = ln1p(actions)

            // Percentile ranks with tie handling
            val pWau = percentileRank(logWau, allWau.map { ln1p(it) })
            val pNew =
                if (firstSeenAvailable) {
                    percentileRank(logNew, allNewUsers.map { ln1p(it) })
                } else 0.0
            val pAct = percentileRank(logAct, allActions.map { ln1p(it) })

            // Weighted activity score
            val activityScore = WEIGHT_WAU * pWau + WEIGHT_NEW_USERS * pNew + WEIGHT_ACTIONS * pAct

            val id = "${appId}_$roundId"
            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .set("_id", id)
                    .set("appId", appId)
                    .set("roundId", roundId)
                    .set("wau", metrics.wau)
                    .set("newUsers", newUsersData[appId] ?: 0L)
                    .set("actions", metrics.actions)
                    .set("pWau", BigDecimal(pWau).setScale(4, RoundingMode.HALF_UP))
                    .set("pNewUsers", BigDecimal(pNew).setScale(4, RoundingMode.HALF_UP))
                    .set("pActions", BigDecimal(pAct).setScale(4, RoundingMode.HALF_UP))
                    .set(
                        "activityScore",
                        BigDecimal(activityScore).setScale(4, RoundingMode.HALF_UP),
                    )
                    .set("updatedAt", now),
            )
            count++
        }

        if (count > 0) {
            bulkOps.execute()
        }
        logger.info("Round {} scored: {} apps", roundId, count)
        return count
    }

    // ──────────────────────────── Aggregation helpers ────────────────────────────

    /**
     * Groups by appId for a given round, producing:
     * - `actions` = sum of `actionsRewarded`
     * - `wau` = count of rows (= distinct users in this app+round)
     */
    private fun aggregateAppMetrics(collection: String, roundId: Int): Map<String, AppMetrics> {
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("roundId").`is`(roundId)),
                Aggregation.group("appId")
                    .sum("actionsRewarded")
                    .`as`("actions")
                    .count()
                    .`as`("wau"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppMetrics>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String)?.lowercase() ?: return@forEach
            val actions = toLong(doc.get("actions"))
            val wau = toLong(doc.get("wau"))
            map[appId] = AppMetrics(actions = actions, wau = wau)
        }
        return map
    }

    /** Counts new users per appId where `firstRoundId == roundId`. */
    private fun countNewUsers(collection: String, roundId: Int): Map<String, Long> {
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("firstRoundId").`is`(roundId)),
                Aggregation.group("appId").count().`as`("count"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, Long>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String)?.lowercase() ?: return@forEach
            map[appId] = toLong(doc.get("count"))
        }
        return map
    }

    // ──────────────────────────── Math helpers ────────────────────────────

    /**
     * Percentile rank with tie handling. `P(x_i) = (# { j | x_j < x_i } + 0.5 × T) / N × 100` where
     * T = number of values tied with x_i.
     */
    private fun percentileRank(value: Double, allValues: List<Double>): Double {
        val n = allValues.size
        if (n == 0) return 0.0
        val below = allValues.count { it < value }
        val tied = allValues.count { it == value }
        return (below + 0.5 * tied) / n * 100.0
    }
}

private fun toLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }
