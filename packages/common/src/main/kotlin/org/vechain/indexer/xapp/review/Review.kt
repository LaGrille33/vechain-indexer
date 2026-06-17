package org.vechain.indexer.xapp.review

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

@Schema(description = "Vote type")
enum class VoteType {
    @Schema(description = "Upvote") UPVOTE,
    @Schema(description = "Downvote") DOWNVOTE,
    @Schema(description = "Report") REPORT,
}

@Schema(description = "Review vote record")
data class ReviewVote(
    @Schema(description = "Voter wallet address") val voter: String,
    @Schema(description = "Vote type: 1=upvote, 2=downvote, 3=report", example = "1")
    val voteType: Int,
    @Schema(description = "Vote timestamp (Unix seconds)", example = "1779424224")
    val timestamp: Long,
)

@Schema(description = "App review")
@Document(collection = IndexerNames.XAPP_REVIEW.COLLECTION)
data class Review
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    override val blockTimestamp: Long,
    @Schema(description = "Review ID", example = "6") val reviewId: Long,
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(
        description = "Author wallet address (checksummed)",
        example = "0xf077b491b355e64048ce21e3a6fc4751eeea77fa",
    )
    val author: String,
    @Schema(description = "Review title", example = "Great app") val title: String,
    @Schema(description = "Review content", example = "Really good experience using this app.")
    val content: String,
    @Schema(description = "Upvote count", example = "2") val upvotes: Long,
    @Schema(description = "Downvote count", example = "0") val downvotes: Long,
    @Schema(description = "Report count", example = "0") val reports: Long,
    @Schema(description = "Whether hidden (auto-hidden when reports >= 20)", example = "false")
    val isHidden: Boolean,
    @Schema(description = "Creation (publish) timestamp, Unix seconds; null on legacy docs")
    val createdAt: Long? = null,
    @Schema(description = "Last edit timestamp, Unix seconds; null on legacy docs")
    val updatedAt: Long? = null,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        reviewId: Long,
        appId: String,
        author: String,
        title: String,
        content: String,
        upvotes: Long = 0,
        downvotes: Long = 0,
        reports: Long = 0,
        isHidden: Boolean = false,
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ) : this(
        id = reviewId.toString(),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        reviewId = reviewId,
        appId = appId,
        author = author,
        title = title,
        content = content,
        upvotes = upvotes,
        downvotes = downvotes,
        reports = reports,
        isHidden = isHidden,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    override fun getDocumentId(): String = id
}
