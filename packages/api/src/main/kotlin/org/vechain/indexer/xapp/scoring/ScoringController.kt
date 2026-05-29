package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.vechain.indexer.constants.XAPP_PATH
import org.vechain.indexer.docs.CommonApiResponses
import org.vechain.indexer.docs.PaginationParameters
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.utils.PaginationUtils.toPageable
import org.vechain.indexer.validation.ValidPageSize

@Profile("b3tr", "xapp-scoring")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Scoring", description = "Query indexed XApp scoring rounds.")
open class ScoringController(private val service: ScoringApiService) {

    @GetMapping("/scoring/rounds")
    @Operation(summary = "Get scoring rounds.")
    @CommonApiResponses
    @PaginationParameters
    open fun getRounds(
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
    ): PaginatedResponse<ScoringRound> =
        service.getRounds(pageable = toPageable(page, size, direction, "round"))

    @GetMapping("/scoring/rounds/{round}")
    @Operation(summary = "Get a single scoring round by round number.")
    @Parameter(name = "round", description = "Round number", `in` = ParameterIn.PATH, example = "1")
    @CommonApiResponses
    open fun getRound(@PathVariable round: Long): ScoringRound = service.getRound(round)
}
