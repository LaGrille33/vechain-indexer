package org.vechain.indexer.xapp.rating

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
import org.vechain.indexer.docs.AddressParameter
import org.vechain.indexer.docs.AppIdParameter
import org.vechain.indexer.docs.CommonApiResponses
import org.vechain.indexer.thor.Address
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.validation.ValidAddress

@Profile("b3tr", "xapp-rating", "xapp-review-vote")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Ratings", description = "Query indexed XApp star ratings.")
open class RatingController(private val service: RatingApiService) {

    @GetMapping("/apps/{appId}/rating")
    @Operation(
        summary = "Get the average rating and rater count for an app.",
        description =
            "Optionally pass ?wallet= to include the current user's rating and hasRated flag.",
    )
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @Parameter(
        name = "wallet",
        description =
            "Current user's wallet address (optional, adds userRating + hasRated to response)",
    )
    @CommonApiResponses
    open fun getAppRating(
        @PathVariable appId: String,
        @RequestParam(required = false) wallet: String?,
    ): AppRatingSummary =
        service.getAppRatingSummary(
            appId = normalise(appId),
            wallet = wallet?.let { normalise(it) },
        )

    @GetMapping("/apps/{appId}/ratings/{wallet}")
    @Operation(summary = "Get the rating a specific wallet gave an app.")
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @AddressParameter(name = "wallet", required = true, `in` = ParameterIn.PATH)
    @CommonApiResponses
    open fun getUserRating(
        @PathVariable appId: String,
        @ValidAddress @PathVariable wallet: Address,
    ): AppRating? = service.getUserRating(normalise(appId), normalise(wallet.value))
}
