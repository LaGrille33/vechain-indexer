package org.vechain.indexer.xapp.metadata

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.vechain.indexer.Indexer
import org.vechain.indexer.IndexerFactory
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.thor.client.ThorClient

/**
 * Subscribes to `AppMetadataURIUpdated(bytes32 indexed appId, string oldMetadataURI, string
 * newMetadataURI)` emitted by the X2EarnApps contract, starting from block 0.
 */
@Configuration
@Profile("b3tr", "xapp-metadata")
open class AppMetadataConfig {
    @Bean
    open fun xAppMetadataIndexer(
        thorClient: ThorClient,
        processor: AppMetadataProcessor,
        @Value("\${indexer.start-block.xapp-metadata}") startBlock: Long,
        @Value("\${indexer.sync-log-interval}") syncLoggerInterval: Long,
        @Value("\${business-event.substitutions.X2EARN_APPS_CONTRACT}")
        x2EarnAppsContractAddress: String,
    ): Indexer =
        IndexerFactory()
            .name(IndexerNames.XAPP_METADATA.NAME)
            .thorClient(thorClient)
            .processor(processor)
            .startBlock(startBlock)
            .syncLoggerInterval(syncLoggerInterval)
            .abis("abis/b3tr")
            .abiEventNames(listOf("AppMetadataURIUpdated"))
            .abiContracts(listOf(x2EarnAppsContractAddress))
            .build()
}
