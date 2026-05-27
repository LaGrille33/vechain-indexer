package org.vechain.indexer.b3tr.onchain

import java.math.BigDecimal
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.utils.IdUtils.generateId

@Document(collection = IndexerNames.ONCHAIN_SCORE.COLLECTION)
data class AppOnchainScore(
    @Id val id: String,
    val appId: String,
    val roundId: Int,
    val name: String,
    val slug: String,
    val teamWallet: String?,
    @Field(targetType = FieldType.DECIMAL128) val allocB3tr: BigDecimal,
    @Field(targetType = FieldType.DECIMAL128) val distributedB3tr: BigDecimal,
    val actions: Long,
    val wau: Long,
    val newUsers: Long?,
    val rankByReward: Int?,
    val rankByActions: Int?,
    val rankPrior: Int?,
    val rankDelta: Int?,
    val pWau: BigDecimal?,
    val pNewUsers: BigDecimal?,
    val pActions: BigDecimal?,
    @Field(targetType = FieldType.DECIMAL128) val activityScore: BigDecimal?,
    @Field(targetType = FieldType.DECIMAL128) val distributionEfficiency: BigDecimal?,
    @Field(targetType = FieldType.DECIMAL128) val distributionScore: BigDecimal?,
    val updatedAt: Long,
) {
    constructor(
        appId: String,
        roundId: Int,
        name: String,
        slug: String,
        teamWallet: String?,
        allocB3tr: BigDecimal,
        distributedB3tr: BigDecimal,
        actions: Long,
        wau: Long,
        newUsers: Long?,
        rankByReward: Int?,
        rankByActions: Int?,
        rankPrior: Int?,
        rankDelta: Int?,
        pWau: BigDecimal?,
        pNewUsers: BigDecimal?,
        pActions: BigDecimal?,
        activityScore: BigDecimal?,
        distributionEfficiency: BigDecimal?,
        distributionScore: BigDecimal?,
        updatedAt: Long,
    ) : this(
        id = generateId(appId, "$roundId"),
        appId = appId,
        roundId = roundId,
        name = name,
        slug = slug,
        teamWallet = teamWallet,
        allocB3tr = allocB3tr,
        distributedB3tr = distributedB3tr,
        actions = actions,
        wau = wau,
        newUsers = newUsers,
        rankByReward = rankByReward,
        rankByActions = rankByActions,
        rankPrior = rankPrior,
        rankDelta = rankDelta,
        pWau = pWau,
        pNewUsers = pNewUsers,
        pActions = pActions,
        activityScore = activityScore,
        distributionEfficiency = distributionEfficiency,
        distributionScore = distributionScore,
        updatedAt = updatedAt,
    )
}
