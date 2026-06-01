package org.vechain.indexer.xapp.review

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

@Profile("b3tr", "xapp-review-vote")
@Configuration
open class ReviewVoteCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
    private val indexerVersionService: IndexerVersionService,
) : CollectionConfig(mongoTemplate, appCoroutineScope, ReviewVoteDoc::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Value("\${indexer.version.xapp-review-vote}") private val version: Int = 1

    override fun initCollection() {
        logger.debug("Check collection version for ${modelObj.simpleName}")
        indexerVersionService.checkAndResetCollectionIfVersionChanged(
            IndexerNames.XAPP_REVIEW_VOTE.NAME,
            ReviewVoteDoc::class.java,
            version,
        )
        ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            listOf(
                buildIndex(IndexedDocument::blockNumber.name to Sort.Direction.DESC),
                // Serves per-review listing and aggregation.
                buildIndex(ReviewVoteDoc::reviewId.name to Sort.Direction.ASC),
                // Serves per-voter lookups and search.
                buildIndex(
                    ReviewVoteDoc::voter.name to Sort.Direction.ASC,
                    ReviewVoteDoc::reviewId.name to Sort.Direction.ASC,
                ),
            )
        )
    }
}
