package org.vechain.indexer.b3tr.round

import jakarta.annotation.PostConstruct
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.event.UserFirstSeenAggregationCompletedEvent
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.event.utils.FunctionReturnDecoder
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.utils.ContractUtils

/**
 * Computes the **Activity Score**, **Distribution Efficiency Score**, and **Community Score** for
 * each dApp per round, as defined in the VBD Reputation Layer Proposal. Triggered by
 * [UserFirstSeenAggregationCompletedEvent] to ensure
 * [b3tr_user_first_seen][IndexerNames.USER_FIRST_SEEN] data is up-to-date.
 *
 * ## App set per round
 * The set of apps scored for a round is the authoritative on-chain list from
 * `XAllocationVoting.getAppIdsOfRound(roundId)` — not just the apps that happen to have action
 * data. The full on-chain set is also the percentile population for the activity and distribution
 * scores: apps without action data contribute `0` to every metric, forming the baseline that active
 * apps rank above (and they themselves are percentile-ranked at the bottom). This keeps scores
 * meaningful even when only a few apps have activity. Community Score is still computed
 * independently over all apps with rating data.
 *
 * ## Activity Score algorithm
 * For each round, per dApp:
 * 1. **WAU** = distinct users with rewarded actions (row count in
 *    `b3tr_app_action_summaries_round`)
 * 2. **NewUsers** = users whose `firstRoundId` equals the target round (from
 *    `b3tr_user_first_seen`)
 * 3. **Actions** = total rewarded actions (sum of `actionsRewarded`)
 * 4. Apply **log scaling**: `ln(1 + value)`
 * 5. Compute **percentile rank** across all dApps (with tie handling)
 * 6. `ActivityScore = 0.5 × P_WAU + 0.3 × P_NewUsers + 0.2 × P_Actions`
 *
 * ## Distribution Efficiency Score algorithm (Appendix A.3)
 * 1. **Alloc_7d** = B3TR allocated to the dApp this round, from
 *    [b3tr_x_alloc_results][IndexerNames.X_ALLOC_RESULT].`totalAmount` (already 18-decimal scaled
 *    to B3TR units)
 * 2. **Dist_7d** = B3TR actually distributed to users this round, = sum of `totalRewardAmount` in
 *    `b3tr_app_action_summaries_round` (same B3TR units, so the ratio is unit-consistent)
 * 3. `DistributionEfficiency = min(Dist_7d / Alloc_7d, 1)`, or `0` when `Alloc_7d == 0`
 * 4. `DistributionScore = PercentileRank(DistributionEfficiency)` across all dApps (with tie
 *    handling), in `[0, 100]`
 *
 * ## Community Score algorithm (round-independent snapshot)
 * Computed over the current rating/review state (no round filter) and merged into each scored app:
 * 1. Aggregate `AvgRating` / `NumRatings` from [xapp_ratings][IndexerNames.XAPP_RATING], and
 *    `HelpfulVotes` / `UnhelpfulVotes` from [xapp_review_votes][IndexerNames.XAPP_REVIEW_VOTE]
 *    (joined to [xapp_reviews][IndexerNames.XAPP_REVIEW] on `reviewId`, excluding hidden reviews).
 * 2. `ConfidenceFactor = 1 - e^(-NumRatings / K)`, `K = 100`.
 * 3. `HelpfulRatio = HelpfulVotes / (HelpfulVotes + UnhelpfulVotes)` (`0.5` when no votes);
 *    `EngagementFactor = 0.5 + 0.5 × HelpfulRatio`.
 * 4. `AdjustedRating = AvgRating × ConfidenceFactor × EngagementFactor` (`0` when `NumRatings <
 *    R_min`, `R_min = 20`).
 * 5. `CommunityScore = PercentileRank(AdjustedRating)` across all apps with rating data (with tie
 *    handling), in `[0, 100]`. Apps without rating data get `CommunityScore = 0`.
 */
