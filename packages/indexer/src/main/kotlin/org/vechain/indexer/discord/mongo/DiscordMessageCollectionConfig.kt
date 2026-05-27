package org.vechain.indexer.discord.mongo

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.vechain.indexer.config.mongo.CollectionConfig
import org.vechain.indexer.discord.DiscordMessage

@Configuration
@Profile("b3tr", "discord-fetcher")
open class DiscordMessageCollectionConfig(
    mongoTemplate: MongoTemplate,
    appCoroutineScope: CoroutineScope,
) : CollectionConfig(mongoTemplate, appCoroutineScope, DiscordMessage::class.java) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun initCollection() {
        this.ensureCollection()
        logger.debug("Initializing indexes for ${modelObj.simpleName}")
        ensureIndexes(
            indexes =
                listOf(
                    buildIndex(DiscordMessage::channelId.name to Sort.Direction.ASC),
                    buildIndex(DiscordMessage::channelName.name to Sort.Direction.ASC),
                    buildIndex(
                        DiscordMessage::channelId.name to Sort.Direction.ASC,
                        DiscordMessage::timestamp.name to Sort.Direction.DESC,
                    ),
                    buildIndex(DiscordMessage::authorId.name to Sort.Direction.ASC),
                    buildIndex(DiscordMessage::timestamp.name to Sort.Direction.DESC),
                ),
            partialFilter = null,
        )
    }
}
