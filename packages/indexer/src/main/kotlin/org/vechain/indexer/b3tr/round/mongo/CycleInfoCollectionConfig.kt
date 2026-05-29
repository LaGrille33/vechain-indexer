package org.vechain.indexer.b3tr.round.mongo

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.b3tr.round.CycleInfo
import org.vechain.indexer.config.mongo.CollectionConfig

@Configuration
@Profile("b3tr", "b3tr-cycle-info")
open class CycleInfoCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, CycleInfo::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes = listOf(buildIndex(CycleInfo::roundId.name to Sort.Direction.ASC)),
            partialFilter = null,
        )
    }
}
