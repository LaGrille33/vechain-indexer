package org.vechain.indexer.xapp.scoring

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ActivityDistributionCalculatorTest {
    private fun input(
        id: Int,
        users: Long = 0,
        new: Long? = 0,
        actions: Long = 0,
        allocated: String = "0",
        distributed: String = "0",
    ) =
        ActivityDistributionCalculator.Input(
            "0x" + id.toString(16).padStart(64, '0'),
            users,
            new,
            actions,
            BigDecimal(allocated),
            BigDecimal(distributed),
        )

    @Test
    fun `preserves existing all-zero ties rather than silently fixing the formula`() {
        val results = ActivityDistributionCalculator.calculate(listOf(input(1), input(2)))
        results.forEach {
            assertEquals(BigDecimal("50.0000"), it.activityScore)
            assertEquals(BigDecimal("50.0000"), it.distributionScore)
            assertEquals(BigDecimal("30.00"), it.subtotal)
            assertEquals(1, it.subtotalRank)
        }
    }

    @Test
    fun `unique values preserve current percentile denominator and weighted rounding`() {
        val results =
            ActivityDistributionCalculator.calculate(
                listOf(
                    input(1, 0, 0, 0, "10", "0"),
                    input(2, 10, 5, 15, "10", "5"),
                    input(3, 20, 10, 30, "10", "10"),
                )
            )
        assertEquals(
            listOf(BigDecimal("40.00"), BigDecimal("20.00"), BigDecimal("0.00")),
            results.map { it.subtotal },
        )
        assertEquals(listOf(1, 2, 3), results.map { it.subtotalRank })
        assertEquals(BigDecimal("66.6667"), results.first().activityScore)
    }

    @Test
    fun `ties use competition ranking with gaps`() {
        val results =
            ActivityDistributionCalculator.calculate(
                listOf(input(1, 20, 10, 30, "10", "10"), input(2, 20, 10, 30, "10", "10"), input(3))
            )
        assertEquals(listOf(1, 1, 3), results.map { it.subtotalRank })
    }

    @Test
    fun `missing history prevents a misleading activity score`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDistributionCalculator.calculate(listOf(input(1, new = null)))
        }
    }

    @Test
    fun `zero allocation and capped ratios match current efficiency behavior`() {
        val results =
            ActivityDistributionCalculator.calculate(
                    listOf(
                        input(1, allocated = "0", distributed = "10"),
                        input(2, allocated = "10", distributed = "20"),
                        input(
                            3,
                            allocated = "1000000000000000000000000000000.000000000000000001",
                            distributed = "500000000000000000000000000000",
                        ),
                    )
                )
                .associateBy { it.appId }
        assertEquals(
            BigDecimal("0.000000"),
            results.getValue(input(1).appId).distributionEfficiency,
        )
        assertEquals(
            BigDecimal("1.000000"),
            results.getValue(input(2).appId).distributionEfficiency,
        )
        assertEquals(
            BigDecimal("0.500000"),
            results.getValue(input(3).appId).distributionEfficiency,
        )
    }

    @Test
    fun `duplicate population members and impossible user counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDistributionCalculator.calculate(listOf(input(1), input(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDistributionCalculator.calculate(listOf(input(1, users = 1, new = 2)))
        }
    }
}
