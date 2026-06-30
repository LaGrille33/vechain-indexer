package org.vechain.indexer.b3tr.navigator

import java.math.BigDecimal
import java.math.BigInteger
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.xapp.badge.BadgeType

@Service
@Profile("b3tr", "b3tr-navigator", "b3tr-navigator-vote-report")
class NavigatorVoteRoundReportAggregationService(
    private val mongoTemplate: MongoTemplate,
    private val cycleInfoRepository: CycleInfoRepository,
    private val navigatorCitizenRepository: NavigatorCitizenRepository,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val WATERMARK_ID = "navigator_vote_round_report_watermark"
        private const val WATERMARK_COLLECTION = "b3tr_navigator_vote_round_report_watermark"
        private const val HISTORY_EVENT_NAME = "B3TR_XALLOCATION_VOTE"

        // Navigators Pick badge: number of top dApps (by navigator voting weight) that earn it.
        private const val NAVIGATORS_PICK_TOP_N = 3
    }

    private data class RoundAggregationResult(
        val report: NavigatorVoteRoundReport,
        val dappRanking: NavigatorDappVoteRoundRanking,
    )

    @Scheduled(
        fixedDelayString = "\${indexer.navigator-vote-report.interval-ms:60000}",
        initialDelayString = "\${indexer.navigator-vote-report.initial-delay-ms:30000}",
    )
    fun aggregateNavigatorVoteReports() {
        try {
            aggregateInternal()
        } catch (e: Exception) {
            logger.error("Navigator vote round report aggregation failed", e)
        }
    }

    private fun aggregateInternal() {
        val latestCycle = cycleInfoRepository.findTopByOrderByRoundIdDesc()
        if (latestCycle == null) {
            logger.info("No cycle info found, skip navigator vote report aggregation")
            return
        }

        val watermarkDoc =
            mongoTemplate.findOne(
                Query(Criteria.where("_id").`is`(WATERMARK_ID)),
                Document::class.java,
                WATERMARK_COLLECTION,
            )
        val lastProcessedRound = watermarkDoc?.getInteger("lastRoundId") ?: 0

        if (latestCycle.roundId <= lastProcessedRound) {
            logger.info(
                "No new completed rounds to aggregate (latest={}, processed={})",
                latestCycle.roundId,
                lastProcessedRound,
            )
            return
        }

        val allCycles =
            cycleInfoRepository
                .findByRoundIdIn((lastProcessedRound + 1..latestCycle.roundId).toList())
                .sortedBy { it.roundId }
        if (allCycles.isEmpty()) return

        allCycles.forEach { cycle ->
            val roundId = cycle.roundId
            val result = buildRoundAggregation(roundId, cycle.blockNumber, cycle.blockTimestamp)
            mongoTemplate.save(result.report, IndexerNames.NAVIGATOR_VOTE_ROUND_REPORT.COLLECTION)
            mongoTemplate.save(
                result.dappRanking,
                IndexerNames.NAVIGATOR_DAPP_VOTE_ROUND_RANKING.COLLECTION,
            )
            // Award the navigatorsPick badge inline, right after the ranking is persisted. This is
            // the single owner of that badge: keeping it here (rather than in the per-round scoring
            // pipeline) guarantees the ranking exists when the badge is awarded, eliminating the
            // race where awardBadges(round) ran before the ranking was built and silently skipped
            // the badge with no retry.
            awardNavigatorsPickBadges(result.dappRanking, cycle.blockTimestamp)
            logger.info(
                "Navigator vote report aggregated for round {}: navigators={}, delegatedVotes={}, totalVotes={}, dapps={}",
                roundId,
                result.report.navigatorCount,
                result.report.delegatedVotes,
                result.report.totalVotes,
                result.dappRanking.dappCount,
            )
        }

        val now = System.currentTimeMillis()
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update.update("lastRoundId", latestCycle.roundId).set("updatedAt", now),
            WATERMARK_COLLECTION,
        )
    }

    private fun buildRoundAggregation(
        roundId: Int,
        roundBlockNumber: Long,
        roundBlockTimestamp: Long,
    ): RoundAggregationResult {
        val reportId = NavigatorVoteRoundReport.idForRound(roundId)
        val activeCitizens = navigatorCitizenRepository.findAll().filter { it.active }
        val citizenToNavigator =
            activeCitizens.associate { it.address.lowercase() to it.navigator.lowercase() }
        val activeNavigatorAddresses =
            mongoTemplate
                .find(
                    Query(
                        Criteria.where("status")
                            .`in`(NavigatorStatus.ACTIVE.name, NavigatorStatus.EXITING.name)
                    ),
                    Document::class.java,
                    IndexerNames.NAVIGATOR.COLLECTION,
                )
                .mapNotNull { doc ->
                    (doc.getString("address") ?: doc.getString("_id") ?: doc.get("_id")?.toString())
                        ?.lowercase()
                }
                .toSet()

        val voteQuery =
            Query(
                    Criteria.where("eventName")
                        .`is`(HISTORY_EVENT_NAME)
                        .and("roundId")
                        .`is`(roundId.toString())
                )
                .with(Sort.by(Sort.Direction.ASC, "blockNumber"))

        val votes =
            mongoTemplate.find(voteQuery, Document::class.java, IndexerNames.HISTORY.COLLECTION)

        val voteEventsByNavigator = mutableMapOf<String, Long>()
        val citizenSetByNavigator = mutableMapOf<String, MutableSet<String>>()
        val weightByNavigator = mutableMapOf<String, BigInteger>()
        val appWeightByNavigator = mutableMapOf<String, MutableMap<String, BigInteger>>()

        var delegatedVotes = 0L
        var manualVotes = 0L
        var totalWeight = BigInteger.ZERO

        votes.forEach { ev ->
            val voter = ((ev["from"] as? String) ?: (ev["origin"] as? String) ?: "").lowercase()
            val isActiveNavigatorVoter =
                activeNavigatorAddresses.contains(voter) ||
                    mongoTemplate.exists(
                        Query(
                            Criteria.where("_id")
                                .`is`(voter)
                                .and("status")
                                .`in`(NavigatorStatus.ACTIVE.name, NavigatorStatus.EXITING.name)
                        ),
                        IndexerNames.NAVIGATOR.COLLECTION,
                    )
            val navigator = citizenToNavigator[voter] ?: if (isActiveNavigatorVoter) voter else null
            if (navigator.isNullOrBlank()) {
                manualVotes++
                return@forEach
            }
            delegatedVotes++
            voteEventsByNavigator[navigator] = (voteEventsByNavigator[navigator] ?: 0L) + 1
            citizenSetByNavigator.computeIfAbsent(navigator) { mutableSetOf() }.add(voter)

            @Suppress("UNCHECKED_CAST")
            val appVotes = ev["appVotes"] as? List<Document> ?: emptyList()
            appVotes.forEach { appVote ->
                val appId = (appVote["appId"] as? String)?.lowercase() ?: return@forEach
                val weight =
                    (appVote["voteWeight"] as? String)?.toBigIntegerOrNull() ?: BigInteger.ZERO
                weightByNavigator[navigator] =
                    (weightByNavigator[navigator] ?: BigInteger.ZERO) + weight
                appWeightByNavigator.computeIfAbsent(navigator) { mutableMapOf() }
                appWeightByNavigator[navigator]!![appId] =
                    (appWeightByNavigator[navigator]!![appId] ?: BigInteger.ZERO) + weight
                totalWeight += weight
            }
        }

        val navigatorSummaries =
            voteEventsByNavigator.keys.sorted().map { navigator ->
                val topApps =
                    (appWeightByNavigator[navigator] ?: emptyMap())
                        .entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .map { NavigatorAppWeight(it.key, it.value) }

                NavigatorVoteSummary(
                    navigator = navigator,
                    voteEvents = voteEventsByNavigator[navigator] ?: 0L,
                    citizenCount = citizenSetByNavigator[navigator]?.size?.toLong() ?: 0L,
                    weightTotal = BigDecimal(weightByNavigator[navigator] ?: BigInteger.ZERO),
                    topApps = topApps,
                )
            }

        val navigatorCount =
            mongoTemplate.count(
                Query(
                    Criteria.where("status")
                        .`in`(NavigatorStatus.ACTIVE.name, NavigatorStatus.EXITING.name)
                ),
                IndexerNames.NAVIGATOR.COLLECTION,
            )

        val report =
            NavigatorVoteRoundReport(
                id = reportId,
                blockId = "",
                blockNumber = roundBlockNumber,
                blockTimestamp = roundBlockTimestamp,
                roundId = roundId,
                reportStatus = "COMPLETED",
                reportGeneratedAt = System.currentTimeMillis(),
                totalVotes = votes.size.toLong(),
                delegatedVotes = delegatedVotes,
                manualVotes = manualVotes,
                autoVotes = 0L,
                navigatorCount = navigatorCount,
                roundDeadline = null,
                roundSnapshot = null,
                totalDelegatedWeight = BigDecimal(totalWeight),
                navigatorSummaries = navigatorSummaries,
            )

        val dappRanking =
            buildDappRanking(roundId, roundBlockNumber, roundBlockTimestamp, appWeightByNavigator)
        return RoundAggregationResult(report = report, dappRanking = dappRanking)
    }

    private fun buildDappRanking(
        roundId: Int,
        roundBlockNumber: Long,
        roundBlockTimestamp: Long,
        appWeightByNavigator: Map<String, Map<String, BigInteger>>,
    ): NavigatorDappVoteRoundRanking {
        val totalWeightByApp = mutableMapOf<String, BigInteger>()
        val navigatorSetByApp = mutableMapOf<String, MutableSet<String>>()

        appWeightByNavigator.forEach { (navigator, appWeights) ->
            appWeights.forEach { (appId, weight) ->
                totalWeightByApp[appId] = (totalWeightByApp[appId] ?: BigInteger.ZERO) + weight
                navigatorSetByApp.computeIfAbsent(appId) { mutableSetOf() }.add(navigator)
            }
        }

        val rankedApps =
            totalWeightByApp.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, BigInteger>> { it.value }
                        .thenBy { it.key }
                )
                .mapIndexed { idx, entry ->
                    NavigatorDappVoteRankingEntry(
                        rank = idx + 1,
                        appId = entry.key,
                        weight = entry.value,
                        contributingNavigators = navigatorSetByApp[entry.key]?.size?.toLong() ?: 0L,
                    )
                }

        val totalWeight = totalWeightByApp.values.fold(BigInteger.ZERO, BigInteger::add)
        return NavigatorDappVoteRoundRanking(
            id = NavigatorDappVoteRoundRanking.idForRound(roundId),
            blockId = "",
            blockNumber = roundBlockNumber,
            blockTimestamp = roundBlockTimestamp,
            roundId = roundId,
            reportGeneratedAt = System.currentTimeMillis(),
            dappCount = rankedApps.size.toLong(),
            totalWeight = BigDecimal(totalWeight),
            dappRankings = rankedApps,
        )
    }

    /**
     * Awards the [BadgeType.NAVIGATORS_PICK] badge to the top [NAVIGATORS_PICK_TOP_N] dApps of
     * [ranking] (whose `dappRankings` are already 1-based and sorted by weight descending). Each
     * winner is upserted into [xapp_badge_awards][IndexerNames.XAPP_BADGE_AWARD] keyed by
     * `appId_navigatorsPick_round`, so re-running for a round is idempotent. The `appId` is
     * lower-cased to match the `_id` convention of the other badge types. No-op when the ranking
     * has no entries in the top N (e.g. no navigator votes for the round).
     */
    private fun awardNavigatorsPickBadges(
        ranking: NavigatorDappVoteRoundRanking,
        roundBlockTimestamp: Long,
    ) {
        val now = System.currentTimeMillis()
        ranking.dappRankings
            .filter { it.rank in 1..NAVIGATORS_PICK_TOP_N }
            .forEach { entry ->
                val appId = entry.appId.lowercase()
                val id = "${appId}_${BadgeType.NAVIGATORS_PICK.value}_${ranking.roundId}"
                mongoTemplate.upsert(
                    Query(Criteria.where("_id").`is`(id)),
                    Update()
                        .set("_id", id)
                        .set("version", 1)
                        .set("blockId", "")
                        .set("blockNumber", 0L)
                        .set("blockTimestamp", now)
                        .set("appId", appId)
                        .set("badgeType", BadgeType.NAVIGATORS_PICK.value)
                        .set("round", ranking.roundId.toLong())
                        .set("rank", entry.rank)
                        .set("isPublished", true)
                        .set("roundBlockTimestamp", roundBlockTimestamp),
                    IndexerNames.XAPP_BADGE_AWARD.COLLECTION,
                )
            }
    }
}
