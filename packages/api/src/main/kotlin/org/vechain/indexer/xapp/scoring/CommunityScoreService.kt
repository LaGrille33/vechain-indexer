package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.exp
import org.bson.Document
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames

@Schema(description = "Community Score result for an app")
data class CommunityScoreResult(
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(description = "Average star rating (1-5)", example = "4.3") val avgRating: Double,
    @Schema(description = "Total number of ratings", example = "128") val numRatings: Long,
    @Schema(description = "Total helpful votes (review upvotes)", example = "45")
    val helpfulVotes: Long,
    @Schema(description = "Total unhelpful votes (review downvotes)", example = "12")
    val unhelpfulVotes: Long,
    @Schema(description = "Confidence factor: 1 - e^(-numRatings/K)", example = "0.723")
    val confidenceFactor: Double,
    @Schema(description = "Helpful ratio: helpfulVotes / (helpfulVotes + unhelpfulVotes)")
    val helpfulRatio: Double,
    @Schema(description = "Engagement factor: 0.5 + 0.5 * helpfulRatio", example = "0.894")
    val engagementFactor: Double,
    @Schema(description = "Adjusted rating: avgRating * confidenceFactor * engagementFactor")
    val adjustedRating: Double,
    @Schema(description = "Community Score: percentile rank (0-100)", example = "85.5")
    val communityScore: Double,
)

@Profile("b3tr")
@Service
open class CommunityScoreService(private val mongoTemplate: MongoTemplate) {

    companion object {
        private const val R_MIN = 20 // Minimum ratings to be eligible
        private const val K = 100.0 // Smoothing constant for ConfidenceFactor
    }

    /**
     * Compute Community Score for a specific app. Other services call this method. Returns null if
     * the app has no rating data.
     */
    open fun computeCommunityScore(appId: String): CommunityScoreResult? {
        return computeCommunityScores().find { it.appId == appId }
    }

    /**
     * Compute Community Scores for all apps that have rating data. Returns results sorted by
     * communityScore descending. This is a pure calculation based on current rating/review state —
     * no dependency on cycle scores or rounds.
     */
    open fun computeCommunityScores(): List<CommunityScoreResult> {
        val ratingStats = aggregateRatingStats()
        if (ratingStats.isEmpty()) return emptyList()

        val voteStats = aggregateVoteStats()

        // Compute adjusted rating for each app with rating data
        val appDataList = mutableListOf<AppData>()
        for (rs in ratingStats.values) {
            val vs = voteStats[rs.appId]
            val data =
                AppData(
                    appId = rs.appId,
                    avgRating = rs.avgRating,
                    numRatings = rs.numRatings,
                    helpfulVotes = vs?.helpfulVotes ?: 0L,
                    unhelpfulVotes = vs?.unhelpfulVotes ?: 0L,
                )
            data.adjustedRating = computeAdjustedRating(data)
            appDataList.add(data)
        }

        // Compute percentile rank across all apps
        val allAdjustedRatings = appDataList.map { it.adjustedRating }
        for (data in appDataList) {
            data.communityScore =
                if (data.numRatings < R_MIN) 0.0
                else percentileRank(data.adjustedRating, allAdjustedRatings)
        }

        return appDataList
            .sortedByDescending { it.communityScore }
            .map { data ->
                val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
                CommunityScoreResult(
                    appId = data.appId,
                    avgRating = data.avgRating,
                    numRatings = data.numRatings,
                    helpfulVotes = data.helpfulVotes,
                    unhelpfulVotes = data.unhelpfulVotes,
                    confidenceFactor = computeConfidenceFactor(data.numRatings),
                    helpfulRatio = helpfulRatio,
                    engagementFactor = computeEngagementFactor(helpfulRatio),
                    adjustedRating = data.adjustedRating,
                    communityScore = data.communityScore,
                )
            }
    }

    // ── Data aggregation ──────────────────────────────────────────────────

    private data class AppRatingStats(
        val appId: String,
        val avgRating: Double,
        val numRatings: Long,
    )

    private data class AppVoteStats(
        val appId: String,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
    )

