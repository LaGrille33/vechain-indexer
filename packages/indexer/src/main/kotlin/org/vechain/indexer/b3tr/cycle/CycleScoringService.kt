package org.vechain.indexer.b3tr.cycle

import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.onchain.AppOnchainScore
import org.vechain.indexer.xapp.badge.BadgeType

@Service
@Profile("b3tr", "b3tr-cycle-scoring")
class CycleScoringService(private val mongoTemplate: MongoTemplate) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val WATERMARK_ID = "cycle_scoring_watermark"
        private const val WATERMARK_COLLECTION = "b3tr_cycle_scoring_watermark"
        private const val DEFAULT_TOP_N = 5
        private const val DISCORD_WINDOW_DAYS = 30L

        // Fixed rank-bonus points (sum = 25 for N=5); variable pool = 75
        private val FIXED_BONUSES = intArrayOf(13, 6, 3, 2, 1)

        // Ineligible statuses
        private val INELIGIBLE_STATUSES =
            setOf("shut-down", "shutting-down", "blacklisted", "pre-endorsement", "unendorsed")

        // Sentiment tokens
        private val POS_TOKENS =
            Regex(
                """\b(thanks|thank you|great|love|awesome|amazing|cool|nice|excited|launching|launched|live|congrats|partnership|update|roadmap|fixed|resolved|working|good|legend|appreciate)\b""",
                RegexOption.IGNORE_CASE,
            )
        private val NEG_TOKENS =
            Regex(
                """\b(bug|broken|down|error|fail|failed|stuck|issue|problem|crash|missing|wrong|delayed|frustrat|slow|scam|rug|silent|ghost|abandoned)\b""",
                RegexOption.IGNORE_CASE,
            )
        private val TEAM_KEYWORDS =
            Regex("""team|update|roadmap|launch|announce|release""", RegexOption.IGNORE_CASE)

        // Slug aliases for Discord channel → on-chain app matching
        private val SLUG_ALIASES =
            mapOf(
                "b3tr-smile" to "b3trsmile",
                "b3tr-transit" to "b3trtransit",
                "eat-up" to "eatup",
                "plant-to-earn" to "planttoearn",
                "turtle-labs" to "turtlelabs",
                "green-ambassador-challenge" to "greenambassadorchallenge",
                "bye-bye-bites" to "byebyebites",
                "zeloop" to "azeloopspotter",
                "nfbc" to "nonfungiblebookclubnfbc",
                "betterswap" to "pool2earnbybetterswap",
            )
    }

    data class DiscordChannelStats(
        val count: Int,
        val participants: Int,
        val sentiment: String,
        val posTokens: Int,
        val negTokens: Int,
    )

    data class ScoredApp(
        val appId: String,
        val name: String,
        val slug: String,
        val channelName: String?,
        val status: String,
        val discordStats: DiscordChannelStats?,
        val onchainScore: AppOnchainScore?,
        val breakdownCommunityTeam: Int,
        val breakdownStrategic: Int,
        val breakdownHealth: Int,
        val breakdownActivity: Int,
        val breakdownDistribution: Int,
        val total: Int,
        var allocation: Int = 0,
        var confidence: String? = null,
        var tier: String? = null,
    )

    @Scheduled(
        fixedDelayString = "\${indexer.cycle-scoring.interval-ms:7200000}",
        initialDelayString = "\${indexer.cycle-scoring.initial-delay-ms:30000}",
    )
    fun runCycleScoring() {
        try {
            runCycleScoringInternal()
        } catch (e: Exception) {
            logger.error("Cycle scoring failed", e)
        }
    }

    private fun runCycleScoringInternal() {
        val started = System.currentTimeMillis()
        logger.info("Starting cycle scoring...")

        val onchainCollection = IndexerNames.ONCHAIN_SCORE.COLLECTION
        val cycleCollection = IndexerNames.APP_CYCLE_SCORE.COLLECTION

        // 1. Find the latest scored round from on-chain scores
        val latestOnchain =
            mongoTemplate.findOne(
                Query().with(Sort.by(Sort.Direction.DESC, "roundId")).limit(1),
                AppOnchainScore::class.java,
                onchainCollection,
            )
        if (latestOnchain == null) {
            logger.info("No on-chain scores found, skipping cycle scoring")
            return
        }
        val currentMaxRound = latestOnchain.roundId
        logger.info("Latest on-chain score round: {}", currentMaxRound)

        // 2. Check watermark — score all rounds from (lastScoredRound + 1) up to currentMaxRound
        val watermarkDoc =
            mongoTemplate.findOne(
                Query(Criteria.where("_id").`is`(WATERMARK_ID)),
                org.bson.Document::class.java,
                WATERMARK_COLLECTION,
            )
        val lastScoredRound = watermarkDoc?.getInteger("lastScoredRoundId") ?: 0

        if (currentMaxRound <= lastScoredRound) {
            logger.info(
                "No new rounds to score (currentMax={}, lastScored={})",
                currentMaxRound,
                lastScoredRound,
            )
            return
        }

        val topN = watermarkDoc?.getInteger("topN") ?: DEFAULT_TOP_N

        // 3. Load Discord messages from the last 30 days (shared across all rounds)
        val discordStats = aggregateDiscordStats()
        logger.info("Discord stats: {} channels with activity", discordStats.size)

        // 4. Process each round from lastScoredRound+1 to currentMaxRound
        var totalCount = 0
        for (roundId in (lastScoredRound + 1)..currentMaxRound) {
            val count =
                scoreSingleRound(onchainCollection, cycleCollection, roundId, topN, discordStats)
            totalCount += count
        }

        // 5. Update watermark to currentMaxRound
        val now = System.currentTimeMillis()
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update.update("lastScoredRoundId", currentMaxRound)
                .set("topN", topN)
                .set("updatedAt", now),
            WATERMARK_COLLECTION,
        )

        val elapsed = System.currentTimeMillis() - started
        logger.info(
            "Cycle scoring completed: rounds {}..{}, {} apps scored total, topN={}, {}ms elapsed",
            lastScoredRound + 1,
            currentMaxRound,
            totalCount,
            topN,
            elapsed,
        )
    }

    private fun scoreSingleRound(
        onchainCollection: String,
        cycleCollection: String,
        roundId: Int,
        topN: Int,
        discordStats: Map<String, DiscordChannelStats>,
    ): Int {
        // Load all on-chain scores for this round
        val onchainScores =
            mongoTemplate.find(
                Query(Criteria.where("roundId").`is`(roundId)),
                AppOnchainScore::class.java,
                onchainCollection,
            )
        if (onchainScores.isEmpty()) {
            logger.info("Round {}: no on-chain scores, skipping", roundId)
            return 0
        }
        logger.info("Round {}: {} apps with on-chain scores", roundId, onchainScores.size)

        // Build scored app rows
        val scoredApps =
            onchainScores.map { oc ->
                val channelMatch = matchChannelToApp(oc.slug, discordStats)
                val stats = channelMatch.second
                val channelName = channelMatch.first

                val breakdownCommunityTeam = dimCommunityTeam(stats)
                val breakdownStrategic = dimStrategic(null)
                val breakdownHealth = dimHealth(stats, oc)
                val breakdownActivity = dimActivity(oc)
                val breakdownDistribution = dimDistribution(oc)
                val total =
                    breakdownCommunityTeam +
                        breakdownStrategic +
                        breakdownHealth +
                        breakdownActivity +
                        breakdownDistribution

                val status =
                    if (oc.allocB3tr > BigDecimal.ZERO || oc.actions > 0) "active"
                    else if (stats != null && stats.count > 0) "active" else "dormant"

                ScoredApp(
                    appId = oc.appId,
                    name = oc.name,
                    slug = oc.slug,
                    channelName = channelName,
                    status = status,
                    discordStats = stats,
                    onchainScore = oc,
                    breakdownCommunityTeam = breakdownCommunityTeam,
                    breakdownStrategic = breakdownStrategic,
                    breakdownHealth = breakdownHealth,
                    breakdownActivity = breakdownActivity,
                    breakdownDistribution = breakdownDistribution,
                    total = total,
                )
            }

        // Run allocation
        val allocated = allocate(scoredApps, topN)

        // Sort by total desc, assign confidence
        val sorted =
            allocated.sortedWith(
                compareByDescending<ScoredApp> { it.total }
                    .thenByDescending { it.breakdownCommunityTeam }
                    .thenByDescending { it.breakdownStrategic }
                    .thenByDescending { it.breakdownHealth }
                    .thenBy { it.name }
            )

        sorted.forEach { app -> app.confidence = confidenceLabel(app) }

        // Bulk upsert results
        val now = System.currentTimeMillis()
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, cycleCollection)
        var count = 0

        for (app in sorted) {
            val oc = app.onchainScore
            val ds = app.discordStats
            val id = "${app.appId}_$roundId"
            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .set("_id", id)
                    .set("appId", app.appId)
                    .set("roundId", roundId)
                    .set("name", app.name)
                    .set("slug", app.slug)
                    .set("channelName", app.channelName)
                    .set("status", app.status)
                    .set("discordMessageCount", ds?.count ?: 0)
                    .set("discordParticipants", ds?.participants ?: 0)
                    .set("discordSentiment", ds?.sentiment ?: "no activity")
                    .set("discordPosTokens", ds?.posTokens ?: 0)
                    .set("discordNegTokens", ds?.negTokens ?: 0)
                    .set("onchainRank", oc?.rankByReward)
                    .set("onchainRankDelta", oc?.rankDelta)
                    .set("onchainAllocB3tr", oc?.allocB3tr)
                    .set("onchainDistributedB3tr", oc?.distributedB3tr)
                    .set("onchainActions", oc?.actions)
                    .set("onchainWau", oc?.wau)
                    .set("onchainNewUsers", oc?.newUsers)
                    .set("onchainActivityScore", oc?.activityScore)
                    .set("onchainDistributionScore", oc?.distributionScore)
                    .set("breakdownCommunityTeam", app.breakdownCommunityTeam)
                    .set("breakdownStrategic", app.breakdownStrategic)
                    .set("breakdownHealth", app.breakdownHealth)
                    .set("breakdownActivity", app.breakdownActivity)
                    .set("breakdownDistribution", app.breakdownDistribution)
                    .set("total", app.total)
                    .set("allocation", app.allocation)
                    .set("confidence", app.confidence)
                    .set("tier", app.tier)
                    .set("updatedAt", now),
            )
            count++
        }

        if (count > 0) {
            bulkOps.execute()
        }

        logger.info("Round {} scored: {} apps", roundId, count)
        logSummary(sorted, roundId, topN)

        // Award badges for this round
        awardBadges(roundId, sorted)

        return count
    }

    // ── Discord sentiment analysis ──────────────────────────────────────

    private fun aggregateDiscordStats(): Map<String, DiscordChannelStats> {
        val discordCollection = IndexerNames.DISCORD_MESSAGE.COLLECTION
        if (!mongoTemplate.collectionExists(discordCollection)) {
            logger.info("Discord messages collection does not exist, skipping sentiment analysis")
            return emptyMap()
        }

        val windowStart = Instant.now().minus(DISCORD_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli()

        // Aggregate messages by channelName within the window, excluding bots
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(
                    Criteria.where("timestamp").gte(windowStart).and("isBot").ne(true)
                ),
                Aggregation.group("channelName")
                    .count()
                    .`as`("count")
                    .addToSet("authorName")
                    .`as`("participants")
                    .push("text")
                    .`as`("texts"),
            )

        val results =
            mongoTemplate.aggregate(aggregation, discordCollection, org.bson.Document::class.java)
        val statsMap = mutableMapOf<String, DiscordChannelStats>()

        results.forEach { doc ->
            val channelName = doc.getString("_id") ?: return@forEach
            val count = doc.getInteger("count") ?: 0
            @Suppress("UNCHECKED_CAST")
            val participants = (doc["participants"] as? List<String>)?.size ?: 0
            @Suppress("UNCHECKED_CAST") val texts = (doc["texts"] as? List<String>) ?: emptyList()
            val fullText = texts.joinToString(" ")

            val posCount = POS_TOKENS.findAll(fullText).count()
            val negCount = NEG_TOKENS.findAll(fullText).count()

            val sentiment =
                when {
                    posCount == 0 && negCount == 0 && count == 0 -> "no activity"
                    posCount > negCount * 1.5 && posCount >= 2 -> "positive"
                    negCount > posCount * 1.5 && negCount >= 2 -> "negative"
                    posCount + negCount >= 3 -> "mixed"
                    else -> "neutral"
                }

            statsMap[channelName] =
                DiscordChannelStats(
                    count = count,
                    participants = participants,
                    sentiment = sentiment,
                    posTokens = posCount,
                    negTokens = negCount,
                )
        }

        return statsMap
    }

    private fun slugify(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun matchChannelToApp(
        appSlug: String,
        discordStats: Map<String, DiscordChannelStats>,
    ): Pair<String?, DiscordChannelStats?> {
        val appSlugLower = appSlug.lowercase()

        // Try direct match or alias match against Discord channel names
        for ((channelName, stats) in discordStats) {
            val channelSlug = slugify(channelName)
            // Direct slug match
            if (channelSlug == appSlugLower) return channelName to stats
            // Alias match
            val alias = SLUG_ALIASES[channelName.lowercase()]
            if (alias != null && alias == appSlugLower) return channelName to stats
        }

        // Fuzzy fallback: substring match
        for ((channelName, stats) in discordStats) {
            val channelSlug = slugify(channelName)
            if (channelSlug.startsWith(appSlugLower) || appSlugLower.startsWith(channelSlug)) {
                return channelName to stats
            }
        }

        return null to null
    }

    // ── 5-dimension scoring (from run-cycle.js) ─────────────────────────

    /** Community & Team (0–20): Discord volume + participants + team-signal presence */
    private fun dimCommunityTeam(stats: DiscordChannelStats?): Int {
        if (stats == null || stats.count == 0) return 0
        val vol = min(10.0, log2(stats.count + 1.0) * 3).toInt()
        val ppl = min(5, stats.participants)
        val communityBase = vol + ppl
        // Team responsiveness heuristic — we can't inspect individual messages in aggregation,
        // so use a simplified approach based on volume
        val teamBoost = min(10, stats.count / 10)
        return min(20, communityBase + teamBoost)
    }

    /** Strategic Fit (0–20): category alignment — defaults to 12 without category data */
    private fun dimStrategic(category: String?): Int {
        if (category.isNullOrBlank()) return 12
        if (category.contains(Regex("Sustainability|Environment|Infrastructure|IoT"))) return 18
        if (category.contains(Regex("Health|Food|Lifestyle|Shopping|Circular"))) return 15
        if (category.contains(Regex("DeFi|Trading|Marketplace|NFT"))) return 10
        return 13
    }

    /** Health Score (0–20): positive base — penalised by negative signals */
    private fun dimHealth(stats: DiscordChannelStats?, ocApp: AppOnchainScore?): Int {
        var score = 20
        if (stats != null) {
            if (stats.sentiment == "negative") score -= 9
            else if (stats.sentiment == "mixed" && stats.negTokens > stats.posTokens) score -= 5
        }
        val rankDelta = ocApp?.rankDelta
        if (rankDelta != null && rankDelta <= -3) score -= 4
        return maxOf(0, score)
    }

    /** Activity Score (0–20): on-chain activity score (0–100) rescaled */
    private fun dimActivity(ocApp: AppOnchainScore?): Int {
        val activityScore = ocApp?.activityScore ?: return 0
        return min(20, (activityScore.toDouble() / 5.0).roundToInt())
    }

    /** Distribution Efficiency Score (0–20): on-chain distribution score (0–100) rescaled */
    private fun dimDistribution(ocApp: AppOnchainScore?): Int {
        val distScore = ocApp?.distributionScore ?: return 0
        return min(20, (distScore.toDouble() / 5.0).roundToInt())
    }

    // ── Allocation algorithm (from score.js) ─────────────────────────────

    private fun isEligible(app: ScoredApp): Boolean = app.status !in INELIGIBLE_STATUSES

    private fun allocate(apps: List<ScoredApp>, topN: Int): List<ScoredApp> {
        val eligible = apps.filter { isEligible(it) && it.total > 0 }
        val sorted =
            eligible.sortedWith(
                compareByDescending<ScoredApp> { it.total }
                    .thenByDescending { it.breakdownCommunityTeam }
                    .thenByDescending { it.breakdownStrategic }
                    .thenByDescending { it.breakdownHealth }
                    .thenBy { it.name }
            )
        val top = sorted.take(topN)
        if (top.isEmpty()) {
            apps.forEach { app ->
                app.allocation = 0
                app.tier = if (isEligible(app)) "Reserve" else "Ineligible"
            }
            return apps
        }

        val sum = top.sumOf { it.total }.coerceAtLeast(1)

        // Fixed rank bonuses + variable 75 pts proportional to score
        val fixed = top.mapIndexed { i, _ -> FIXED_BONUSES.getOrElse(i) { 0 } }
        val fixedSum = fixed.sum()
        val varPool = 100 - fixedSum
        val varRaw = top.map { (varPool * it.total) / sum.toDouble() }
        val varRounded = varRaw.map { it.roundToInt() }.toMutableList()

        // Adjust rounding remainder to last element
        var varDiff = varPool - varRounded.sum()
        if (varDiff != 0 && varRounded.isNotEmpty()) {
            varRounded[varRounded.lastIndex] += varDiff
        }

        val allocations =
            top.mapIndexed { i, app -> app.name to (fixed[i] + varRounded[i]) }.toMap()

        // Adjust total to exactly 100
        var totalDiff = 100 - allocations.values.sum()
        val adjustedAllocations = allocations.toMutableMap()
        if (totalDiff != 0 && adjustedAllocations.isNotEmpty()) {
            val lastKey = top.last().name
            adjustedAllocations[lastKey] = (adjustedAllocations[lastKey] ?: 0) + totalDiff
        }

        val topNames = top.map { it.name }.toSet()
        apps.forEach { app ->
            if (app.name in topNames) {
                app.allocation = adjustedAllocations[app.name] ?: 0
                app.tier = "Top $topN"
            } else {
                app.allocation = 0
                app.tier = if (isEligible(app)) "Reserve" else "Ineligible"
            }
        }
        return apps
    }

    // ── Confidence labels (from score.js) ────────────────────────────────

    private fun confidenceLabel(app: ScoredApp): String {
        val hasOnchain = app.onchainScore != null
        val hasSentiment =
            app.discordStats != null &&
                app.discordStats.count > 0 &&
                app.discordStats.sentiment != "no activity"
        val hasTeam = app.discordStats != null && app.discordStats.count >= 8
        val corrobs = listOf(hasOnchain, hasSentiment, hasTeam).count { it }
        return when {
            corrobs >= 3 && app.breakdownHealth >= 16 -> "High"
            corrobs >= 2 -> "Medium-High"
            corrobs >= 1 -> "Medium"
            else -> "Low"
        }
    }

    // ── Badge awarding ────────────────────────────────────────────────────

    private fun awardBadges(roundId: Int, sorted: List<ScoredApp>) {
        val badgeCollection = IndexerNames.XAPP_BADGE_AWARD.COLLECTION
        val now = System.currentTimeMillis()

        // 1. APP评分徽章 (TOP_ECOSYSTEM_DAPP): top 10% by total score
        awardTopPercentageBadge(
            badgeCollection,
            roundId,
            sorted,
            BadgeType.TOP_ECOSYSTEM_DAPP,
            valueSelector = { it.total.toDouble() },
            allowTieExpansion = true,
            now = now,
        )

        // 2. 高效分发徽章 (TOP_DISTRIBUTION_PERFORMER): top 10% by onchainDistributionScore
        val withDistScore = sorted.filter { it.onchainScore?.distributionScore != null }
        awardTopPercentageBadge(
            badgeCollection,
            roundId,
            withDistScore,
            BadgeType.TOP_DISTRIBUTION_PERFORMER,
            valueSelector = { it.onchainScore?.distributionScore?.toDouble() ?: 0.0 },
            allowTieExpansion = false,
            now = now,
        )
    }

    private fun awardTopPercentageBadge(
        badgeCollection: String,
        roundId: Int,
        apps: List<ScoredApp>,
        badgeType: BadgeType,
        valueSelector: (ScoredApp) -> Double,
        allowTieExpansion: Boolean,
        now: Long,
    ) {
        if (apps.isEmpty()) return

        // Sort descending by value, then by appId for stable ordering
        val ranked =
            apps.sortedWith(
                compareByDescending<ScoredApp> { valueSelector(it) }.thenBy { it.appId }
            )

        // Top 10%, rounded up (ceil). Minimum 1.
        val topCount = maxOf(1, ceil(ranked.size * 0.1).toInt())

        // Assign ranks with tie handling
        val rankedApps = mutableListOf<Pair<ScoredApp, Int>>()
        var currentRank = 1
        for (i in ranked.indices) {
            if (i > 0) {
                val prevValue = valueSelector(ranked[i - 1])
                val currValue = valueSelector(ranked[i])
                if (currValue != prevValue) {
                    currentRank = i + 1
                }
            }
            // Only include apps within the top 10%
            if (allowTieExpansion) {
                // Include if rank <= topCount, or if tied with the last included app
                if (currentRank <= topCount) {
                    rankedApps.add(ranked[i] to currentRank)
                } else {
                    // Check if tied with the last included app
                    val lastIncludedValue = valueSelector(rankedApps.last().first)
                    if (valueSelector(ranked[i]) == lastIncludedValue) {
                        rankedApps.add(ranked[i] to currentRank)
                    }
                }
            } else {
                if (currentRank <= topCount) {
                    rankedApps.add(ranked[i] to currentRank)
                }
            }
        }

        if (rankedApps.isEmpty()) return

        // Bulk upsert badge awards
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, badgeCollection)
        for ((app, rank) in rankedApps) {
            val id = "${app.appId}_${badgeType.value}_$roundId"
            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .set("_id", id)
                    .set("version", 1)
                    .set("blockId", "")
                    .set("blockNumber", 0L)
                    .set("blockTimestamp", now)
                    .set("appId", app.appId)
                    .set("badgeType", badgeType.value)
                    .set("round", roundId.toLong())
                    .set("rank", rank)
                    .set("isPublished", true),
            )
        }
        bulkOps.execute()

        logger.info(
            "Round {}: awarded {} {} badges (top {}% of {} apps)",
            roundId,
            rankedApps.size,
            badgeType.name,
            topCount,
            apps.size,
        )
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private fun logSummary(sorted: List<ScoredApp>, roundId: Int, topN: Int) {
        val top = sorted.filter { it.allocation > 0 }
        logger.info("===== Cycle Summary =====")
        logger.info("Round: {} | Top-N: {}", roundId, topN)
        logger.info("Rank | App | Score | Allocation | Confidence")
        top.forEachIndexed { i, app ->
            logger.info(
                "  {} | {} | {} | {}% | {}",
                i + 1,
                app.name,
                app.total,
                app.allocation,
                app.confidence,
            )
        }
    }
}
