package org.vechain.indexer.b3tr.event

/**
 * Published by [UserFirstSeenAggregationService] after successfully processing new rounds.
 * Downstream services can listen for this event to trigger dependent computations.
 */
data class UserFirstSeenAggregationCompletedEvent(
    /** The highest round that was processed during this run. */
    val processedUpToRound: Int
)
