package org.vechain.indexer.b3tr.xAlloc

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "Reward details summary for an app.")
data class RewardDetailsResponse(
    @field:Schema(
        description = "Total B3TR allocated to the app across all rounds.",
        example = "70331.81",
    )
    val totalAllocationEarnings: BigDecimal,
    @field:Schema(description = "Average B3TR allocated per round.", example = "5410.14")
    val averageAllocationPerRound: BigDecimal,
    @field:Schema(description = "Number of rounds the app participated in.", example = "13")
    val totalRounds: Int,
    @field:Schema(
        description =
            "Percentage of total allocation actually distributed (0-100), 2 decimal places.",
        example = "45.23",
    )
    val totalDistributionPerformance: BigDecimal,
    @field:Schema(description = "Total B3TR distributed by the app.", example = "31800.50")
    val totalDistributed: BigDecimal,
    @field:Schema(description = "Average B3TR distributed per round.", example = "2446.19")
    val averageDistributionPerRound: BigDecimal,
    @field:Schema(description = "Total number of rewarded actions.", example = "1250")
    val actionsRewarded: Long,
)
