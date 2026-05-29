package org.vechain.indexer.b3tr.round

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames

@Document(collection = IndexerNames.CYCLE_INFO.COLLECTION)
data class CycleInfo(
    @Id val id: String,
    val roundId: Int,
    val blockNumber: Long,
    val blockTimestamp: Long,
    val eventType: String,
    val updatedAt: Long,
) {
    constructor(
        roundId: Int,
        blockNumber: Long,
        blockTimestamp: Long,
        eventType: String,
        updatedAt: Long,
    ) : this(
        id = roundId.toString(),
        roundId = roundId,
        blockNumber = blockNumber,
        blockTimestamp = blockTimestamp,
        eventType = eventType,
        updatedAt = updatedAt,
    )
}
