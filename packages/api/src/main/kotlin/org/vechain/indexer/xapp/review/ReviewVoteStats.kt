package org.vechain.indexer.xapp.review

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Statistics for a single vote type")
data class VoteTypeStats(
    @Schema(description = "Number of votes of this type", example = "35") val count: Long,
    @Schema(description = "Percentage of total votes", example = "70.0") val percentage: Double,
)

@Schema(description = "Aggregated vote statistics for a review")
data class ReviewVoteStats(
    @Schema(description = "Total number of votes", example = "50") val total: Long,
    @Schema(description = "Upvote statistics") val upvotes: VoteTypeStats,
    @Schema(description = "Downvote statistics") val downvotes: VoteTypeStats,
    @Schema(description = "Report statistics") val reports: VoteTypeStats,
)

@Schema(description = "Detailed vote record with domain resolution")
data class ReviewVoteDetail(
    @Schema(
        description = "Voter wallet address (checksummed)",
        example = "0x4F064B42dD4583754cd1E2D21C8FeF2786CbD4a9",
    )
    val voter: String,
    @Schema(description = "Vet domain name (null if not resolved)", example = "jun.vet")
    val domain: String?,
    @Schema(description = "Vote type: 1=UPVOTE, 2=DOWNVOTE, 3=REPORT", example = "1")
    val voteType: Int,
    @Schema(description = "Vote type name", example = "UPVOTE") val voteTypeName: String,
    @Schema(description = "Vote timestamp (Unix seconds)", example = "1779424224")
    val timestamp: Long,
)

@Schema(description = "Review with vote statistics and user context")
data class ReviewWithVotes(
    @Schema(description = "Review ID", example = "6") val reviewId: Long,
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(
        description = "Author wallet address (checksummed)",
        example = "0x4F064B42dD4583754cd1E2D21C8FeF2786CbD4a9",
    )
    val author: String,
    @Schema(description = "Author vet domain name (null if not resolved)", example = "alice.vet")
    val authorDomain: String?,
    @Schema(description = "Review title", example = "Great app") val title: String,
    @Schema(description = "Review content", example = "Really good experience.")
    val content: String,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    val blockTimestamp: Long,
    @Schema(description = "Whether the review is hidden", example = "false") val isHidden: Boolean,
    @Schema(description = "Upvote statistics") val upvotes: VoteTypeStats,
    @Schema(description = "Downvote statistics") val downvotes: VoteTypeStats,
    @Schema(description = "Report statistics") val reports: VoteTypeStats,
    @Schema(
        description =
            "Current user's vote type on this review (0=none, 1=upvote, 2=downvote, 3=report)",
        example = "1",
    )
    val myVoteType: Int,
)

@Schema(description = "App detail with rating stats")
data class AppDetailResponse(
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(description = "Average rating, one decimal", example = "4.7") val average: Double,
    @Schema(description = "Number of raters", example = "128") val count: Long,
    @Schema(description = "Current user's rating (null if not rated)", example = "4")
    val userRating: Int?,
    @Schema(description = "Whether the current user has rated this app", example = "true")
    val hasRated: Boolean,
)
