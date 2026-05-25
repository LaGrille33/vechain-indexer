package org.vechain.indexer.xapp.review

import io.swagger.v3.oas.annotations.Operation
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
import org.vechain.indexer.docs.CommonApiResponses
import org.vechain.indexer.docs.PaginationParameters
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.thor.Address
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.utils.PaginationUtils.toPageable
import org.vechain.indexer.validation.ValidAddress
import org.vechain.indexer.validation.ValidPageSize

@Profile("b3tr", "xapp-review")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Reviews", description = "Query indexed XApp reviews.")
open class ReviewController(private val service: ReviewApiService) {

    @GetMapping("/apps/{appId}/reviews")
    @Operation(summary = "Get reviews for an app.")
    @CommonApiResponses
    @PaginationParameters
    open fun getAppReviews(
        @PathVariable appId: String,
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false) author: String?,
    ): PaginatedResponse<Review> {
        val sortFields =
            when (sortBy?.lowercase()) {
                "upvotes",
                "most_upvotes" -> arrayOf("upvotes", "blockTimestamp")
                "downvotes",
                "most_downvotes" -> arrayOf("downvotes", "blockTimestamp")
                "reports",
                "most_reports" -> arrayOf("reports", "blockTimestamp")
                else -> arrayOf("blockTimestamp")
            }
        val finalDirection =
            if (
                sortBy != null &&
                    sortBy.lowercase() in
                        setOf(
                            "upvotes",
                            "most_upvotes",
                            "downvotes",
                            "most_downvotes",
                            "reports",
                            "most_reports",
                        )
            ) {
                "desc"
            } else {
                direction ?: "desc"
            }
        return service.getAppReviews(
            appId = normalise(appId),
            author = author?.let { normalise(it) },
            pageable = toPageable(page, size, finalDirection, *sortFields),
        )
    }

    @GetMapping("/reviews/{reviewId}")
    @Operation(summary = "Get a single review by ID.")
    @CommonApiResponses
    open fun getReview(@PathVariable reviewId: Long): Review = service.getReview(reviewId)

    @GetMapping("/reviews/{reviewId}/votes")
    @Operation(summary = "Get vote history for a review.")
    @CommonApiResponses
    @PaginationParameters
    open fun getReviewVotes(
        @PathVariable reviewId: Long,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
    ): PaginatedResponse<ReviewVote> =
        service.getReviewVotes(
            reviewId = reviewId,
            search = search,
            pageable = toPageable(page, size, direction ?: "desc", "timestamp"),
        )

    @GetMapping("/users/{wallet}/reviews")
    @Operation(summary = "Get reviews written by a user.")
    @AddressParameter(name = "wallet", required = true, `in` = ParameterIn.PATH)
    @CommonApiResponses
    @PaginationParameters
    open fun getUserReviews(
        @ValidAddress @PathVariable wallet: Address,
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
    ): PaginatedResponse<Review> =
        service.getUserReviews(
            author = normalise(wallet.value),
            pageable = toPageable(page, size, direction, "blockTimestamp"),
        )
}
