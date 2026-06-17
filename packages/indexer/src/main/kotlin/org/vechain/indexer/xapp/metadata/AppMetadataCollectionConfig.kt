package org.vechain.indexer.xapp.metadata

import kotlinx.coroutines.CoroutineScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.IndexedDocument
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.config.mongo.CollectionConfig
import org.vechain.indexer.version.IndexerVersionService

@Profile("b3tr", "xapp-metadata")
@Configuration
open class AppMetadataCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
    private val indexerVersionService: IndexerVersionService,
) : CollectionConfig(mongoTemplate, appCoroutineScope, AppMetadata::class.java) {

    @Value("\${indexer.version.xapp-metadata}") private val version: Int = 1

    override fun initCollection() {
        indexerVersionService.checkAndResetCollectionIfVersionChanged(
            IndexerNames.XAPP_METADATA.NAME,
            AppMetadata::class.java,
            version,
        )
        ensureCollection()
        ensureIndexes(
            listOf(
                buildIndex(IndexedDocument::blockNumber.name to Sort.Direction.DESC),
                buildIndex(AppMetadata::appId.name to Sort.Direction.ASC),
            )
        )
    }
}
