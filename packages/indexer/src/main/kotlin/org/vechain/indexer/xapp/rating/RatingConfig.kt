package org.vechain.indexer.xapp.rating

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.vechain.indexer.Indexer
import org.vechain.indexer.IndexerFactory
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.thor.client.ThorClient

@Configuration
@Profile("b3tr", "xapp-rating")
open class RatingConfig {
    @Bean
    open fun xAppRatingIndexer(
        thorClient: ThorClient,
        processor: RatingProcessor,
        @Value("\${indexer.start-block.xapp-rating}") startBlock: Long,
        @Value("\${indexer.sync-log-interval}") syncLoggerInterval: Long,
        @Value("\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT}")
        reviewManagerContractAddress: String,
    ): Indexer =
        IndexerFactory()
            .name(IndexerNames.XAPP_RATING.NAME)
            .thorClient(thorClient)
            .processor(processor)
            .startBlock(startBlock)
            .syncLoggerInterval(syncLoggerInterval)
            .abis("abis/b3tr")
            .abiEventNames(listOf("RatingSubmitted", "RatingUpdated"))
            .abiContracts(listOf(reviewManagerContractAddress))
            .build()
}
