package org.vechain.indexer.b3tr.userfirstseen.mongo

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.b3tr.userfirstseen.UserFirstSeen
import org.vechain.indexer.config.mongo.CollectionConfig

@Configuration
@Profile("b3tr", "b3tr-user-first-seen")
open class UserFirstSeenCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, UserFirstSeen::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes =
                listOf(
                    buildUniqueIndex(
                        UserFirstSeen::appId.name to Sort.Direction.ASC,
                        UserFirstSeen::user.name to Sort.Direction.ASC,
                    ),
                    buildIndex(
                        UserFirstSeen::appId.name to Sort.Direction.ASC,
                        UserFirstSeen::firstRoundId.name to Sort.Direction.ASC,
                    ),
                ),
            partialFilter = null,
        )
    }
}
