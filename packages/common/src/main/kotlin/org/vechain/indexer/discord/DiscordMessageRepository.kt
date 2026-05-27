package org.vechain.indexer.discord

import org.springframework.data.mongodb.repository.MongoRepository

interface DiscordMessageRepository : MongoRepository<DiscordMessage, String> {
    fun existsBySnowflakeId(snowflakeId: String): Boolean
}
