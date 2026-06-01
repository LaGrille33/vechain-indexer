package org.vechain.indexer.xapp.rating

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.math.RoundingMode
import org.bson.Document
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.IndexerService
import org.vechain.indexer.xapp.rating.repository.AppRatingRepository

@Schema(description = "Aggregated rating summary for an app")
data class AppRatingSummary(
    @Schema(description = "App ID (bytes32 hex)", example = "0x6d25...77b4") val appId: String,
    @Schema(description = "Average rating, rounded to one decimal place", example = "4.7")
    val average: Double,
    @Schema(description = "Number of users who rated this app", example = "128") val count: Long,
    @Schema(description = "Current user's rating (null if wallet not provided or not rated)")
    val userRating: Int? = null,
    @Schema(description = "Whether the current user has rated this app", example = "true")
    val hasRated: Boolean = false,
)

@Profile("b3tr", "xapp-rating", "xapp-review-vote")
@Service
open class RatingApiService(
    private val repository: AppRatingRepository,
    private val mongoTemplate: MongoTemplate,
) : IndexerService {

    /** Average rating (1 decimal) and rater count for an app, computed in a single aggregation. */
    open fun getAppRatingSummary(appId: String, wallet: String? = null): AppRatingSummary {
        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(
                    org.springframework.data.mongodb.core.query.Criteria.where(
                            AppRating::appId.name
                        )
                        .`is`(appId)
                ),
                Aggregation.group()
                    .count()
                    .`as`("count")
                    .avg(AppRating::rating.name)
                    .`as`("average"),
            )
        val result =
            mongoTemplate
                .aggregate(aggregation, IndexerNames.XAPP_RATING.COLLECTION, Document::class.java)
                .uniqueMappedResult

        val count = (result?.get("count") as? Number)?.toLong() ?: 0L
        val rawAverage = (result?.get("average") as? Number)?.toDouble() ?: 0.0
        val average = BigDecimal(rawAverage).setScale(1, RoundingMode.HALF_UP).toDouble()

        val userRatingResult =
            if (wallet != null) {
                repository.findByAppIdAndRater(appId, wallet)
            } else {
                null
            }

        return AppRatingSummary(
            appId = appId,
            average = average,
            count = count,
            userRating = userRatingResult?.rating,
            hasRated = userRatingResult != null,
        )
    }

    /** The current rating a specific wallet gave an app, or null if they have not rated it. */
    open fun getUserRating(appId: String, rater: String): AppRating? =
        repository.findByAppIdAndRater(appId, rater)

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}
