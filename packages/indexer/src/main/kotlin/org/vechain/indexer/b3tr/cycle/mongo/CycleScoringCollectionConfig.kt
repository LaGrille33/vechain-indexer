package org.vechain.indexer.b3tr.cycle.mongo

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.b3tr.cycle.AppCycleScore
import org.vechain.indexer.config.mongo.CollectionConfig

@Configuration
@Profile("b3tr", "b3tr-cycle-scoring")
open class CycleScoringCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, AppCycleScore::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes =
                listOf(
                    buildIndex(AppCycleScore::roundId.name to Sort.Direction.DESC),
                    buildIndex(
                        AppCycleScore::appId.name to Sort.Direction.ASC,
                        AppCycleScore::roundId.name to Sort.Direction.DESC,
                    ),
                    buildIndex(
                        AppCycleScore::roundId.name to Sort.Direction.DESC,
                        AppCycleScore::total.name to Sort.Direction.DESC,
                    ),
                ),
            partialFilter = null,
        )
    }
}
