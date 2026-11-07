package org.vechain.indexer.xapp.scoring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Generates exact decimal reference results for the local Postgres/WASM parity tests. */
class ActivityDistributionParityFixtureTest {
    @Test
    fun `export current Kotlin reference results for SQL parity`() {
        fun input(i: Int, users: Long, new: Long, actions: Long, alloc: String, dist: String) =
            ActivityDistributionCalculator.Input(
                "0x" + i.toString(16).padStart(64, '0'),
                users,
                new,
                actions,
                BigDecimal(alloc),
                BigDecimal(dist),
            )
        val fixtures =
            linkedMapOf(
                "zero-ties" to (1..8).map { input(it, 0, 0, 0, "0", "0") },
                "singleton" to listOf(input(1, 9, 4, 13, "3", "2")),
                "unique-thirds" to
                    (0..2).map {
                        input(it + 1, it * 10L, it * 5L, it * 15L, "10", (it * 5).toString())
                    },
                "competition-ties" to
                    listOf(
                        input(1, 20, 10, 30, "10", "10"),
                        input(2, 20, 10, 30, "10", "10"),
                        input(3, 0, 0, 0, "0", "0"),
                    ),
                "efficiency-rounding" to
                    listOf(
                        input(1, 10, 1, 11, "1", "0.123456789041"),
                        input(2, 11, 1, 12, "1", "0.123456789049"),
                        input(3, 12, 2, 13, "1", "0.123456789051"),
                        input(4, 0, 0, 0, "0", "50"),
                        input(5, 2, 2, 3, "2", "10"),
                    ),
                "large-integers" to
                    listOf(
                        input(
                            1,
                            Long.MAX_VALUE,
                            1,
                            Long.MAX_VALUE,
                            "1000000000000000000000000000000.000000000000000001",
                            "500000000000000000000000000000",
                        ),
                        input(2, Long.MAX_VALUE - 1, 1, Long.MAX_VALUE - 1, "3", "1"),
                        input(3, 0, 0, 0, "3", "2"),
                    ),
                "mixed-37-apps" to
                    (1..37).map {
                        input(
                            it,
                            (it * 13 % 17).toLong(),
                            (it % 3).toLong().coerceAtMost((it * 13 % 17).toLong()),
                            (it * 29 % 53).toLong(),
                            (it % 9).toString(),
                            "${it % 13}.123456789012345678",
                        )
                    },
            )
        val mapper = jacksonObjectMapper()
        val payload =
            fixtures.map { (name, inputs) ->
                mapOf(
                    "name" to name,
                    "inputs" to
                        inputs.map {
                            mapOf(
                                "appId" to it.appId,
                                "activeUsers" to it.activeUsers.toString(),
                                "newUsers" to it.newUsers.toString(),
                                "actions" to it.actions.toString(),
                                "allocated" to it.allocated.toPlainString(),
                                "distributed" to it.distributed.toPlainString(),
                            )
                        },
                    "expected" to
                        ActivityDistributionCalculator.calculate(inputs).map {
                            mapOf(
                                "app_id" to it.appId,
                                "p_active_users" to it.activeUsersPercentile.toPlainString(),
                                "p_new_users" to it.newUsersPercentile.toPlainString(),
                                "p_actions" to it.actionsPercentile.toPlainString(),
                                "activity_score" to it.activityScore.toPlainString(),
                                "distribution_efficiency" to
                                    it.distributionEfficiency.toPlainString(),
                                "distribution_score" to it.distributionScore.toPlainString(),
                                "activity_contribution" to it.activityContribution.toPlainString(),
                                "distribution_contribution" to
                                    it.distributionContribution.toPlainString(),
                                "subtotal" to it.subtotal.toPlainString(),
                                "activity_rank" to it.activityRank.toString(),
                                "distribution_rank" to it.distributionRank.toString(),
                                "subtotal_rank" to it.subtotalRank.toString(),
                            )
                        },
                )
            }
        val target = Path.of("build/reports/scoring-pilot/kotlin-reference.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), payload)
        assertEquals(fixtures.size, mapper.readTree(target.toFile()).size())
    }
}