    private class AppData(
        val appId: String,
        val avgRating: Double,
        val numRatings: Long,
        val helpfulVotes: Long,
        val unhelpfulVotes: Long,
        var adjustedRating: Double = 0.0,
        var communityScore: Double = 0.0,
    )

    private fun aggregateRatingStats(): Map<String, AppRatingStats> {
        val collection = IndexerNames.XAPP_RATING.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("_id").ne(null)),
                Aggregation.group("appId").count().`as`("count").avg("rating").`as`("average"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppRatingStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String) ?: return@forEach
            val count = toLong(doc.get("count"))
            val average = (doc.get("average") as? Number)?.toDouble() ?: 0.0
            map[appId] = AppRatingStats(appId = appId, avgRating = average, numRatings = count)
        }
        return map
    }

    private fun aggregateVoteStats(): Map<String, AppVoteStats> {
        val collection = IndexerNames.XAPP_REVIEW_VOTE.COLLECTION
        if (!mongoTemplate.collectionExists(collection)) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                // Join votes with reviews to get appId and check isHidden
                Aggregation.lookup(
                    IndexerNames.XAPP_REVIEW.COLLECTION,
                    "reviewId",
                    "reviewId",
                    "review",
                ),
                Aggregation.unwind("review"),
                Aggregation.match(Criteria.where("review.isHidden").`is`(false)),
                // voteType 1 = upvote (helpful), 2 = downvote (unhelpful)
                Aggregation.group("review.appId")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(1))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("helpfulVotes")
                    .sum(
                        ConditionalOperators.`when`(Criteria.where("voteType").`is`(2))
                            .then(1)
                            .otherwise(0)
                    )
                    .`as`("unhelpfulVotes"),
            )

        val results = mongoTemplate.aggregate(aggregation, collection, Document::class.java)
        val map = mutableMapOf<String, AppVoteStats>()
        results.forEach { doc ->
            val appId = (doc["_id"] as? String) ?: return@forEach
            val helpful = toLong(doc.get("helpfulVotes"))
            val unhelpful = toLong(doc.get("unhelpfulVotes"))
            map[appId] =
                AppVoteStats(appId = appId, helpfulVotes = helpful, unhelpfulVotes = unhelpful)
        }
        return map
    }

    // ── Formula components ────────────────────────────────────────────────

    /** ConfidenceFactor = 1 - e^(-NumRatings / K) */
    private fun computeConfidenceFactor(numRatings: Long): Double =
        1.0 - exp(-numRatings.toDouble() / K)

    /** HelpfulRatio = helpfulVotes / (helpfulVotes + unhelpfulVotes), default 0.5 if no votes. */
    private fun computeHelpfulRatio(helpfulVotes: Long, unhelpfulVotes: Long): Double {
        val total = helpfulVotes + unhelpfulVotes
        return if (total == 0L) 0.5 else helpfulVotes.toDouble() / total.toDouble()
    }

    /** EngagementFactor = 0.5 + 0.5 * HelpfulRatio, range [0.5, 1.0] */
    private fun computeEngagementFactor(helpfulRatio: Double): Double = 0.5 + 0.5 * helpfulRatio

    /** AdjustedRating = AvgRating * ConfidenceFactor * EngagementFactor */
    private fun computeAdjustedRating(data: AppData): Double {
        if (data.numRatings < R_MIN) return 0.0
        val confidenceFactor = computeConfidenceFactor(data.numRatings)
        val helpfulRatio = computeHelpfulRatio(data.helpfulVotes, data.unhelpfulVotes)
        val engagementFactor = computeEngagementFactor(helpfulRatio)
        return data.avgRating * confidenceFactor * engagementFactor
    }

    /** Percentile rank: P(x) = (below + 0.5 * ties) / N * 100 */
    private fun percentileRank(value: Double, allValues: List<Double>): Double {
        val n = allValues.size
        if (n == 0) return 0.0
        val below = allValues.count { it < value }
        val tied = allValues.count { it == value }
        return (below + 0.5 * tied) / n * 100.0
    }
}

private fun toLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }
