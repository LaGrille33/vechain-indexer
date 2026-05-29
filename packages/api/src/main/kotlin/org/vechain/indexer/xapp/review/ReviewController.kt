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
import org.vechain.indexer.docs.AddressParameter
import org.vechain.indexer.docs.AppIdParameter
import org.vechain.indexer.docs.CommonApiResponses
import org.vechain.indexer.docs.PaginationParameters
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.thor.Address
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.utils.PaginationUtils.toPageable
import org.vechain.indexer.validation.ValidAddress
import org.vechain.indexer.validation.ValidPageSize

@Profile("b3tr", "xapp-review", "xapp-review-vote")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Reviews", description = "Query indexed XApp reviews.")
open class ReviewController(private val service: ReviewApiService) {

    @GetMapping("/apps/{appId}/detail")
    @Operation(
        summary = "Get app detail: rating summary.",
        description = "Returns aggregate rating stats (avg, count, userRating, hasRated).",
    )
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @Parameter(
        name = "wallet",
        description = "Current user's wallet address (optional, adds userRating + hasRated)",
    )
    @CommonApiResponses
    open fun getAppDetail(
        @PathVariable appId: String,
        @RequestParam(required = false) wallet: String?,
    ): AppDetailResponse =
        service.getAppDetail(appId = normalise(appId), wallet = wallet?.let { normalise(it) })

    @GetMapping("/apps/{appId}/reviews")
    @Operation(
        summary = "Get reviews for an app with vote stats and user context.",
        description =
            "Returns reviews enriched with vote percentages, author domain, and current user's vote. " +
                "Hidden reviews are included if the viewer is the author. " +
                "Sort options: newest (default), most_upvotes, most_downvotes, most_reports, my_reviews.",
    )
    @AppIdParameter(required = true, `in` = ParameterIn.PATH)
    @Parameter(
        name = "sortBy",
        description =
            "Sort field: upvotes/most_upvotes (by upvote count), downvotes/most_downvotes (by downvote count), reports/most_reports (by report count), defaults to timestamp",
    )
    @Parameter(name = "author", description = "Filter by author address (checksummed format)")
    @CommonApiResponses
    @PaginationParameters
    open fun getAppReviews(
        @PathVariable appId: String,
        @RequestParam(required = false) wallet: String?,
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false) author: String?,
    ): PaginatedResponse<ReviewWithVotes> {
        val normalizedSort = sortBy?.lowercase()
        val sortFields =
            when (normalizedSort) {
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
                normalizedSort in
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
            viewer = wallet?.let { normalise(it) },
            sortBy = normalizedSort,
            pageable = toPageable(page, size, finalDirection, *sortFields),
        )
    }

    @GetMapping("/reviews/{reviewId}")
    @Operation(summary = "Get a single review by ID.")
    @Parameter(name = "reviewId", description = "Review ID", `in` = ParameterIn.PATH, example = "6")
    @CommonApiResponses
    open fun getReview(@PathVariable reviewId: Long): Review = service.getReview(reviewId)

    @GetMapping("/reviews/{reviewId}/votes/stats")
    @Operation(summary = "Get aggregated vote statistics for a review.")
    @Parameter(name = "reviewId", description = "Review ID", `in` = ParameterIn.PATH, example = "6")
    @CommonApiResponses
    open fun getVoteStats(@PathVariable reviewId: Long): ReviewVoteStats =
        service.getVoteStats(reviewId)

    @GetMapping("/reviews/{reviewId}/votes")
    @Operation(summary = "Get paginated vote history for a review.")
    @Parameter(name = "reviewId", description = "Review ID", `in` = ParameterIn.PATH, example = "6")
    @Parameter(
        name = "search",
        description = "Search by voter address or domain name (e.g. '0x123...' or 'jun.vet')",
    )
    @CommonApiResponses
    @PaginationParameters
    open fun getReviewVotes(
        @PathVariable reviewId: Long,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) page: Int?,
        @ValidPageSize @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) direction: String?,
    ): PaginatedResponse<ReviewVoteDetail> =
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
