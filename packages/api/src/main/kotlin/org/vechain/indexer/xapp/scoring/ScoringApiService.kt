package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.math.RoundingMode
import org.bson.Document
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.IndexerService
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.exception.ResourceNotFoundException

@Profile("b3tr", "xapp-scoring")
@Service
open class ScoringApiService(
    private val mongoTemplate: MongoTemplate,
    private val cycleInfoRepository: CycleInfoRepository,
) : IndexerService {

    /**
     * Returns the app's scores for the round preceding the current cycle round, read from the
     * [b3tr_app_round_scores][IndexerNames.APP_ROUND_SCORE] documents written by
     * `AppRoundScoreService`. Each dimension is a percentile in `[0, 100]`; `score` is the
     * composite `totalScore`, `rank` is the within-round competition rank persisted alongside it,
     * `distribution`/`activity`/`community`/`health` map to `distributionScore`/`activityScore`/
     * `communityScore`/`profileScore`. Apps with no document for the previous round get `null`
     * scores and `null` rank, but still report the round number and its date.
     */
    open fun getAppPreviousScore(appId: String): AppPreviousScoreResponse {
        val currentCycle =
            cycleInfoRepository.findTopByOrderByRoundIdDesc()
                ?: throw ResourceNotFoundException("No cycle info found")
        val previousRound = currentCycle.roundId - 1
        val previousCycle = cycleInfoRepository.findByRoundId(previousRound)

        val doc =
            mongoTemplate.findOne(
                Query(Criteria.where("appId").`is`(appId).and("roundId").`is`(previousRound)),
                Document::class.java,
                IndexerNames.APP_ROUND_SCORE.COLLECTION,
            )
                ?: return AppPreviousScoreResponse(
                    appId = appId,
                    round = previousRound.toLong(),
                    score = null,
                    rank = null,
                    roundDate = previousCycle?.blockTimestamp,
                    distribution = null,
                    activity = null,
                    community = null,
                    health = null,
                )

        return AppPreviousScoreResponse(
            appId = appId,
            round = previousRound.toLong(),
            score = doc.toBigDecimal("totalScore")?.rounded(),
            rank = (doc.get("rank") as? Number)?.toInt(),
            roundDate = previousCycle?.blockTimestamp,
            // Each dimension is returned as its weighted contribution to the composite Total Score
            // (0.3 × distribution, 0.3 × activity, 0.3 × community, 0.1 × profile), so the four
            // dimensions sum to `score` (totalScore).
            distribution = doc.toBigDecimal("distributionScore")?.weighted(WEIGHT_DIMENSION),
            activity = doc.toBigDecimal("activityScore")?.weighted(WEIGHT_DIMENSION),
            community = doc.toBigDecimal("communityScore")?.weighted(WEIGHT_DIMENSION),
            health = doc.toBigDecimal("profileScore")?.weighted(WEIGHT_HEALTH),
        )
    }

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()

    /**
     * Converts a stored score field to [BigDecimal]. The indexer persists these as strings (e.g.
     * `"74.4643"`), so a [String] is parsed; a missing field yields `null`.
     */
    private fun Document.toBigDecimal(key: String): BigDecimal? =
        when (val value = get(key)) {
            null -> null
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

    /** Returns [this] score rounded to [SCALE] dp for the API response. */
    private fun BigDecimal.rounded(): BigDecimal = setScale(SCALE, RoundingMode.HALF_UP)

    /** Returns [this] score scaled by [weight] and rounded to [SCALE] dp. */
    private fun BigDecimal.weighted(weight: BigDecimal): BigDecimal = multiply(weight).rounded()

    companion object {
        // Decimal places exposed by the API (product requirement: 2 dp).
        private const val SCALE = 2
        // Total Score dimension weights (VBD Reputation Layer Proposal): each returned dimension is
        // its weighted contribution to the composite `totalScore` (`score`).
        private val WEIGHT_DIMENSION = BigDecimal("0.3") // distribution / activity / community
        private val WEIGHT_HEALTH = BigDecimal("0.1") // profile (health)
    }
}

@Schema(description = "App score for the previous round")
data class AppPreviousScoreResponse(
    @Schema(description = "App identifier") val appId: String,
    @Schema(description = "Round number", example = "5") val round: Long,
    @Schema(description = "App Score (composite Total Score, 0-100)", example = "73.89")
    val score: BigDecimal?,
    @Schema(description = "Ranking", example = "3") val rank: Int?,
    @Schema(description = "Round date (Unix timestamp)", example = "1779424224")
    val roundDate: Long?,
    @Schema(description = "Distribution dimension score (weighted, 0-100)", example = "28.80")
    val distribution: BigDecimal?,
    @Schema(description = "Activity dimension score (weighted, 0-100)", example = "28.80")
    val activity: BigDecimal?,
    @Schema(description = "Community dimension score (weighted, 0-100)", example = "12.86")
    val community: BigDecimal?,
    @Schema(description = "Health (Profile) dimension score (weighted, 0-100)", example = "3.43")
    val health: BigDecimal?,
)
