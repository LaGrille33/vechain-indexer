package org.vechain.indexer.b3tr.xAlloc

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
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
        if (allEarnings.isEmpty()) return emptyList()

        val sortedRoundIds = allEarnings.map { it.roundId }.distinct().sorted()
        val limit = RANGE_LIMITS[normalizedRange]
        val roundIds = if (limit != null) sortedRoundIds.takeLast(limit) else sortedRoundIds

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

            val allocationEarnings =
                (earning?.rewardsAllocationAmount ?: java.math.BigDecimal.ZERO).clean()
            val b3trDistributed = (action?.totalRewardAmount ?: java.math.BigDecimal.ZERO).clean()
            val performance =
                if (allocationEarnings > java.math.BigDecimal.ZERO) {
                    b3trDistributed
                        .divide(allocationEarnings, 10, java.math.RoundingMode.HALF_UP)
                        .multiply(java.math.BigDecimal(100))
                        .setScale(4, java.math.RoundingMode.HALF_UP)
                } else {
                    java.math.BigDecimal.ZERO
                }

            DistributionPerformanceItem(
                roundId = roundId,
                roundTime = cycleInfo?.blockTimestamp ?: 0L,
                allocationEarnings = allocationEarnings,
                b3trDistributed = b3trDistributed,
                distributionPerformance = performance,
            )
        }
    }
}
