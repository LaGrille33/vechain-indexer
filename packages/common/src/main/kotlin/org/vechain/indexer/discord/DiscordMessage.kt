package org.vechain.indexer.discord

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames

@Document(collection = IndexerNames.DISCORD_MESSAGE.COLLECTION)
data class DiscordMessage(
    @Id val id: String,
    val channelId: String,
    val channelName: String,
    val authorId: String?,
    val authorName: String,
    val isBot: Boolean,
    val text: String,
    val snowflakeId: String,
    val timestamp: Long,
    val fetchedAt: Long,
)
