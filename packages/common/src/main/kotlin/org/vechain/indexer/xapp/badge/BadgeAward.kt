package org.vechain.indexer.xapp.badge

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

enum class BadgeType(@JsonValue val value: String) {
    TOP_ECOSYSTEM_DAPP("topEcosystemDapp"),
    TOP_DISTRIBUTION_PERFORMER("topDistributionPerformer"),
    NAVIGATORS_PICK("navigatorsPick");

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromValue(value: String): BadgeType = entries.first { it.value == value }
    }
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
    val roundBlockTimestamp: Long? = null,
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
        roundBlockTimestamp: Long? = null,
    ) : this(
        id = "${appId}_${badgeType.value}_$round",
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        appId = appId,
        badgeType = badgeType,
        round = round,
        rank = rank,
        isPublished = isPublished,
        roundBlockTimestamp = roundBlockTimestamp,
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

data class BadgeRoundInfo(val round: Long, val rank: Int?, val date: Long)

data class BadgeStatsResponse(
    val totalCount: Int,
    val latestRound: Long?,
    val rounds: List<BadgeRoundInfo>,
)

data class BadgeVisibilityRequest(val isPublished: Boolean)
