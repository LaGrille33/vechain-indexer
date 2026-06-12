package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.vechain.indexer.constants.XAPP_PATH
import org.vechain.indexer.docs.AppIdParameter
import org.vechain.indexer.docs.CommonApiResponses

@Profile("b3tr")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(
    name = "XApp - Community Score",
    description = "Community Score for apps based on ratings and review votes.",
)
open class CommunityScoreController(private val service: CommunityScoreService) {

    @GetMapping("/apps/{appId}/community-score")
    @Operation(
        summary = "Get the Community Score for a specific app.",
        description = "Returns the Community Score (percentile rank 0-100) for an app.",
    )
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @CommonApiResponses
    open fun getCommunityScore(@PathVariable appId: String): CommunityScoreResult? =
        service.computeCommunityScore(appId)

    @GetMapping("/community-scores")
    @Operation(
        summary = "Get Community Scores for all apps.",
        description =
            "Returns Community Scores for all apps that have rating data, sorted by score descending.",
    )
    @CommonApiResponses
    open fun getCommunityScores(): List<CommunityScoreResult> = service.computeCommunityScores()
}
