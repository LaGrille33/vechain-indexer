package org.vechain.indexer.b3tr.xAlloc

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.vechain.indexer.constants.X_ALLOC_PATH
import org.vechain.indexer.docs.AppIdParameter
import org.vechain.indexer.docs.CommonApiResponses
import org.vechain.indexer.docs.RoundIdParameter

@Profile("b3tr", "b3tr-x-alloc")
@Tag(name = "B3TR - X-Allocations", description = "Query XAllocation voting data on VeBetterDAO.")
@Validated
@RestController
@RequestMapping(X_ALLOC_PATH)
open class XAllocController(private val xAllocService: XAllocService) {

    @GetMapping("{roundId}/results")
    @Operation(
        summary = "Get XAllocation voting results for a round",
        description = "Returns voting results for a specific round, optionally filtered by appId.",
    )
    @RoundIdParameter(`in` = ParameterIn.PATH, required = true)
    @AppIdParameter
    @CommonApiResponses
    open fun getXAllocResults(
        @PathVariable roundId: Int,
        @RequestParam(required = false) appId: String?,
    ): List<XAllocResultResponse> {
        return if (appId != null) {
            // Both provided: return single result as a list
            val result = xAllocService.getXAllocResultByAppIdAndRoundId(appId, roundId)
            if (result != null) listOf(result) else emptyList()
        } else {
            // Only roundId: return all apps in that round
            xAllocService.getXAllocResultsByRoundId(roundId)
        }
    }

    @GetMapping("earnings")
    @Operation(
        summary = "Get XAllocation earnings distribution",
        description =
            "Returns earnings distribution for a specific app and/or round. At least one parameter " +
                "(appId or roundId) must be provided. If both are provided, returns earnings for that " +
                "specific app and round. If only appId is provided, returns earnings for that app across " +
                "all rounds. If only roundId is provided, returns earnings for all apps in that round.",
    )
    @AppIdParameter
    @RoundIdParameter
    @CommonApiResponses
    open fun getXAllocEarnings(
        @RequestParam(required = false) appId: String?,
        @RequestParam(required = false) roundId: Int?,
    ): List<XAllocEarningsResponse> {
        // Validate that at least one parameter is provided
        if (appId == null && roundId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Either appId or roundId must be provided to limit the results",
            )
        }

        return when {
            appId != null && roundId != null -> {
                // Both provided: return single earnings as a list
                val earnings = xAllocService.getXAllocEarningsByAppIdAndRoundId(appId, roundId)
                if (earnings != null) listOf(earnings) else emptyList()
            }
            appId != null -> {
                // Only appId: return earnings for that app across all rounds
                xAllocService.getXAllocEarningsByAppId(appId)
            }
            else -> {
                // Only roundId: return earnings for all apps in that round
                xAllocService.getXAllocEarningsByRoundId(roundId!!)
            }
        }
    }

    @GetMapping("distribution-performance")
    @Operation(
        summary = "Get distribution performance for an app",
        description =
            "Returns distribution performance metrics for a specific app, showing how much of " +
                "the allocated rewards were actually distributed. Optionally filter by range " +
                "(3M=13 rounds, 6M=26 rounds, 1Y=52 rounds, All=no limit).",
    )
    @AppIdParameter(required = true)
    @CommonApiResponses
    open fun getDistributionPerformance(
        @RequestParam appId: String,
        @Parameter(
            description =
                "Time range filter. 3M=last 13 rounds, 6M=last 26 rounds, 1Y=last 52 rounds, All=no limit.",
            schema = Schema(allowableValues = ["3M", "6M", "1Y", "All"]),
        )
        @RequestParam(required = false)
        range: String?,
    ): List<DistributionPerformanceItem> {
        return xAllocService.getDistributionPerformance(appId, range)
    }
}
