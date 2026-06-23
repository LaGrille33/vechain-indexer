package org.vechain.indexer.b3tr.xAlloc

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.vechain.indexer.b3tr.action.repository.UserAllTimeActionSummaryRepository
import org.vechain.indexer.b3tr.action.repository.UserRoundActionSummaryRepository
import org.vechain.indexer.b3tr.round.CycleInfo
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.b3tr.shared.EntityType
import org.vechain.indexer.b3tr.xAlloc.repository.XAllocResultRepository

@ExtendWith(MockKExtension::class)
internal class XAllocServiceTest {
    @MockK lateinit var xAllocResultRepository: XAllocResultRepository
    @MockK lateinit var userRoundRepo: UserRoundActionSummaryRepository
    @MockK(relaxed = true) lateinit var userAllTimeRepo: UserAllTimeActionSummaryRepository
    @MockK(relaxed = true) lateinit var cycleInfoRepo: CycleInfoRepository

    private lateinit var service: XAllocService

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        service =
            XAllocService(xAllocResultRepository, userRoundRepo, userAllTimeRepo, cycleInfoRepo)
    }

    @Test
    fun `getDistributionPerformance returns zero-valued items for the range when app has no earnings`() {
        val appId = "0xapp"
        every { xAllocResultRepository.findByAppId(appId) } returns emptyList()
        every { cycleInfoRepo.findTopByOrderByRoundIdDesc() } returns
            CycleInfo(
                roundId = 500,
                blockNumber = 1,
                blockTimestamp = 1_700_000_000,
                eventType = "cycle",
                updatedAt = 1,
            )
        every {
            userRoundRepo.findAllByEntityAndEntityTypeAndRoundIdIn(appId, EntityType.APP, any())
        } returns emptyList()

        val result = service.getDistributionPerformance(appId, "6M")

        // 6M = 26 rounds ending at the previous round (500 - 1 = 499): 474..499, ascending.
        assertEquals(26, result.size)
        assertEquals((474..499).toList(), result.map { it.roundId })
        result.forEach { item ->
            assertEquals(0L, item.roundTime)
            assertEquals(0, item.totalAllocationAmount.compareTo(BigDecimal.ZERO))
            assertEquals(0, item.rewardsAllocationAmount.compareTo(BigDecimal.ZERO))
            assertEquals(0, item.b3trDistributed.compareTo(BigDecimal.ZERO))
            assertEquals(0, item.distributionPerformance.compareTo(BigDecimal.ZERO))
        }
    }

    @Test
    fun `getDistributionPerformance returns zero-valued items clamped to round 1 when range exceeds history`() {
        val appId = "0xapp"
        every { xAllocResultRepository.findByAppId(appId) } returns emptyList()
        every { cycleInfoRepo.findTopByOrderByRoundIdDesc() } returns
            CycleInfo(
                roundId = 5,
                blockNumber = 1,
                blockTimestamp = 1,
                eventType = "cycle",
                updatedAt = 1,
            )
        every {
            userRoundRepo.findAllByEntityAndEntityTypeAndRoundIdIn(appId, EntityType.APP, any())
        } returns emptyList()

        // 1Y asks for 52 rounds, but only rounds 1..4 exist (previous round = 5 - 1 = 4).
        val result = service.getDistributionPerformance(appId, "1Y")

        assertEquals((1..4).toList(), result.map { it.roundId })
    }

    @Test
    fun `getDistributionPerformance fills zero-valued rounds within the range when app has partial earnings`() {
        val appId = "0xapp"
        every { xAllocResultRepository.findByAppId(appId) } returns
            listOf(
                earning(appId, roundId = 488, total = "100"),
                earning(appId, roundId = 490, total = "200"),
                earning(appId, roundId = 495, total = "300"),
            )
        every { cycleInfoRepo.findTopByOrderByRoundIdDesc() } returns
            CycleInfo(
                roundId = 500,
                blockNumber = 1,
                blockTimestamp = 1_700_000_000,
                eventType = "cycle",
                updatedAt = 1,
            )
        every {
            userRoundRepo.findAllByEntityAndEntityTypeAndRoundIdIn(appId, EntityType.APP, any())
        } returns emptyList()

        val result = service.getDistributionPerformance(appId, "6M")

        // Full 26-round window from the global cycle (previousRound = 499): 474..499, ascending.
        assertEquals((474..499).toList(), result.map { it.roundId })
        val byRound = result.associateBy { it.roundId }
        // Rounds the app earned in carry their allocation amounts.
        assertEquals(0, byRound.getValue(488).totalAllocationAmount.compareTo(BigDecimal("100")))
        assertEquals(0, byRound.getValue(490).totalAllocationAmount.compareTo(BigDecimal("200")))
        assertEquals(0, byRound.getValue(495).totalAllocationAmount.compareTo(BigDecimal("300")))
        // Rounds with no earnings are filled with zeros.
        assertEquals(0, byRound.getValue(474).totalAllocationAmount.compareTo(BigDecimal.ZERO))
        assertEquals(0, byRound.getValue(499).b3trDistributed.compareTo(BigDecimal.ZERO))
    }

    private fun earning(appId: String, roundId: Int, total: String) =
        XAllocResult(
            version = 1,
            blockId = "block-$roundId",
            blockNumber = 1,
            blockTimestamp = 1_700_000_000,
            roundId = roundId,
            appId = appId,
            voters = 0,
            votesReceived = BigInteger.ZERO,
            totalAmount = BigDecimal(total),
            rewardsAllocationAmount = BigDecimal(total),
        )
}
