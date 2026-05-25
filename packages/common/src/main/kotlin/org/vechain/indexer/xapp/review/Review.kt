package org.vechain.indexer.xapp.review

import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

enum class VoteType {
    UPVOTE,
    DOWNVOTE,
    REPORT,
}

data class ReviewVote(
    val voter: String,
    val voteType: Int, // 1 = UPVOTE, 2 = DOWNVOTE, 3 = REPORT
    val timestamp: Long,
)

@Document(collection = IndexerNames.XAPP_REVIEW.COLLECTION)
data class Review
@ConstructorBinding
constructor(
    @Id val id: String,
    override val version: Int,
    override val blockId: String,
    override val blockNumber: Long,
    override val blockTimestamp: Long,
    val reviewId: Long,
    val appId: String,
    val author: String,
    val rating: Int,
    val title: String,
    val content: String,
    val upvotes: Long,
    val downvotes: Long,
    val reports: Long,
    val isHidden: Boolean,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        reviewId: Long,
        appId: String,
        author: String,
        rating: Int,
        title: String,
        content: String,
        upvotes: Long = 0,
        downvotes: Long = 0,
        reports: Long = 0,
        isHidden: Boolean = false,
    ) : this(
        id = reviewId.toString(),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        reviewId = reviewId,
        appId = appId,
        author = author,
        rating = rating,
        title = title,
        content = content,
        upvotes = upvotes,
        downvotes = downvotes,
        reports = reports,
        isHidden = isHidden,
    )

    override fun getDocumentId(): String = id
}
