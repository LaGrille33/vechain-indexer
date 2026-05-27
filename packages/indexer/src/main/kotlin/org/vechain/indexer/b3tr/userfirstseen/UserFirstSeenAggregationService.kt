package org.vechain.indexer.b3tr.userfirstseen

import java.time.Instant
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.action.AppRoundActionSummary
import org.vechain.indexer.utils.IdUtils.generateId

@Service
@Profile("b3tr", "b3tr-user-first-seen")
class UserFirstSeenAggregationService(private val mongoTemplate: MongoTemplate) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val WATERMARK_ID = "user_first_seen_watermark"
        private const val WATERMARK_COLLECTION = "b3tr_user_first_seen_watermark"
    }

    @Scheduled(
        fixedDelayString = "\${indexer.user-first-seen.interval-ms:3600000}",
        initialDelayString = "\${indexer.user-first-seen.initial-delay-ms:60000}",
    )
    fun aggregateFirstSeen() {
        val started = System.currentTimeMillis()
        logger.info("Starting user first_seen aggregation...")

        val sourceCollection = IndexerNames.APP_ROUND_ACTION_SUMMARY.COLLECTION
        val targetCollection = IndexerNames.USER_FIRST_SEEN.COLLECTION

        val maxRoundQuery = Query().with(Sort.by(Sort.Direction.DESC, "roundId")).limit(1)
        val latestDoc = mongoTemplate.findOne(maxRoundQuery, AppRoundActionSummary::class.java)
        if (latestDoc == null) {
            logger.info("No data in source collection {}, skipping", sourceCollection)
            return
        }
        val currentMaxRound = latestDoc.roundId

        val watermarkDoc =
            mongoTemplate.findOne(
                Query(Criteria.where("_id").`is`(WATERMARK_ID)),
                Document::class.java,
                WATERMARK_COLLECTION,
            )
        val lastProcessedRound = watermarkDoc?.getInteger("lastProcessedRoundId") ?: 0

        if (currentMaxRound <= lastProcessedRound) {
            logger.info(
                "No new rounds to process (current={}, last={})",
                currentMaxRound,
                lastProcessedRound,
            )
            return
        }

        logger.info(
            "Aggregating first_seen for rounds {}..{} (delta={})",
            lastProcessedRound + 1,
            currentMaxRound,
            currentMaxRound - lastProcessedRound,
        )

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("roundId").gt(lastProcessedRound)),
                Aggregation.group("appId", "user").min("roundId").`as`("firstRoundId"),
            )

        val results = mongoTemplate.aggregate(aggregation, sourceCollection, Document::class.java)

        val now = Instant.now().toEpochMilli()
        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, targetCollection)
        var count = 0

        results.forEach { doc ->
            val appId = (doc["_id"] as? Document)?.getString("appId") ?: return@forEach
            val user = (doc["_id"] as? Document)?.getString("user") ?: return@forEach
            val firstRoundId = doc.getInteger("firstRoundId") ?: return@forEach
            val id = generateId(appId, user)

            bulkOps.upsert(
                Query(Criteria.where("_id").`is`(id)),
                Update()
                    .setOnInsert("_id", id)
                    .setOnInsert("appId", appId)
                    .setOnInsert("user", user)
                    .setOnInsert("firstRoundId", firstRoundId)
                    .setOnInsert("updatedAt", now),
            )
            count++
        }

        if (count > 0) {
            bulkOps.execute()
            logger.info("Bulk upsert completed for {} (appId, user) pairs", count)
        }

        mongoTemplate.upsert(
            Query(Criteria.where("_id").`is`(WATERMARK_ID)),
            Update.update("lastProcessedRoundId", currentMaxRound).set("updatedAt", now),
            WATERMARK_COLLECTION,
        )

        val elapsed = System.currentTimeMillis() - started
        logger.info(
            "User first_seen aggregation completed in {}ms (watermark -> {})",
            elapsed,
            currentMaxRound,
        )
    }
}
