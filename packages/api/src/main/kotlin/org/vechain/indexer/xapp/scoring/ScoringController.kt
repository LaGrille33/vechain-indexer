package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.vechain.indexer.constants.XAPP_PATH
import org.vechain.indexer.docs.CommonApiResponses

@Profile("b3tr", "xapp-scoring")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Scoring", description = "Query XApp scoring data.")
open class ScoringController(private val service: ScoringApiService) {

    @GetMapping("/apps/{appId}/scores/previousRound")
    @Operation(summary = "Get app score, rank, round date for the previous round.")
    @CommonApiResponses
    open fun getAppPreviousScore(
        @Parameter(description = "App identifier", required = true) @PathVariable appId: String
    ): AppPreviousScoreResponse = service.getAppPreviousScore(appId)
}
