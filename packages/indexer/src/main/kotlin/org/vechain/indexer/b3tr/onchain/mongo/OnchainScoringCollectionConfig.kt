package org.vechain.indexer.b3tr.onchain.mongo

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.b3tr.onchain.AppOnchainScore
import org.vechain.indexer.b3tr.onchain.EcosystemRoundSnapshot
import org.vechain.indexer.config.mongo.CollectionConfig

@Configuration
@Profile("b3tr", "b3tr-onchain-scoring")
open class OnchainScoringCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, AppOnchainScore::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes =
                listOf(
                    buildIndex(AppOnchainScore::roundId.name to Sort.Direction.DESC),
                    buildIndex(
                        AppOnchainScore::appId.name to Sort.Direction.ASC,
                        AppOnchainScore::roundId.name to Sort.Direction.DESC,
                    ),
                    buildIndex(
                        AppOnchainScore::roundId.name to Sort.Direction.DESC,
                        AppOnchainScore::allocB3tr.name to Sort.Direction.DESC,
                    ),
                ),
            partialFilter = null,
        )
    }
}

@Configuration
@Profile("b3tr", "b3tr-onchain-scoring")
open class EcosystemRoundSnapshotCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, EcosystemRoundSnapshot::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes =
                listOf(buildIndex(EcosystemRoundSnapshot::roundId.name to Sort.Direction.DESC)),
            partialFilter = null,
        )
    }
}
