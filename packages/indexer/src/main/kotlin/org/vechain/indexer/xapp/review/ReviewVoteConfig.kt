package org.vechain.indexer.xapp.review

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.vechain.indexer.Indexer
import org.vechain.indexer.IndexerFactory
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.thor.client.ThorClient

@Configuration
@Profile("b3tr", "xapp-review-vote")
open class ReviewVoteConfig {
    @Bean
    open fun xAppReviewVoteIndexer(
        thorClient: ThorClient,
        processor: ReviewVoteProcessor,
        @Value("\${indexer.start-block.xapp-review-vote}") startBlock: Long,
        @Value("\${indexer.sync-log-interval}") syncLoggerInterval: Long,
        @Value("\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT}")
        reviewManagerContractAddress: String,
    ): Indexer =
        IndexerFactory()
            .name(IndexerNames.XAPP_REVIEW_VOTE.NAME)
            .thorClient(thorClient)
            .processor(processor)
            .startBlock(startBlock)
            .syncLoggerInterval(syncLoggerInterval)
            .abis("abis/b3tr")
            .abiEventNames(listOf("ReviewVoted"))
            .abiContracts(listOf(reviewManagerContractAddress))
            .build()
}
