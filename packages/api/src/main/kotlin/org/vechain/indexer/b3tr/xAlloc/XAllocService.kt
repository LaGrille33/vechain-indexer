package org.vechain.indexer.b3tr.xAlloc

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.b3tr.action.repository.UserAllTimeActionSummaryRepository
import org.vechain.indexer.b3tr.action.repository.UserRoundActionSummaryRepository
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.b3tr.shared.EntityType
import org.vechain.indexer.b3tr.xAlloc.repository.XAllocResultRepository
import org.vechain.indexer.exception.BadRequestException

private val RANGE_LIMITS = mapOf("3M" to 13, "6M" to 26, "1Y" to 52, "ALL" to null)

private fun java.math.BigDecimal.clean(): java.math.BigDecimal {
    val stripped = this.stripTrailingZeros()
    return if (stripped.scale() < 0) stripped.setScale(0) else stripped
}

@Profile("b3tr", "b3tr-x-alloc")
@Service
open class XAllocService(
    private val xAllocResultRepository: XAllocResultRepository,
    private val userRoundRepo: UserRoundActionSummaryRepository,
    private val userAllTimeRepo: UserAllTimeActionSummaryRepository,
    private val cycleInfoRepo: CycleInfoRepository,
) : IndexerService {

    /**
     * Get XAllocation results for a specific app and round.
     *
     * @param appId App ID to filter by.
     * @param roundId Round ID to filter by.
     */
    open fun getXAllocResultByAppIdAndRoundId(appId: String, roundId: Int): XAllocResultResponse? =
        xAllocResultRepository.findByAppIdAndRoundId(appId, roundId)?.let {
            XAllocResultResponse.from(it)
        }

    /**
     * Get XAllocation results for a specific round, grouped by app. Returns voting data only,
     * sorted by votesReceived descending.
     *
     * @param roundId Round to filter by.
     */
    open fun getXAllocResultsByRoundId(roundId: Int): List<XAllocResultResponse> {
        return xAllocResultRepository
            .findByRoundId(roundId)
            .map { XAllocResultResponse.from(it) }
            .sortedByDescending { it.votesReceived }
    }

    /**
     * Get XAllocation earnings for a specific round and app.
     *
     * @param appId App ID to filter by.
     * @param roundId Round ID to filter by.
     */
    open fun getXAllocEarningsByAppIdAndRoundId(
        appId: String,
        roundId: Int,
    ): XAllocEarningsResponse? =
        xAllocResultRepository.findByAppIdAndRoundId(appId, roundId)?.let { result ->
            XAllocEarningsResponse.from(result)
        }

    /**
     * Get XAllocation earnings for a specific round, grouped by app. Returns earnings data only,
     * sorted by totalAmount descending.
     *
     * @param roundId Round to filter by.
     */
    open fun getXAllocEarningsByRoundId(roundId: Int): List<XAllocEarningsResponse> {
        return xAllocResultRepository
            .findByRoundId(roundId)
            .mapNotNull { XAllocEarningsResponse.from(it) }
            .sortedByDescending { it.totalAmount }
    }

    /**
     * Get XAllocation earnings for a specific app across all rounds. Returns earnings data for each
     * round as separate records, sorted by roundId ascending then totalAmount descending.
     *
     * @param appId App ID to filter by.
     */
    open fun getXAllocEarningsByAppId(appId: String): List<XAllocEarningsResponse> {
        return xAllocResultRepository
            .findByAppId(appId)
            .mapNotNull { XAllocEarningsResponse.from(it) }
            .sortedWith(
                compareBy<XAllocEarningsResponse> { it.roundId }.thenByDescending { it.totalAmount }
            )
    }

    override fun getLatestIndexedBlocks(): Map<String, Long> =
        mapOf("XAllocResult" to (xAllocResultRepository.getLatestRecord()?.blockNumber ?: 0))

    open fun getDistributionPerformance(
        appId: String,
        range: String?,
    ): List<DistributionPerformanceItem> {
        val normalizedRange = range?.uppercase()
        if (normalizedRange != null && normalizedRange !in RANGE_LIMITS) {
            throw BadRequestException("Invalid range. Must be one of: 3M, 6M, 1Y, All")
        }

        val allEarnings = xAllocResultRepository.findByAppId(appId)
        val roundIds = resolveRoundIds(normalizedRange)

        val earningsByRound =
            allEarnings.filter { it.roundId in roundIds }.associateBy { it.roundId }

        val actionSummaries =
            userRoundRepo
                .findAllByEntityAndEntityTypeAndRoundIdIn(appId, EntityType.APP, roundIds)
                .associateBy { it.roundId }

        val cycleInfoByRound = cycleInfoRepo.findByRoundIdIn(roundIds).associateBy { it.roundId }

        return roundIds.map { roundId ->
            val earning = earningsByRound[roundId]
            val action = actionSummaries[roundId]
            val cycleInfo = cycleInfoByRound[roundId]

            val totalAllocationAmount = (earning?.totalAmount ?: java.math.BigDecimal.ZERO).clean()
            val rewardsAllocationAmount =
                (earning?.rewardsAllocationAmount ?: java.math.BigDecimal.ZERO).clean()
            val b3trDistributed = (action?.totalRewardAmount ?: java.math.BigDecimal.ZERO).clean()
            val performance =
                if (totalAllocationAmount > java.math.BigDecimal.ZERO) {
                    b3trDistributed
                        .divide(totalAllocationAmount, 10, java.math.RoundingMode.HALF_UP)
                        .multiply(java.math.BigDecimal(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                } else {
                    java.math.BigDecimal.ZERO
                }

            DistributionPerformanceItem(
                roundId = roundId,
                roundTime = cycleInfo?.blockTimestamp ?: 0L,
                totalAllocationAmount = totalAllocationAmount,
                rewardsAllocationAmount = rewardsAllocationAmount,
                b3trDistributed = b3trDistributed,
                distributionPerformance = performance,
            )
        }
    }

    /**
     * Resolve the round window to surface for [normalizedRange], always derived from the latest
     * global cycle. Apps with no or partial earnings still receive a zero-valued item for every
     * round in the requested range — mirroring the roundUserStats endpoint.
     */
    private fun resolveRoundIds(normalizedRange: String?): List<Int> {
        val previousRound = (cycleInfoRepo.findTopByOrderByRoundIdDesc()?.roundId ?: 0) - 1
        if (previousRound < 1) return emptyList()

        val limit = RANGE_LIMITS[normalizedRange]
        val fromRound = if (limit != null) previousRound - (limit - 1) else 1
        val safeFromRound = maxOf(1, fromRound)
        return (safeFromRound..previousRound).toList().sorted()
    }

    open fun getRewardDetails(appId: String): RewardDetailsResponse {
        val allEarnings = xAllocResultRepository.findByAppId(appId)
        val totalRounds = allEarnings.map { it.roundId }.distinct().size

        val totalAllocationEarnings =
            allEarnings
                .fold(java.math.BigDecimal.ZERO) { acc, r ->
                    acc + (r.totalAmount ?: java.math.BigDecimal.ZERO)
                }
                .clean()

        val overview = userAllTimeRepo.findByEntity(appId)
        val totalDistributed = (overview?.totalRewardAmount ?: java.math.BigDecimal.ZERO).clean()
        val actionsRewarded = overview?.actionsRewarded ?: 0

        val averageAllocationPerRound =
            if (totalRounds > 0) {
                totalAllocationEarnings
                    .divide(java.math.BigDecimal(totalRounds), 10, java.math.RoundingMode.HALF_UP)
                    .clean()
            } else {
                java.math.BigDecimal.ZERO
            }

        val averageDistributionPerRound =
            if (totalRounds > 0) {
                totalDistributed
                    .divide(java.math.BigDecimal(totalRounds), 10, java.math.RoundingMode.HALF_UP)
                    .clean()
            } else {
                java.math.BigDecimal.ZERO
            }

        val totalDistributionPerformance =
            if (totalAllocationEarnings > java.math.BigDecimal.ZERO) {
                totalDistributed
                    .divide(totalAllocationEarnings, 10, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal(100))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
            } else {
                java.math.BigDecimal.ZERO
            }

        return RewardDetailsResponse(
            totalAllocationEarnings = totalAllocationEarnings,
            averageAllocationPerRound = averageAllocationPerRound,
            totalRounds = totalRounds,
            totalDistributionPerformance = totalDistributionPerformance,
            totalDistributed = totalDistributed,
            averageDistributionPerRound = averageDistributionPerRound,
            actionsRewarded = actionsRewarded,
        )
    }
}