@Service
@Profile("b3tr", "b3tr-app-round-score")
class AppRoundScoreService(
    private val mongoTemplate: MongoTemplate,
    private val thorClient: ThorClient,
    @param:Value("\${business-event.substitutions.X_ALLOC_VOTING_CONTRACT}")
    private val xAllocVotingContract: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * ABI for `XAllocationVoting.getAppIdsOfRound(uint256) -> bytes32[]`, the authoritative
     * on-chain list of app ids participating in a round. Loaded lazily from `abis/b3tr` (the
     * function was added to `VeBetterDAO-x-allocations-voting.json`).
     */
    private val getAppIdsOfRoundAbi: AbiElement by lazy {
        AbiLoader.loadFunctions("abis/b3tr", listOf("getAppIdsOfRound")).firstOrNull {
            it.name == "getAppIdsOfRound"
        } ?: throw IllegalArgumentException("Function 'getAppIdsOfRound' not found in ABI")
    }

    companion object {
        // Weights from the proposal
        private val WEIGHT_WAU = BigDecimal("0.5")
        private val WEIGHT_NEW_USERS = BigDecimal("0.3")
        private val WEIGHT_ACTIONS = BigDecimal("0.2")

        private val HUNDRED = BigDecimal("100")
        private val HALF = BigDecimal("0.5")

        // Community Score constants (VBD Reputation Layer Proposal, Community Score)
        private const val R_MIN = 20L // Minimum ratings to be eligible
        private val K = BigDecimal("100") // Smoothing constant for ConfidenceFactor
        private val COMMUNITY_MC = MathContext(20, RoundingMode.HALF_UP)

        // ln1pBig helpers
        private val TWO = BigDecimal(2)
        private val LN2 = BigDecimal("0.69314718055994530941723212145817656807550013436025")
        private val MATH_CTX = MathContext(34, RoundingMode.HALF_UP)

        // Internal computation scale; results are rounded to 4 dp when stored.
        private const val COMPUTE_SCALE = 10
        private const val MAX_LN_ITER = 200

        /**
         * Percentile rank with tie handling. `P(x_i) = (# { j | x_j < x_i } + 0.5 × T) / N × 100`
         * where T = number of values tied with x_i. Static utility — callable from Kotlin and Java
         * as `AppRoundScoreService.percentileRank(value, allValues)`.
         *
         * Operates on [BigDecimal] so comparisons and ties are exact; `below`, `tied`, and `n` are
         * exact integer counts. Returns `0` for an empty population.
         *
         * @param value the value to rank
         * @param allValues the full population to rank against (may include [value])
         * @return percentile rank in `[0, 100]`, computed at [COMPUTE_SCALE] precision
         */
        @JvmStatic
        fun percentileRank(value: BigDecimal, allValues: List<BigDecimal>): BigDecimal {
            val n = allValues.size
            if (n == 0) return BigDecimal.ZERO
            var below = 0
            var tied = 0
            for (v in allValues) {
                when (v.compareTo(value)) {
                    -1 -> below++
                    0 -> tied++
                }
            }
            val belowBd = BigDecimal(below)
            val nBd = BigDecimal(n)
            return if (tied == 1) {
                belowBd.divide(nBd, COMPUTE_SCALE, RoundingMode.HALF_UP).multiply(HUNDRED)
            } else {
                belowBd
                    .add(HALF.multiply(BigDecimal(tied)))
                    .divide(nBd, COMPUTE_SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
            }
        }

        /**
         * High-precision `ln(1 + x)` for non-negative x, computed entirely with [BigDecimal] (no
         * Double). Static utility — callable from Kotlin and Java as
         * `AppRoundScoreService.ln1pBig(x)`.
         *
         * Argument reduction `1+x = m · 2^p` with `m ∈
         * [1, 2)`, then `ln(m) = 2 · artanh((m-1)/(m+1))`; the artanh series converges fast because `(m-1)/(m+1) ∈ [0, 1/3)`. `ln(2)` is a precomputed high-precision constant, so the whole chain stays exact up to the [MATH_CTX]
         * working precision. Returns `0` for `x ≤ 0`.
         *
         * @param x a non-negative value
         * @return `ln(1 + x)` at [MATH_CTX] precision
         */
        @JvmStatic
        fun ln1pBig(x: BigDecimal): BigDecimal {
            if (x.signum() <= 0) return BigDecimal.ZERO
            val y = x.add(BigDecimal.ONE) // 1 + x, y >= 1

            // reduce y = m · 2^p, m in [1, 2)
            var p = 0
            var m = y
            while (m.compareTo(TWO) >= 0) {
                m = m.divide(TWO, MATH_CTX)
                p++
            }

            // ln(m) = 2 · artanh(t), t = (m-1)/(m+1), t in [0, 1/3)
            val t = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MATH_CTX)

            // artanh(t) = Σ_{k>=0} t^(2k+1)/(2k+1)
            var sum = BigDecimal.ZERO
            var term = t // t^1
            var k = 1
            var guard = 0
            while (guard++ < MAX_LN_ITER) {
                val inc = term.divide(BigDecimal(k), MATH_CTX)
                sum = sum.add(inc, MATH_CTX)
                if (inc.signum() == 0) break
                term = term.multiply(t, MATH_CTX).multiply(t, MATH_CTX) // t^(2k+1) -> t^(2k+3)
                k += 2
            }
            return sum.multiply(TWO, MATH_CTX).add(BigDecimal(p).multiply(LN2, MATH_CTX), MATH_CTX)
        }
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

    data class AppMetrics(val actions: Long, val wau: Long, val distributedB3tr: BigDecimal)

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
        val xAllocCollection = IndexerNames.X_ALLOC_RESULT.COLLECTION
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
                xAllocCollection = xAllocCollection,
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
        xAllocCollection: String,
        targetCollection: String,
        roundId: Int,
        firstSeenAvailable: Boolean,
    ): Int {
        // 0. Authoritative app list for this round comes from the XAllocationVoting contract
        //    (getAppIdsOfRound). The full set — including apps with no action data — is scored and
        //    forms the percentile population (see below).
        val onChainAppIds = fetchAppIdsOfRound(roundId)
        if (onChainAppIds.isEmpty()) {
            logger.info(
                "No apps returned by getAppIdsOfRound for round {} (chain error or empty round); skipping",
                roundId,
            )
            // No apps to score → nothing to persist → skip the round.
            return 0
        }

        // 1. Aggregate per-app metrics from action summaries (a subset of the on-chain apps)
        val appMetrics = aggregateAppMetrics(sourceCollection, roundId)

        // 2. Count new users per app from first-seen collection
        val newUsersData =
            if (firstSeenAvailable) {
                countNewUsers(firstSeenCollection, roundId)
            } else {
                emptyMap()
            }

        // 2b. Fetch B3TR allocations per app from x-allocation results (Alloc_7d)
        val allocations = getAllocations(xAllocCollection, roundId)

        // The percentile population is the FULL on-chain app set for the round. Apps without
        // action-summary data contribute 0 to every metric, so they form the baseline that active
        // apps rank above — and they themselves are percentile-ranked at the bottom. This keeps
        // scores meaningful even when only a few apps have activity.

        // 3. Collect raw values for percentile computation (BigDecimal; exact for Long metrics)
        val allWau = onChainAppIds.map { BigDecimal(appMetrics[it]?.wau ?: 0L) }
        val allNewUsers = onChainAppIds.map { BigDecimal(newUsersData[it] ?: 0L) }
        val allActions = onChainAppIds.map { BigDecimal(appMetrics[it]?.actions ?: 0L) }

        // Pre-compute log-scaled values once (BigDecimal ln1p), reused for every app's ranking
        val logAllWau = allWau.map { ln1pBig(it) }
        val logAllNewUsers = allNewUsers.map { ln1pBig(it) }
        val logAllActions = allActions.map { ln1pBig(it) }

        // 3b. Pre-compute distribution efficiencies per app (population for percentile ranking)
        val efficiencies =
            onChainAppIds.associateWith { appId ->
                distributionEfficiency(
                    appMetrics[appId]?.distributedB3tr ?: BigDecimal.ZERO,
                    allocations[appId] ?: BigDecimal.ZERO,
                )
            }
        val allEfficiencies = efficiencies.values.toList()

        // 3c. Community Score — computed over the current rating/review state (round-independent),
        // then merged into each scored app. Population is all apps with rating data.
        val communityScores = computeCommunityScores()

        // 4. Build and persist per-app score documents
        val now = System.currentTimeMillis()
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, targetCollection)
        var count = 0

        for (appId in onChainAppIds) {
            val metrics = appMetrics[appId]

            // Log scaling (BigDecimal throughout); apps without data contribute ln(1 + 0) = 0
            val logWau = ln1pBig(BigDecimal(metrics?.wau ?: 0L))
            val logNew = ln1pBig(BigDecimal(newUsersData[appId] ?: 0L))
            val logAct = ln1pBig(BigDecimal(metrics?.actions ?: 0L))

            // Percentile ranks with tie handling (BigDecimal comparisons — exact ties)
            val pWau = percentileRank(logWau, logAllWau)
            val pNew =
                if (firstSeenAvailable) {
                    percentileRank(logNew, logAllNewUsers)
                } else BigDecimal.ZERO
            val pAct = percentileRank(logAct, logAllActions)

            // Weighted activity score (BigDecimal arithmetic)
            val activityScore =
                WEIGHT_WAU.multiply(pWau)
                    .add(WEIGHT_NEW_USERS.multiply(pNew))
                    .add(WEIGHT_ACTIONS.multiply(pAct))

            // Distribution efficiency score (Appendix A.3)
            val distB3tr = metrics?.distributedB3tr ?: BigDecimal.ZERO
            val allocB3tr = allocations[appId] ?: BigDecimal.ZERO
            val distEff = efficiencies[appId] ?: BigDecimal.ZERO
            val distScore = percentileRank(distEff, allEfficiencies)

            // Community Score (round-independent snapshot merged into this app's round document)
            val community = communityScores[appId]

            val id = "${appId}_$roundId"
            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .set("_id", id)
                    .set("appId", appId)
                    .set("roundId", roundId)
                    .set("wau", metrics?.wau ?: 0L)
                    .set("newUsers", newUsersData[appId] ?: 0L)
                    .set("actions", metrics?.actions ?: 0L)
                    .set("pWau", pWau.setScale(4, RoundingMode.HALF_UP))
                    .set("pNewUsers", pNew.setScale(4, RoundingMode.HALF_UP))
                    .set("pActions", pAct.setScale(4, RoundingMode.HALF_UP))
                    .set("activityScore", activityScore.setScale(4, RoundingMode.HALF_UP))
                    .set("allocB3tr", allocB3tr.setScale(6, RoundingMode.HALF_UP))
                    .set("distributedB3tr", distB3tr.setScale(6, RoundingMode.HALF_UP))
                    .set("distributionEfficiency", distEff.setScale(6, RoundingMode.HALF_UP))
                    .set("distributionScore", distScore.setScale(4, RoundingMode.HALF_UP))
                    .set(
                        "avgRating",
                        (community?.avgRating ?: BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP),
                    )
                    .set("numRatings", community?.numRatings ?: 0L)
                    .set("helpfulVotes", community?.helpfulVotes ?: 0L)
                    .set("unhelpfulVotes", community?.unhelpfulVotes ?: 0L)
                    .set(
                        "confidenceFactor",
                        (community?.confidenceFactor ?: BigDecimal.ZERO).setScale(
                            6,
                            RoundingMode.HALF_UP,
                        ),
                    )
                    .set(
                        "helpfulRatio",
                        (community?.helpfulRatio ?: BigDecimal.ZERO).setScale(
                            6,
                            RoundingMode.HALF_UP,
                        ),
                    )
                    .set(
                        "engagementFactor",
                        (community?.engagementFactor ?: BigDecimal.ZERO).setScale(
                            6,
                            RoundingMode.HALF_UP,
                        ),
                    )
                    .set(
                        "adjustedRating",
                        (community?.adjustedRating ?: BigDecimal.ZERO).setScale(
                            6,
                            RoundingMode.HALF_UP,
                        ),
                    )
                    .set(
                        "communityScore",
                        (community?.communityScore ?: BigDecimal.ZERO).setScale(
                            4,
                            RoundingMode.HALF_UP,
                        ),
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

    // ──────────────────────────── On-chain app lookup ────────────────────────────

    /**
     * Calls `XAllocationVoting.getAppIdsOfRound(roundId)` at the best block and returns the app ids
     * (on-chain `bytes32`) as lower-cased `0x`-hex strings — the same format used as `appId` in the
     * action-summary and x-allocation collections. Returns an empty list if the call reverts or
     * yields no data, so [scoreRound] can treat that as "nothing to score for this round".
     */
    private fun fetchAppIdsOfRound(roundId: Int): List<String> = runBlocking {
        val clause =
            ContractUtils.createClause(
                xAllocVotingContract,
                getAppIdsOfRoundAbi,
                BigInteger.valueOf(roundId.toLong()),
            )
        val response = thorClient.inspectClauses(listOf(clause)).first()
        if (response.reverted) {
            logger.warn("getAppIdsOfRound({}) reverted: {}", roundId, response.vmError)
            return@runBlocking emptyList()
        }
        val data = response.data
        if (data.isBlank() || data == "0x") return@runBlocking emptyList()

        val decoded = FunctionReturnDecoder.decode(data, getAppIdsOfRoundAbi.outputs)
        val raw = decoded[""] as? List<*> ?: emptyList<Any?>()
        raw.mapNotNull { normalizeAppId(it) }
    }

    /**
     * Normalizes a decoded `bytes32` element to a lower-cased `0x`-hex string. indexer-core's
     * `Types.BYTES` decoder returns static `bytes32` as a `0x`-prefixed hex [String] (verified in
     * `FunctionReturnDecoderTest`), so `getAppIdsOfRound`'s `bytes32[]` output is a `List<String>`.
     * Anything unexpected yields `null`, so the offending app id is skipped rather than crashing
     * the whole round.
     */
    private fun normalizeAppId(value: Any?): String? {
        if (value !is String) {
            logger.warn("Unexpected app-id element type: {}", value?.javaClass?.name)
            return null
        }
        val hex = value.lowercase()
        return if (hex.startsWith("0x")) hex else "0x$hex"
    }

    // ──────────────────────────── Aggregation helpers ────────────────────────────

    /**
     * Groups by appId for a given round, producing:
     * - `actions` = sum of `actionsRewarded`
     * - `wau` = count of rows (= distinct users in this app+round)
     * - `distributedB3tr` = sum of `totalRewardAmount` (= Dist_7d; already 18-decimal scaled to
     *   B3TR units by the action-summary write path)
     */
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
            val wau = toLong(doc.get("wau"))
            val distributedB3tr = toBigDecimal(doc.get("distributedB3tr"))
            map[appId] = AppMetrics(actions = actions, wau = wau, distributedB3tr = distributedB3tr)
        }
        return map
    }

    /**
     * Reads per-app B3TR allocation (Alloc_7d) for a round from
     * [b3tr_x_alloc_results][IndexerNames.X_ALLOC_RESULT], keyed by lower-cased `appId`.
     * `totalAmount` is already 18-decimal scaled to B3TR units; a `null`/absent value (no rewards
     * claimed yet) is treated as `0`.
     */
    private fun getAllocations(collection: String, roundId: Int): Map<String, BigDecimal> {
        val results =
            mongoTemplate.find(
                Query(Criteria.where("roundId").`is`(roundId)),
                Document::class.java,
                collection,
            )
        val map = mutableMapOf<String, BigDecimal>()
        for (doc in results) {
            val appId = doc.getString("appId")?.lowercase() ?: continue
            map[appId] = toBigDecimal(doc.get("totalAmount"))
        }
        return map
    }

    /**
     * Distribution efficiency = `min(Dist_7d / Alloc_7d, 1)`, or `0` when `Alloc_7d == 0` (VBD
     * Reputation Layer Proposal, Appendix A.3). Both inputs are in human B3TR units, so the ratio
     * is unit-consistent. Result is in `[0, 1]`.
     */
    private fun distributionEfficiency(distributed: BigDecimal, allocated: BigDecimal): BigDecimal =
        if (allocated.compareTo(BigDecimal.ZERO) > 0) {
            val ratio = distributed.divide(allocated, COMPUTE_SCALE, RoundingMode.HALF_UP)
            if (ratio.compareTo(BigDecimal.ONE) > 0) BigDecimal.ONE else ratio
        } else {
            BigDecimal.ZERO
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

    // ──────────────────────────── Community Score ────────────────────────────

    /** Community Score components for a single app, persisted into the round-score document. */
    private data class CommunityScoreData(
        val avgRating: BigDecimal,
        val numRatings: Long,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
        val confidenceFactor: BigDecimal,
        val helpfulRatio: BigDecimal,
        val engagementFactor: BigDecimal,
        val adjustedRating: BigDecimal,
        val communityScore: BigDecimal,
    )

    private data class CommunityRatingStats(
        val appId: String,
        val avgRating: BigDecimal,
        val numRatings: Long,
    )

    private data class CommunityVoteStats(
        val appId: String,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
    )

    private class CommunityAppData(
        val appId: String,
        val avgRating: BigDecimal,
        val numRatings: Long,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
        var adjustedRating: BigDecimal = BigDecimal.ZERO,
        var communityScore: BigDecimal = BigDecimal.ZERO,
    )

    /**
     * Computes the Community Score for every app that has rating data (VBD Reputation Layer
     * Proposal, Community Score). This is a pure calculation over the current rating/review state
     * with no round dependency; the population for percentile ranking is all apps with rating data.
     * Returns a map keyed by lower-cased `appId` so it can be merged into the round-score documents
     * (whose appIds are also lower-cased).
     */
    private fun computeCommunityScores(): Map<String, CommunityScoreData> {
        val ratingStats = aggregateCommunityRatingStats()
        if (ratingStats.isEmpty()) return emptyMap()

        val voteStats = aggregateCommunityVoteStats()

        // Compute adjusted rating for each app with rating data
        val appDataList = mutableListOf<CommunityAppData>()
        for (rs in ratingStats.values) {
            val vs = voteStats[rs.appId]
            val data =
                CommunityAppData(
                    appId = rs.appId,
                    avgRating = rs.avgRating,
                    numRatings = rs.numRatings,
                    helpfulVotes = vs?.helpfulVotes ?: 0L,
                    unhelpfulVotes = vs?.unhelpfulVotes ?: 0L,
                )
            data.adjustedRating = computeAdjustedRating(data)
            appDataList.add(data)
        }

        // Percentile rank across all apps (reuses the shared mid-rank implementation)
        val allAdjustedRatings = appDataList.map { it.adjustedRating }
        for (data in appDataList) {
            data.communityScore =
                if (data.numRatings < R_MIN) BigDecimal.ZERO
                else percentileRank(data.adjustedRating, allAdjustedRatings)
        }

        return appDataList.associateBy(
            keySelector = { it.appId },
            valueTransform = { data ->
                val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
                CommunityScoreData(
                    avgRating = data.avgRating,
                    numRatings = data.numRatings,
                    helpfulVotes = data.helpfulVotes,
                    unhelpfulVotes = data.unhelpfulVotes,
                    confidenceFactor = computeConfidenceFactor(data.numRatings),
                    helpfulRatio = helpfulRatio,
                    engagementFactor = computeEngagementFactor(helpfulRatio),
                    adjustedRating = data.adjustedRating,
                    communityScore = data.communityScore,
                )
            },
        )
    }

    private fun aggregateCommunityRatingStats(): Map<String, CommunityRatingStats> {
        val collection = IndexerNames.XAPP_RATING.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("_id").ne(null)),
                Aggregation.group("appId").count().`as`("count").avg("rating").`as`("average"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, CommunityRatingStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String)?.lowercase() ?: return@forEach
            val count = toLong(doc.get("count"))
            val average = BigDecimal.valueOf((doc.get("average") as? Number)?.toDouble() ?: 0.0)
            map[appId] =
                CommunityRatingStats(appId = appId, avgRating = average, numRatings = count)
        }
        return map
    }

    private fun aggregateCommunityVoteStats(): Map<String, CommunityVoteStats> {
        val collection = IndexerNames.XAPP_REVIEW_VOTE.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                // Join votes with reviews to get appId and check isHidden
                Aggregation.lookup(
                    IndexerNames.XAPP_REVIEW.COLLECTION,
                    "reviewId",
                    "reviewId",
                    "review",
                ),
                Aggregation.unwind("review"),
                Aggregation.match(Criteria.where("review.isHidden").`is`(false)),
                // voteType 1 = upvote (helpful), 2 = downvote (unhelpful)
                Aggregation.group("review.appId")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(1))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("helpfulVotes")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(2))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("unhelpfulVotes"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, CommunityVoteStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String)?.lowercase() ?: return@forEach
            val helpful = toLong(doc.get("helpfulVotes"))
            val unhelpful = toLong(doc.get("unhelpfulVotes"))
            map[appId] =
                CommunityVoteStats(
                    appId = appId,
                    helpfulVotes = helpful,
                    unhelpfulVotes = unhelpful,
                )
        }
        return map
    }

    /**
     * ConfidenceFactor = 1 - e^(-NumRatings / K). The exponential is transcendental, so it is
     * evaluated via Double then wrapped in BigDecimal to preserve all remaining arithmetic.
     */
    private fun computeConfidenceFactor(numRatings: Long): BigDecimal {
        val exponent = -numRatings.toDouble() / K.toDouble()
        return BigDecimal.ONE.subtract(exp(exponent).toBigDecimal(), COMMUNITY_MC)
    }

    /** HelpfulRatio = helpfulVotes / (helpfulVotes + unhelpfulVotes), default 0.5 if no votes. */
    private fun computeHelpfulRatio(helpfulVotes: Long, unhelpfulVotes: Long): BigDecimal {
        val total = helpfulVotes + unhelpfulVotes
        return if (total == 0L) {
            BigDecimal("0.5")
        } else {
            BigDecimal(helpfulVotes).divide(BigDecimal(total), COMMUNITY_MC)
        }
    }

    /** EngagementFactor = 0.5 + 0.5 * HelpfulRatio, range [0.5, 1.0] */
    private fun computeEngagementFactor(helpfulRatio: BigDecimal): BigDecimal =
        BigDecimal("0.5").add(BigDecimal("0.5").multiply(helpfulRatio, COMMUNITY_MC), COMMUNITY_MC)

    /**
     * AdjustedRating = AvgRating * ConfidenceFactor * EngagementFactor (0 when NumRatings < R_min).
     */
    private fun computeAdjustedRating(data: CommunityAppData): BigDecimal {
        if (data.numRatings < R_MIN) return BigDecimal.ZERO
        val confidenceFactor = computeConfidenceFactor(data.numRatings)
        val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
        val engagementFactor = computeEngagementFactor(helpfulRatio)
        return data.avgRating
            .multiply(confidenceFactor, COMMUNITY_MC)
            .multiply(engagementFactor, COMMUNITY_MC)
    }
}

private fun toLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }

private fun toBigDecimal(value: Any?): BigDecimal =
    when (value) {
        is BigDecimal -> value
        // Decimal128 (MongoDB driver) and other Number subtypes carry the full-precision value in
        // toString(), so round-tripping through the string avoids Double precision loss.
        is Number -> BigDecimal(value.toString())
        else -> BigDecimal.ZERO
    }
