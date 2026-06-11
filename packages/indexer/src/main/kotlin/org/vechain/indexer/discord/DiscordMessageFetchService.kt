package org.vechain.indexer.discord

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames

@Service
@Profile("b3tr", "discord-fetcher")
class DiscordMessageFetchService(
    private val mongoTemplate: MongoTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${indexer.discord.bot-token:}") private val botToken: String,
    @Value("\${indexer.discord.channels:}") private val channelsConfig: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val DISCORD_API = "https://discord.com/api/v10"
        private const val DISCORD_EPOCH = 1420070400000L
        private const val WATERMARK_ID = "discord_fetch_watermark"
        private const val WATERMARK_COLLECTION = "discord_fetch_watermark"
        private const val PAGE_LIMIT = 100
        private const val MAX_RETRIES = 5
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)).build()
    }

    data class ChannelConfig(val name: String, val id: String)

    @Scheduled(
        fixedDelayString = "\${indexer.discord.interval-ms:3600000}",
        initialDelayString = "\${indexer.discord.initial-delay-ms:60000}",
    )
    fun fetchMessages() {
        if (botToken.isBlank()) {
            logger.debug("Discord bot token not configured, skipping")
            return
        }

        val channels = parseChannels()
        if (channels.isEmpty()) {
            logger.debug("No Discord channels configured, skipping")
            return
        }

        val started = System.currentTimeMillis()
        logger.info("Starting Discord message fetch for {} channels...", channels.size)

        val watermarkDoc =
            mongoTemplate.findOne(
                Query(Criteria.where("_id").`is`(WATERMARK_ID)),
                Document::class.java,
                WATERMARK_COLLECTION,
            )

        val lastSnowflakes = mutableMapOf<String, String>()
        if (watermarkDoc != null) {
            val stored = watermarkDoc.get("lastSnowflakes") as? Document
            stored?.forEach { k, v -> lastSnowflakes[k] = v.toString() }
        }

        val targetCollection = IndexerNames.DISCORD_MESSAGE.COLLECTION
        var totalFetched = 0
        var totalAppended = 0

        for (ch in channels) {
            val lastSf = lastSnowflakes[ch.id]?.toLongOrNull()
            logger.info(
                "  > #{} since={}",
                ch.name,
                if (lastSf != null) snowflakeToTimestamp(lastSf).toString() else "-",
            )

            val msgs = fetchAfter(ch.id, lastSf)
            totalFetched += msgs.size

            val appended = persistMessages(targetCollection, ch, msgs)
            totalAppended += appended

            if (msgs.isNotEmpty()) {
                val newHigh = msgs.maxOf { it["id"].asLong() }
                lastSnowflakes[ch.id] = newHigh.toString()
            }

            val cursorSf = lastSnowflakes[ch.id]?.toLongOrNull()
            logger.info(
                "    fetched={:>4}  appended={:>4}  cursor->{}",
                msgs.size,
                appended,
                if (cursorSf != null) snowflakeToTimestamp(cursorSf).toString() else "-",
            )
        }

        val now = System.currentTimeMillis()
        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update()
                .set("lastSnowflakes", lastSnowflakes)
                .set("fetchedAt", Instant.ofEpochMilli(now).toString())
                .set("channelCount", channels.size)
                .set("updatedAt", now),
            WATERMARK_COLLECTION,
        )

        val elapsed = System.currentTimeMillis() - started
        logger.info(
            "Discord fetch completed: {} fetched, {} new across {} channels in {}ms",
            totalFetched,
            totalAppended,
            channels.size,
            elapsed,
        )
    }

    private fun parseChannels(): List<ChannelConfig> {
        if (channelsConfig.isBlank()) return emptyList()
        return channelsConfig
            .split(",")
            .map { it.trim() }
            .filter { it.contains(":") }
            .map { entry ->
                val parts = entry.split(":", limit = 2)
                ChannelConfig(name = parts[0].trim(), id = parts[1].trim())
            }
    }

    private fun fetchAfter(channelId: String, afterSnowflake: Long?): List<JsonNode> {
        var cursor = afterSnowflake ?: 1L
        val out = mutableListOf<JsonNode>()
        var retries = 0

        while (true) {
            try {
                val uri =
                    "$DISCORD_API/channels/$channelId/messages?limit=$PAGE_LIMIT&after=$cursor"
                val request =
                    HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .header("Authorization", "Bot $botToken")
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    throw RuntimeException(
                        "Discord API returned ${response.statusCode()}: ${response.body().take(200)}"
                    )
                }

                val batch = objectMapper.readTree(response.body())
                if (batch == null || !batch.isArray || batch.isEmpty) break

                val sorted = batch.toList().sortedBy { it["id"].asLong() }
                out.addAll(sorted)
                cursor = sorted.maxOf { it["id"].asLong() }

                if (batch.size() < PAGE_LIMIT) break

                Thread.sleep(250)
            } catch (e: Exception) {
                retries++
                if (retries > MAX_RETRIES) {
                    logger.warn(
                        "Channel {}: failed after {} retries: {}",
                        channelId,
                        MAX_RETRIES,
                        e.message,
                    )
                    break
                }
                val wait = (1L shl retries) * 1000L
                logger.info("  [retry {}] {} -- waiting {}s", retries, e.message, wait / 1000)
                Thread.sleep(wait)
            }
        }

        return out
    }

    private fun persistMessages(
        targetCollection: String,
        channel: ChannelConfig,
        msgs: List<JsonNode>,
    ): Int {
        if (msgs.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, targetCollection)
        var count = 0

        for (msg in msgs) {
            val snowflakeId = msg["id"]?.asText() ?: continue
            val content = msg["content"]?.asText()?.trim() ?: ""
            if (content.isEmpty()) continue

            val author = msg["author"] ?: continue
            val authorId = author["id"]?.asText()
            val display =
                author["global_name"]?.asText() ?: author["username"]?.asText() ?: "Unknown"
            val isBot = author["bot"]?.asBoolean() ?: false
            val sfLong = snowflakeId.toLongOrNull() ?: continue
            val ts = snowflakeToTimestamp(sfLong)

            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(snowflakeId)),
                Update()
                    .setOnInsert("_id", snowflakeId)
                    .set("channelId", channel.id)
                    .set("channelName", channel.name)
                    .set("authorId", authorId)
                    .set("authorName", display)
                    .set("isBot", isBot)
                    .set("text", content)
                    .set("snowflakeId", snowflakeId)
                    .set("timestamp", ts.toEpochMilli())
                    .set("fetchedAt", now),
            )
            count++
        }

        if (count > 0) {
            bulkOps.execute()
        }
        return count
    }

    private fun snowflakeToTimestamp(snowflake: Long): Instant =
        Instant.ofEpochMilli((snowflake shr 22) + DISCORD_EPOCH)
}
