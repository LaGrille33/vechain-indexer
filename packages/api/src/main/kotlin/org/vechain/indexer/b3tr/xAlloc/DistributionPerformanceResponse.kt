package org.vechain.indexer.b3tr.xAlloc

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "Distribution performance metrics for a single round.")
data class DistributionPerformanceItem(
    @field:Schema(description = "The round/cycle ID.", example = "477") val roundId: Int,
    @field:Schema(
        description = "Block timestamp of the round (Unix epoch seconds).",
        example = "1772615600",
    )
    val roundTime: Long,
    @field:Schema(
        description = "B3TR allocated to the app for rewards in this round.",
        example = "70331.81455991528",
    )
    val allocationEarnings: BigDecimal,
    @field:Schema(
        description = "B3TR actually distributed by the app in this round.",
        example = "25.0",
    )
    val b3trDistributed: BigDecimal,
    @field:Schema(
        description = "Percentage of allocated rewards actually distributed (0-100).",
        example = "0.0355",
    )
    val distributionPerformance: BigDecimal,
)
