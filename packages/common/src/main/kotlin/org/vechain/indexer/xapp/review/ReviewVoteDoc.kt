package org.vechain.indexer.xapp.review

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

/**
 * A single user's vote on a review.
 *
 * Each user can have at most one active vote per review (upsert semantics). Changing a vote (e.g.
 * from UPVOTE to DOWNVOTE) updates the existing document keyed by "{reviewId}_{voter}".
 */
@Schema(description = "Review vote record (one per user per review)")
@Document(collection = IndexerNames.XAPP_REVIEW_VOTE.COLLECTION)
data class ReviewVoteDoc
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID ({reviewId}_{voter})") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    override val blockTimestamp: Long,
    @Schema(description = "Review ID", example = "6") val reviewId: Long,
    @Schema(
        description = "Voter wallet address (checksummed)",
        example = "0xf077b491b355e64048ce21e3a6fc4751eeea77fa",
    )
    val voter: String,
    @Schema(description = "Vote type: 1=UPVOTE, 2=DOWNVOTE, 3=REPORT", example = "1")
    val voteType: Int,
    @Schema(description = "Vote timestamp (Unix seconds)", example = "1779424224")
    val timestamp: Long,
    @Schema(description = "Voter vet domain name (null if not resolved)", example = "jun.vet")
    val domain: String? = null,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        reviewId: Long,
        voter: String,
        voteType: Int,
        timestamp: Long,
        domain: String? = null,
    ) : this(
        id = documentId(reviewId, voter),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        reviewId = reviewId,
        voter = voter,
        voteType = voteType,
        timestamp = timestamp,
        domain = domain,
    )

    override fun getDocumentId(): String = id

    companion object {
        fun documentId(reviewId: Long, voter: String): String = "${reviewId}_$voter"
    }
}
