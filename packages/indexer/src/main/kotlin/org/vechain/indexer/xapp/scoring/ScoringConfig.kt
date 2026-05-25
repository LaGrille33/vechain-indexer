package org.vechain.indexer.xapp.scoring

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.vechain.indexer.Indexer
import org.vechain.indexer.IndexerFactory
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.thor.client.ThorClient

@Configuration
@Profile("b3tr", "xapp-scoring")
open class ScoringConfig {
    @Bean
    open fun xAppScoringRoundIndexer(
        thorClient: ThorClient,
        processor: ScoringProcessor,
        @Value("\${indexer.start-block.xapp-scoring}") startBlock: Long,
        @Value("\${indexer.sync-log-interval}") syncLoggerInterval: Long,
        @Value("\${business-event.substitutions.XAPP_SCORING_CONTRACT}")
        scoringContractAddress: String,
    ): Indexer =
        IndexerFactory()
            .name(IndexerNames.XAPP_SCORING_ROUND.NAME)
            .thorClient(thorClient)
            .processor(processor)
            .startBlock(startBlock)
            .syncLoggerInterval(syncLoggerInterval)
            .abis("abis/b3tr")
            .abiEventNames(listOf("ScoresRecorded", "RoundFinalized", "RoundRankingsUpdated"))
            .abiContracts(listOf(scoringContractAddress))
            .build()
}
