package org.vechain.indexer.xapp.scoring

import java.math.BigDecimal
import java.math.RoundingMode
import org.vechain.indexer.b3tr.round.AppRoundScoreService

/** Calculation-only adapter for the pilot; callers supply the complete on-chain round app set. */
object ActivityDistributionCalculator {
    const val FORMULA_VERSION = "activity-distribution-mongo-parity-v1"

    data class Input(
        val appId: String,
        val activeUsers: Long,
        val newUsers: Long?,
        val actions: Long,
        val allocated: BigDecimal,
        val distributed: BigDecimal,
    )

    data class Result(
        val appId: String,
        val activeUsersPercentile: BigDecimal,
        val newUsersPercentile: BigDecimal,
        val actionsPercentile: BigDecimal,
        val activityScore: BigDecimal,
        val distributionEfficiency: BigDecimal,
        val distributionScore: BigDecimal,
        val activityContribution: BigDecimal,
        val distributionContribution: BigDecimal,
        /** Out of 60; deliberately not named totalScore, which needs Community and Profile. */
        val subtotal: BigDecimal,
        val activityRank: Int,
        val distributionRank: Int,
        val subtotalRank: Int,
    )

    fun calculate(inputs: List<Input>): List<Result> {
        require(inputs.isNotEmpty()) { "The complete round app population is required" }
        val normalized = inputs.map { it.copy(appId = it.appId.lowercase()) }
        require(normalized.map { it.appId }.distinct().size == normalized.size) {
            "Duplicate app IDs in the round population"
        }
        normalized.forEach {
            require(it.appId.matches(Regex("0x[0-9a-f]{64}"))) { "Invalid app ID" }
            require(it.activeUsers >= 0 && it.actions >= 0) { "Negative activity metric" }
            require(it.newUsers != null) { "New-user history is incomplete for ${it.appId}" }
            require(it.newUsers >= 0 && it.newUsers <= it.activeUsers) {
                "New users must be between zero and active users"
            }
            require(it.allocated.signum() >= 0 && it.distributed.signum() >= 0) {
                "Negative reward amount"
            }
        }
        fun logs(metric: (Input) -> Long) =
            normalized.map { AppRoundScoreService.ln1pBig(BigDecimal(metric(it))) }

        val users = logs { it.activeUsers }
        val newUsers = logs { requireNotNull(it.newUsers) }
        val actions = logs { it.actions }
        val efficiencies =
            normalized.map {
                if (it.allocated.signum() == 0) BigDecimal.ZERO
                else
                    it.distributed
                        .divide(it.allocated, 10, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE)
            }
        val pending =
            normalized.mapIndexed { i, input ->
                val pUsers = AppRoundScoreService.percentileRank(users[i], users)
                val pNew = AppRoundScoreService.percentileRank(newUsers[i], newUsers)
                val pActions = AppRoundScoreService.percentileRank(actions[i], actions)
                val activity =
                    pUsers
                        .multiply(BigDecimal("0.5"))
                        .add(pNew.multiply(BigDecimal("0.3")))
                        .add(pActions.multiply(BigDecimal("0.2")))
                        .round(4)
                val distribution =
                    AppRoundScoreService.percentileRank(efficiencies[i], efficiencies).round(4)
                val activityContribution = activity.multiply(BigDecimal("0.3")).round(2)
                val distributionContribution = distribution.multiply(BigDecimal("0.3")).round(2)
                Result(
                    input.appId,
                    pUsers.round(4),
                    pNew.round(4),
                    pActions.round(4),
                    activity,
                    efficiencies[i].round(6),
                    distribution,
                    activityContribution,
                    distributionContribution,
                    activityContribution.add(distributionContribution),
                    0,
                    0,
                    0,
                )
            }
        val activityRanks = ranks(pending) { it.activityScore }
        val distributionRanks = ranks(pending) { it.distributionScore }
        val subtotalRanks = ranks(pending) { it.subtotal }
        return pending
            .map {
                it.copy(
                    activityRank = activityRanks.getValue(it.appId),
                    distributionRank = distributionRanks.getValue(it.appId),
                    subtotalRank = subtotalRanks.getValue(it.appId),
                )
            }
            .sortedWith(compareBy<Result> { it.subtotalRank }.thenBy { it.appId })
    }

    private fun ranks(results: List<Result>, value: (Result) -> BigDecimal): Map<String, Int> {
        val sorted = results.sortedWith(compareByDescending(value).thenBy { it.appId })
        var rank = 1
        return sorted
            .mapIndexed { index, result ->
                if (index > 0 && value(result).compareTo(value(sorted[index - 1])) != 0)
                    rank = index + 1
                result.appId to rank
            }
            .toMap()
    }

    private fun BigDecimal.round(scale: Int) = setScale(scale, RoundingMode.HALF_UP)
}
