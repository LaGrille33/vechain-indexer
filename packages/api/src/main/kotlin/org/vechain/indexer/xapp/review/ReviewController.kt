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
import org.vechain.indexer.docs.PaginationParameters
import org.vechain.indexer.rest.PaginatedCountResponse
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.utils.PaginationUtils.toPageable
import org.vechain.indexer.validation.ValidPageSize

@Profile("b3tr", "xapp-review", "xapp-review-vote")
@Validated
@RestController
@RequestMapping(XAPP_PATH)
@Tag(name = "XApp - Reviews", description = "Query indexed XApp reviews.")
open class ReviewController(private val service: ReviewApiService) {

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
            "Sort field: newest (default, by time), most_upvotes (by upvote count), most_downvotes (by downvote count), most_reports (by report count), my_reviews (viewer's own reviews, requires wallet)",
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
    ): PaginatedCountResponse<ReviewWithVotes> {
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
    open fun getReview(@PathVariable reviewId: Long): ReviewWithVotes = service.getReview(reviewId)

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
}
