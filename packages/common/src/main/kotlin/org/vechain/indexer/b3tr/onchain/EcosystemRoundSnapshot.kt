package org.vechain.indexer.b3tr.onchain

import java.math.BigDecimal
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import org.vechain.indexer.IndexerNames

@Document(collection = IndexerNames.ECOSYSTEM_ROUND_SNAPSHOT.COLLECTION)
data class EcosystemRoundSnapshot(
    @Id val id: String,
    val roundId: Int,
    @Field(targetType = FieldType.DECIMAL128) val totalB3trAllocated: BigDecimal,
    @Field(targetType = FieldType.DECIMAL128) val totalB3trDistributed: BigDecimal,
    val totalActions: Long,
    val totalWau: Long,
    val activeApps: Long,
    val eligibleAppsCount: Long,
    val newUsersAvailable: Boolean,
    val updatedAt: Long,
) {
    constructor(
        roundId: Int,
        totalB3trAllocated: BigDecimal,
        totalB3trDistributed: BigDecimal,
        totalActions: Long,
        totalWau: Long,
        activeApps: Long,
        eligibleAppsCount: Long,
        newUsersAvailable: Boolean,
        updatedAt: Long,
    ) : this(
        id = roundId.toString(),
        roundId = roundId,
        totalB3trAllocated = totalB3trAllocated,
        totalB3trDistributed = totalB3trDistributed,
        totalActions = totalActions,
        totalWau = totalWau,
        activeApps = activeApps,
        eligibleAppsCount = eligibleAppsCount,
        newUsersAvailable = newUsersAvailable,
        updatedAt = updatedAt,
    )
}
