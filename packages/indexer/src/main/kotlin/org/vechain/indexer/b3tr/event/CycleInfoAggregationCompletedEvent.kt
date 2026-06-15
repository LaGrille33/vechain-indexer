package org.vechain.indexer.b3tr.event

/**
 * Published by [CycleInfoAggregationService] after successfully syncing cycle info from chain.
 * Downstream services can listen for this event to trigger dependent computations.
 */
data class CycleInfoAggregationCompletedEvent(
    /** The highest round that was synced during this run. */
    val syncedUpToRound: Int
)
