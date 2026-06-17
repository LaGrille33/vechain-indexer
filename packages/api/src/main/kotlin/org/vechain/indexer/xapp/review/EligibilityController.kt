package org.vechain.indexer.xapp.review

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
import org.vechain.indexer.docs.AppIdParameter
import org.vechain.indexer.docs.CommonApiResponses

@Profile("b3tr")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Eligibility", description = "Reward-eligibility pre-check for rating/review.")
open class EligibilityController(private val service: EligibilityApiService) {

    @GetMapping("/apps/{appId}/eligibility")
    @Operation(
        summary = "Check whether a wallet may rate/review an app (received a reward from it).",
        description =
            "UX pre-check backed by the indexer's all-time action summary " +
                "(totalRewardAmount > 0 for this app+wallet). This mirrors the on-chain guard " +
                "(VeBetterPassport.userUniqueAppInteraction, set when a reward is distributed); " +
                "the XAppReviewManager contract remains the authoritative enforcer.",
    )
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @Parameter(name = "wallet", description = "Wallet address to check", required = true)
    @CommonApiResponses
    open fun getEligibility(
        @PathVariable appId: String,
        @RequestParam wallet: String,
    ): EligibilityApiService.EligibilityResult = service.getEligibility(appId, wallet)
}
