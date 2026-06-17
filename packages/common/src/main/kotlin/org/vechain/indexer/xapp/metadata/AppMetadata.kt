package org.vechain.indexer.xapp.metadata

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

/**
 * A single version-history entry declared inside an app's IPFS metadata JSON.
 *
 * Mirrors the `version_history` array written by the VeBetter app submission flow: `{"version":
 * "V1.0", "notes": "...", "timestamp": 1781157025400}`.
 */
@Schema(description = "App version-history entry from IPFS metadata")
data class AppMetadataVersionEntry(
    @Schema(description = "Version label, e.g. \"V1.0\"") val version: String,
    @Schema(description = "Release notes") val notes: String,
    @Schema(description = "Timestamp (epoch millis)", example = "1781157025400") val timestamp: Long,
)

/**
 * Current IPFS metadata snapshot for a single app, keyed by `appId`.
 *
 * Updated on every `AppMetadataURIUpdated` event from the X2EarnApps contract. Only the
 * `version_history` and `tweets` portions of the off-chain metadata JSON are persisted (plus the
 * `metadataURI` for traceability); everything else in the JSON is intentionally discarded.
 */
@Schema(description = "App IPFS metadata snapshot (version_history + tweets)")
@Document(collection = IndexerNames.XAPP_METADATA.COLLECTION)
data class AppMetadata
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID (app id)") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    override val blockTimestamp: Long,
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(description = "New metadataURI / IPFS CID emitted by the event")
    val metadataURI: String,
    @Schema(description = "Whether the IPFS JSON was fetched and parsed successfully")
    val metadataResolved: Boolean,
    @Schema(description = "Epoch millis the metadata was last fetched", example = "1781157025400")
    val metadataFetchedAt: Long?,
    @Schema(description = "version_history entries from the IPFS metadata JSON")
    val versionHistory: List<AppMetadataVersionEntry>,
    @Schema(description = "Tweet URLs from the IPFS metadata JSON") val tweets: List<String>,
    @Schema(
        description =
            "1 if versionHistory changed vs the previous snapshot for this app, else 0 (0 on first snapshot)"
    )
    val versionHistoryUpdated: Int,
    @Schema(
        description =
            "1 if tweets changed vs the previous snapshot for this app, else 0 (0 on first snapshot)"
    )
    val tweetsUpdated: Int,
    @Schema(
        description =
            "How many optional metadata fields the app filled in (0-7): social_urls, tweets, " +
                "whitepaper, tutorial, more_details.team_background, more_details.app_roadmap, " +
                "more_details.ecosystem_partners"
    )
    val optionalFieldsCount: Int,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        appId: String,
        metadataURI: String,
        metadataResolved: Boolean,
        metadataFetchedAt: Long?,
        versionHistory: List<AppMetadataVersionEntry>,
        tweets: List<String>,
        versionHistoryUpdated: Int,
        tweetsUpdated: Int,
        optionalFieldsCount: Int,
    ) : this(
        id = documentId(appId),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        appId = appId,
        metadataURI = metadataURI,
        metadataResolved = metadataResolved,
        metadataFetchedAt = metadataFetchedAt,
        versionHistory = versionHistory,
        tweets = tweets,
        versionHistoryUpdated = versionHistoryUpdated,
        tweetsUpdated = tweetsUpdated,
        optionalFieldsCount = optionalFieldsCount,
    )

    override fun getDocumentId(): String = id

    companion object {
        fun documentId(appId: String): String = appId
    }
}
