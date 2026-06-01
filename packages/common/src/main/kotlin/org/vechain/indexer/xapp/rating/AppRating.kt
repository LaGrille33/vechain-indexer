package org.vechain.indexer.xapp.rating

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

/**
 * A single user's current star rating for an app.
 *
 * Ratings are independent from reviews: a user may rate an app without writing a review (and vice
 * versa). There is at most one rating per (appId, rater); updating a rating overwrites the existing
 * document (keyed by "<appId>_<rater>").
 */
@Schema(description = "App rating (one per user per app)")
@Document(collection = IndexerNames.XAPP_RATING.COLLECTION)
data class AppRating
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID (<appId>_<rater>)") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    override val blockTimestamp: Long,
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(
        description = "Rater wallet address (checksummed)",
        example = "0x4F064B42dD4583754cd1E2D21C8FeF2786CbD4a9",
    )
    val rater: String,
    @Schema(description = "Rating (1-5)", example = "4") val rating: Int,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        appId: String,
        rater: String,
        rating: Int,
    ) : this(
        id = documentId(appId, rater),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        appId = appId,
        rater = rater,
        rating = rating,
    )

    override fun getDocumentId(): String = id

    companion object {
        fun documentId(appId: String, rater: String): String = "${appId}_$rater"
    }
}
