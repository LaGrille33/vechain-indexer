package org.vechain.indexer.xapp.scoring

import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

@Document(collection = IndexerNames.XAPP_SCORING_ROUND.COLLECTION)
data class ScoringRound
@ConstructorBinding
constructor(
    @Id val id: String,
    override val version: Int,
    override val blockId: String,
    override val blockNumber: Long,
    override val blockTimestamp: Long,
    val round: Long,
    val appCount: Long,
    val rankedAppCount: Long?,
    val isFinalized: Boolean,
    val hasRankingsUpdated: Boolean,
) : VersionedDocument {
    constructor(
        version: Int,
        blockId: String,
        blockNumber: Long,
        blockTimestamp: Long,
        round: Long,
        appCount: Long = 0,
        rankedAppCount: Long? = null,
        isFinalized: Boolean = false,
        hasRankingsUpdated: Boolean = false,
    ) : this(
        id = round.toString(),
        version = version,
        blockId = blockId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        round = round,
        appCount = appCount,
        rankedAppCount = rankedAppCount,
        isFinalized = isFinalized,
        hasRankingsUpdated = hasRankingsUpdated,
    )

    override fun getDocumentId(): String = id
}
