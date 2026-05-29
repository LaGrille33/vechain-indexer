package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.VersionedDocument

@Schema(description = "App scoring round")
@Document(collection = IndexerNames.XAPP_SCORING_ROUND.COLLECTION)
data class ScoringRound
@ConstructorBinding
constructor(
    @Id @Schema(description = "MongoDB document ID") val id: String,
    @Schema(description = "Document version") override val version: Int,
    @Schema(description = "Block hash") override val blockId: String,
    @Schema(description = "Block number", example = "15000000") override val blockNumber: Long,
    @Schema(description = "Block timestamp (Unix seconds)", example = "1779424224")
    override val blockTimestamp: Long,
    @Schema(description = "Round number", example = "1") val round: Long,
    @Schema(description = "Number of apps in this scoring round", example = "120")
    val appCount: Long,
    @Schema(description = "Number of ranked apps (available after finalization)", example = "100")
    val rankedAppCount: Long?,
    @Schema(description = "Whether finalized", example = "true") val isFinalized: Boolean,
    @Schema(description = "Whether rankings have been updated", example = "false")
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
