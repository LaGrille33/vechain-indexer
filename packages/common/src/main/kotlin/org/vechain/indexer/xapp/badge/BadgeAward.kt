package org.vechain.indexer.xapp.badge

import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

enum class BadgeType {
    TOP_ECOSYSTEM_DAPP,
    TOP_DISTRIBUTION_PERFORMER,
    NAVIGATORS_PICK,
}

@Document(collection = IndexerNames.XAPP_BADGE_AWARD.COLLECTION)
data class BadgeAward
@ConstructorBinding
constructor(
    @Id val id: String,
    override val version: Int,
    override val blockId: String,
    override val blockNumber: Long,
    override val blockTimestamp: Long,
    val appId: String,
    val badgeType: BadgeType,
    val round: Long,
    val rank: Int,
    val isPublished: Boolean = true,
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

data class BadgeSummary(
    val badgeType: BadgeType,
    val badgeName: String,
    val isPublished: Boolean,
    val totalCount: Int,
    val latestRound: Long?,
)

data class BadgeDetail(
    val badgeType: BadgeType,
    val badgeName: String,
    val isPublished: Boolean,
    val description: String,
    val totalCount: Int,
    val latestRound: Long?,
    val records: List<BadgeRecord>,
)

data class BadgeRecord(val round: Long, val rank: Int, val date: Long)

data class BadgeVisibilityRequest(val isPublished: Boolean)
