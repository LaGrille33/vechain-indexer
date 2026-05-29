package org.vechain.indexer.xapp.badge

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

@Schema(description = "Badge type")
enum class BadgeType {
    @Schema(description = "Top Ecosystem DApp")
    TOP_ECOSYSTEM_DAPP,
    @Schema(description = "Top Distribution Performer")
    TOP_DISTRIBUTION_PERFORMER,
    @Schema(description = "Navigator's Pick")
    NAVIGATORS_PICK,
}

@Schema(description = "App badge award record")
@Document(collection = IndexerNames.XAPP_BADGE_AWARD.COLLECTION)
data class BadgeAward
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224") override val blockTimestamp: Long,
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(description = "Badge type") val badgeType: BadgeType,
    @Schema(description = "Scoring round number", example = "1") val round: Long,
    @Schema(description = "Rank", example = "3") val rank: Int,
    @Schema(description = "Whether published", example = "true") val isPublished: Boolean = true,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        appId: String,
        badgeType: BadgeType,
        round: Long,
        rank: Int,
        isPublished: Boolean = true,
    ) : this(
        id = "${appId}_${badgeType.name}_$round",
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        appId = appId,
        badgeType = badgeType,
        round = round,
        rank = rank,
        isPublished = isPublished,
    )

    override fun getDocumentId(): String = id
}

@Schema(description = "App badge summary")
data class BadgeSummary(
    @Schema(description = "Badge type") val badgeType: BadgeType,
    @Schema(description = "Badge name", example = "Top Ecosystem DApp") val badgeName: String,
    @Schema(description = "Whether published", example = "true") val isPublished: Boolean,
    @Schema(description = "Total times awarded", example = "5") val totalCount: Int,
    @Schema(description = "Latest round number awarded", example = "3") val latestRound: Long?,
)

@Schema(description = "App badge detail")
data class BadgeDetail(
    @Schema(description = "Badge type") val badgeType: BadgeType,
    @Schema(description = "Badge name", example = "Top Ecosystem DApp") val badgeName: String,
    @Schema(description = "Whether published", example = "true") val isPublished: Boolean,
    @Schema(description = "Badge description") val description: String,
    @Schema(description = "Total times awarded", example = "5") val totalCount: Int,
    @Schema(description = "Latest round number awarded", example = "3") val latestRound: Long?,
    @Schema(description = "List of badge award records") val records: List<BadgeRecord>,
)

@Schema(description = "Badge award record")
data class BadgeRecord(
    @Schema(description = "Scoring round number", example = "1") val round: Long,
    @Schema(description = "Rank", example = "3") val rank: Int,
    @Schema(description = "Record timestamp (Unix seconds)", example = "1779424224") val date: Long,
)

@Schema(description = "Badge visibility update request")
data class BadgeVisibilityRequest(
    @Schema(description = "Whether to publish", example = "true") val isPublished: Boolean,
)
