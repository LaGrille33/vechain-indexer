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
    }

    @Scheduled(
        fixedDelayString = "\${indexer.navigator-vote-report.interval-ms:3600000}",
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
            val report = buildRoundReport(roundId, cycle.blockNumber, cycle.blockTimestamp)
            mongoTemplate.save(report, IndexerNames.NAVIGATOR_VOTE_ROUND_REPORT.COLLECTION)
            logger.info(
                "Navigator vote report aggregated for round {}: navigators={}, delegatedVotes={}, totalVotes={}",
                roundId,
                report.navigatorCount,
                report.delegatedVotes,
                report.totalVotes,
            )
        }

        val now = System.currentTimeMillis()
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update.update("lastRoundId", latestCycle.roundId).set("updatedAt", now),
            WATERMARK_COLLECTION,
        )
    }

    private fun buildRoundReport(
        roundId: Int,
        roundBlockNumber: Long,
        roundBlockTimestamp: Long,
    ): NavigatorVoteRoundReport {
        val reportId = NavigatorVoteRoundReport.idForRound(roundId)
        val activeCitizens = navigatorCitizenRepository.findAll().filter { it.active }
        val citizenToNavigator =
            activeCitizens.associate { it.address.lowercase() to it.navigator.lowercase() }

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
            val navigator = citizenToNavigator[voter]
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

        return NavigatorVoteRoundReport(
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
    }
}
