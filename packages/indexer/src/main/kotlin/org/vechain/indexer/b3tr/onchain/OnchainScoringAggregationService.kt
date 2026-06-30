package org.vechain.indexer.b3tr.onchain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ln1p
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.onchain.repository.AppOnchainScoreRepository
import org.vechain.indexer.b3tr.onchain.repository.EcosystemRoundSnapshotRepository

@Service
@Profile("b3tr", "b3tr-onchain-scoring")
class OnchainScoringAggregationService(
    private val mongoTemplate: MongoTemplate,
    private val appOnchainScoreRepository: AppOnchainScoreRepository,
    private val ecosystemRoundSnapshotRepository: EcosystemRoundSnapshotRepository,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val WATERMARK_ID = "onchain_scoring_watermark"
        private const val WATERMARK_COLLECTION = "b3tr_onchain_scoring_watermark"
    }

    //    @Scheduled(
    //        fixedDelayString = "\${indexer.onchain-scoring.interval-ms:3600000}",
    //        initialDelayString = "\${indexer.onchain-scoring.initial-delay-ms:120000}",
    //    )
    fun aggregateOnchainScores() {
        try {
            aggregateOnchainScoresInternal()
        } catch (e: Exception) {
            logger.error("On-chain scoring aggregation failed", e)
        }
    }

    private fun aggregateOnchainScoresInternal() {
        val started = System.currentTimeMillis()
        logger.info("Starting on-chain scoring aggregation...")

        val sourceCollection = IndexerNames.APP_ROUND_ACTION_SUMMARY.COLLECTION
        val scoreCollection = IndexerNames.ONCHAIN_SCORE.COLLECTION
        val ecosystemCollection = IndexerNames.ECOSYSTEM_ROUND_SNAPSHOT.COLLECTION

        // 1. Find the latest round in the action summaries
        val maxRoundQuery = Query().with(Sort.by(Sort.Direction.DESC, "roundId")).limit(1)
        val latestDoc = mongoTemplate.findOne(maxRoundQuery, Document::class.java, sourceCollection)
        if (latestDoc == null) {
            logger.info("No data in source collection {}, skipping", sourceCollection)
            return
        }
        val currentMaxRound = latestDoc.getInteger("roundId")
        logger.info("Source collection {} max roundId={}", sourceCollection, currentMaxRound)

        if (currentMaxRound <= 0) {
            logger.info("currentMaxRound={} is <= 0, nothing to score", currentMaxRound)
            return
        }

        // 2. Read watermark — score all rounds from (lastScoredRound + 1) up to currentMaxRound
        val watermarkDoc =
            mongoTemplate.findOne(
                Query(Criteria.where("_id").`is`(WATERMARK_ID)),
                Document::class.java,
                WATERMARK_COLLECTION,
            )
        val lastScoredRound = watermarkDoc?.getInteger("lastScoredRoundId") ?: 0

        if (currentMaxRound <= lastScoredRound) {
            logger.info(
                "No new rounds to score (currentMaxRound={}, lastScored={})",
                currentMaxRound,
                lastScoredRound,
            )
            return
        }

        val fromRound = lastScoredRound + 1
        val toRound = currentMaxRound
        logger.info("Scoring rounds {}..{} (total={})", fromRound, toRound, toRound - fromRound + 1)

        var totalScored = 0
        for (roundId in fromRound..toRound) {
            val ppRound = roundId - 1
            val count =
                scoreSingleRound(
                    sourceCollection,
                    scoreCollection,
                    ecosystemCollection,
                    roundId,
                    ppRound,
                )
            totalScored += count
        }

        // Update watermark to currentMaxRound
        val now = System.currentTimeMillis()
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update.update("lastScoredRoundId", currentMaxRound).set("updatedAt", now),
            WATERMARK_COLLECTION,
        )

        val elapsed = System.currentTimeMillis() - started
        logger.info(
            "On-chain scoring completed: rounds {}..{}, {} apps scored total, {}ms elapsed",
            fromRound,
            toRound,
            totalScored,
            elapsed,
        )
    }

    private fun scoreSingleRound(
        sourceCollection: String,
        scoreCollection: String,
        ecosystemCollection: String,
        targetRound: Int,
        priorPriorRound: Int,
    ): Int {

        // 3. Aggregate per-app metrics from AppRoundActionSummary for targetRound
        val priorAppMetrics = aggregateAppMetrics(sourceCollection, targetRound)
        logger.info("Round {}: {} apps with metrics", targetRound, priorAppMetrics.size)

        if (priorAppMetrics.isEmpty()) {
            return 0
        }

        // 4. Aggregate per-app metrics for prior-prior round (for rank delta)
        val ppAppMetrics =
            if (priorPriorRound > 0) {
                aggregateAppMetrics(sourceCollection, priorPriorRound)
            } else {
                emptyMap()
            }

        // 5. Get allocation data from XAllocResult
        val xAllocCollection = IndexerNames.X_ALLOC_RESULT.COLLECTION
        val priorAllocations = getAllocations(xAllocCollection, targetRound)
        val ppAllocations =
            if (priorPriorRound > 0) {
                getAllocations(xAllocCollection, priorPriorRound)
            } else {
                emptyMap()
            }
        logger.info(
            "Allocations: {} apps for round {}, {} apps for round {}",
            priorAllocations.size,
            targetRound,
            ppAllocations.size,
            priorPriorRound,
        )

        // 6. Get new users from UserFirstSeen
        val firstSeenCollection = IndexerNames.USER_FIRST_SEEN.COLLECTION
        val newUsersData = countNewUsers(firstSeenCollection, targetRound)
        val newUsersAvailable = mongoTemplate.collectionExists(firstSeenCollection)
        logger.info(
            "New users: {} apps with data, collection exists={}",
            newUsersData.size,
            newUsersAvailable,
        )

        // 7. Compute ranks by allocation (descending)
        val allAppIds = priorAppMetrics.keys
        val rankedByReward =
            computeRanks(allAppIds) { appId ->
                priorAllocations[appId]?.let { alloc ->
                    priorAppMetrics[appId]?.let { metrics ->
                        if (alloc > BigDecimal.ZERO) alloc else metrics.distributedB3tr
                    }
                } ?: BigDecimal.ZERO
            }

        val rankedByActions =
            computeRanks(allAppIds) { appId ->
                BigDecimal.valueOf(priorAppMetrics[appId]?.actions ?: 0L)
            }

        val ppRankedByReward =
            if (priorPriorRound > 0) {
                computeRanks(ppAppMetrics.keys) { appId ->
                    ppAllocations[appId]?.let { alloc ->
                        ppAppMetrics[appId]?.let { metrics ->
                            if (alloc > BigDecimal.ZERO) alloc else metrics.distributedB3tr
                        }
                    } ?: BigDecimal.ZERO
                }
            } else {
                emptyMap()
            }

        // 8. Compute Appendix-A scores
        val allWau = allAppIds.map { priorAppMetrics[it]?.wau?.toDouble() ?: 0.0 }
        val allNewUsers = allAppIds.map { appId -> (newUsersData[appId] ?: 0L).toDouble() }
        val allActions = allAppIds.map { priorAppMetrics[it]?.actions?.toDouble() ?: 0.0 }
        val allEfficiencies =
            allAppIds.map { appId ->
                val alloc = priorAllocations[appId] ?: BigDecimal.ZERO
                val dist = priorAppMetrics[appId]?.distributedB3tr ?: BigDecimal.ZERO
                if (alloc > BigDecimal.ZERO) minOf(dist.toDouble() / alloc.toDouble(), 1.0) else 0.0
            }

        // 9. Build and save per-app score documents
        val now = System.currentTimeMillis()
        var totalAlloc = BigDecimal.ZERO
        var totalDist = BigDecimal.ZERO
        var totalActions = 0L
        var totalWau = 0L
        var activeApps = 0L

        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, scoreCollection)
        var count = 0

        for (appId in allAppIds) {
            val metrics = priorAppMetrics[appId] ?: continue
            val allocRaw = priorAllocations[appId] ?: BigDecimal.ZERO
            val newUsers = newUsersData[appId]
            val rankReward = rankedByReward[appId]
            val rankActions = rankedByActions[appId]
            val ppRankReward = ppRankedByReward[appId]
            val rankDelta =
                if (rankReward != null && ppRankReward != null) {
                    ppRankReward - rankReward
                } else null

            val allocB3tr =
                allocRaw.divide(BigDecimal.valueOf(1e18.toLong()), 6, RoundingMode.HALF_UP)
            val distB3tr = metrics.distributedB3tr.setScale(6, RoundingMode.HALF_UP)

            // Appendix-A scoring
            val activityScore =
                computeActivityScore(
                    metrics.wau.toDouble(),
                    (newUsers ?: 0L).toDouble(),
                    metrics.actions.toDouble(),
                    allWau,
                    allNewUsers,
                    allActions,
                    newUsersAvailable,
                )
            val distEff = allEfficiencies[allAppIds.indexOf(appId)]
            val distScore = percentileRank(distEff, allEfficiencies)

            val id = "${appId}_$targetRound"
            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .set("_id", id)
                    .set("appId", appId)
                    .set("roundId", targetRound)
                    .set("name", appId.take(18))
                    .set("slug", appId.take(18).lowercase())
                    .set("teamWallet", null)
                    .set("allocB3tr", allocB3tr)
                    .set("distributedB3tr", distB3tr)
                    .set("actions", metrics.actions)
                    .set("wau", metrics.wau)
                    .set("newUsers", newUsers)
                    .set("rankByReward", rankReward)
                    .set("rankByActions", rankActions)
                    .set("rankPrior", ppRankReward)
                    .set("rankDelta", rankDelta)
                    .set("pWau", activityScore?.first)
                    .set("pNewUsers", activityScore?.second)
                    .set("pActions", activityScore?.third)
                    .set("activityScore", activityScore?.fourth)
                    .set(
                        "distributionEfficiency",
                        BigDecimal(distEff).setScale(6, RoundingMode.HALF_UP),
                    )
                    .set(
                        "distributionScore",
                        BigDecimal(distScore).setScale(4, RoundingMode.HALF_UP),
                    )
                    .set("updatedAt", now),
            )
            count++

            totalAlloc = totalAlloc.add(allocRaw)
            totalDist = totalDist.add(metrics.distributedB3tr)
            totalActions += metrics.actions
            totalWau += metrics.wau
            if (allocRaw > BigDecimal.ZERO || metrics.actions > 0) activeApps++
        }

        if (count > 0) {
            bulkOps.execute()
        }

        // 10. Save ecosystem snapshot
        val ecosystemSnapshot =
            Document()
                .append("_id", targetRound.toString())
                .append("roundId", targetRound)
                .append(
                    "totalB3trAllocated",
                    totalAlloc.divide(BigDecimal.valueOf(1e18.toLong()), 4, RoundingMode.HALF_UP),
                )
                .append("totalB3trDistributed", totalDist.setScale(4, RoundingMode.HALF_UP))
                .append("totalActions", totalActions)
                .append("totalWau", totalWau)
                .append("activeApps", activeApps)
                .append("eligibleAppsCount", allAppIds.size.toLong())
                .append("newUsersAvailable", newUsersAvailable)
                .append("updatedAt", now)
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(targetRound.toString())),
            Update.fromDocument(ecosystemSnapshot),
            ecosystemCollection,
        )

        logger.info("Round {} scored: {} apps", targetRound, count)
        return count
    }

    data class AppMetrics(val actions: Long, val distributedB3tr: BigDecimal, val wau: Long)

    private fun aggregateAppMetrics(collection: String, roundId: Int): Map<String, AppMetrics> {
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("roundId").`is`(roundId)),
                Aggregation.group("appId")
                    .sum("actionsRewarded")
                    .`as`("actions")
                    .sum("totalRewardAmount")
                    .`as`("distributedB3tr")
                    .count()
                    .`as`("wau"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppMetrics>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String)?.lowercase() ?: return@forEach
            val actions = toLong(doc.get("actions"))
            val distB3tr =
                when (val v = doc.get("distributedB3tr")) {
                    is BigDecimal -> v
                    is Number -> BigDecimal(v.toString())
                    else -> BigDecimal.ZERO
                }
            val wau = toLong(doc.get("wau"))
            map[appId] = AppMetrics(actions = actions, distributedB3tr = distB3tr, wau = wau)
        }
        return map
    }

    private fun getAllocations(collection: String, roundId: Int): Map<String, BigDecimal> {
        val query = Query(Criteria.where("roundId").`is`(roundId))
        val results = mongoTemplate.find(query, Document::class.java, collection)
        val map = mutableMapOf<String, BigDecimal>()
        for (doc in results) {
            val appId = doc.getString("appId")?.lowercase() ?: continue
            val totalAmount =
                when (val v = doc.get("totalAmount")) {
                    is BigDecimal -> v
                    is Number -> BigDecimal(v.toString())
                    else -> null
                }
            if (totalAmount != null) {
                map[appId] = totalAmount
            }
        }
        return map
    }

    private fun countNewUsers(collection: String, roundId: Int): Map<String, Long> {
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

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

    private fun computeRanks(
        appIds: Set<String>,
        valueExtractor: (String) -> BigDecimal,
    ): Map<String, Int> {
        val sorted = appIds.map { it to valueExtractor(it) }.sortedByDescending { it.second }
        val ranks = mutableMapOf<String, Int>()
        for ((index, pair) in sorted.withIndex()) {
            ranks[pair.first] = index + 1
        }
        return ranks
    }

    // Appendix-A scoring: P(x) = (below + 0.5*ties) / N * 100
    private fun percentileRank(value: Double, allValues: List<Double>): Double {
        val n = allValues.size
        if (n == 0) return 0.0
        val below = allValues.count { it < value }
        val tied = allValues.count { it == value }
        return (below + 0.5 * tied) / n * 100.0
    }

    private fun computeActivityScore(
        wau: Double,
        newUsers: Double,
        actions: Double,
        allWau: List<Double>,
        allNew: List<Double>,
        allActions: List<Double>,
        newUsersAvailable: Boolean,
    ): Tuple4<BigDecimal, BigDecimal, BigDecimal, BigDecimal>? {
        if (!newUsersAvailable) return null

        val logWau = ln1p(wau)
        val logNew = ln1p(newUsers)
        val logAct = ln1p(actions)

        val pWau = percentileRank(logWau, allWau.map { ln1p(it) })
        val pNew = percentileRank(logNew, allNew.map { ln1p(it) })
        val pAct = percentileRank(logAct, allActions.map { ln1p(it) })

        val mc = MathContext(6, RoundingMode.HALF_UP)
        val score =
            BigDecimal(0.5 * pWau + 0.3 * pNew + 0.2 * pAct).setScale(4, RoundingMode.HALF_UP)

        return Tuple4(
            BigDecimal(pWau).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(pNew).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(pAct).setScale(4, RoundingMode.HALF_UP),
            score,
        )
    }
}

private fun toLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }

private data class Tuple4<T1, T2, T3, T4>(
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4,
)
