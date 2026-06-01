package org.vechain.indexer.xapp.rating

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.IndexedDocument
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.config.mongo.CollectionConfig
import org.vechain.indexer.version.IndexerVersionService

@Profile("b3tr", "xapp-rating")
@Configuration
open class RatingCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
    private val indexerVersionService: IndexerVersionService,
) : CollectionConfig(mongoTemplate, appCoroutineScope, AppRating::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Value("\${indexer.version.xapp-rating}") private val version: Int = 1

    override fun initCollection() {
        logger.debug("Check collection version for ${modelObj.simpleName}")
        indexerVersionService.checkAndResetCollectionIfVersionChanged(
            IndexerNames.XAPP_RATING.NAME,
            AppRating::class.java,
            version,
        )
        ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            listOf(
                buildIndex(IndexedDocument::blockNumber.name to Sort.Direction.DESC),
                // Serves per-app aggregation (avg + count) and per-app listing.
                buildIndex(AppRating::appId.name to Sort.Direction.ASC),
                // Serves "my rating" lookups (appId + rater) and a user's ratings.
                buildIndex(
                    AppRating::rater.name to Sort.Direction.ASC,
                    AppRating::appId.name to Sort.Direction.ASC,
                ),
            )
        )
    }
}
