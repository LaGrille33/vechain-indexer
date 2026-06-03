package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.b3tr.cycle.repository.AppCycleScoreRepository
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.exception.ResourceNotFoundException

@Profile("b3tr", "xapp-scoring")
@Service
open class ScoringApiService(
    private val appCycleScoreRepository: AppCycleScoreRepository,
    private val cycleInfoRepository: CycleInfoRepository,
) : IndexerService {

    open fun getAppPreviousScore(appId: String): AppPreviousScoreResponse {
        val currentCycle =
            cycleInfoRepository.findTopByOrderByRoundIdDesc()
                ?: throw ResourceNotFoundException("No cycle info found")
        val previousRound = currentCycle.roundId - 1
        val previousCycle = cycleInfoRepository.findByRoundId(previousRound)
        val score =
            appCycleScoreRepository.findByAppIdAndRoundId(appId, previousRound)
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
        val rank =
            appCycleScoreRepository.countByRoundIdAndTotalGreaterThan(score.roundId, score.total) +
                1
        return AppPreviousScoreResponse(
            appId = appId,
            round = previousRound.toLong(),
            score = score.total,
            rank = rank,
            roundDate = previousCycle?.blockTimestamp,
            distribution = score.breakdownDistribution,
            activity = score.breakdownActivity,
            community = score.breakdownCommunityTeam,
            health = score.breakdownHealth,
        )
    }

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}

@Schema(description = "App score for the previous round")
data class AppPreviousScoreResponse(
    @Schema(description = "App identifier") val appId: String,
    @Schema(description = "Round number", example = "5") val round: Long,
    @Schema(description = "App Score", example = "85") val score: Int?,
    @Schema(description = "Ranking", example = "3") val rank: Int?,
    @Schema(description = "Round date (Unix timestamp)", example = "1779424224")
    val roundDate: Long?,
    @Schema(description = "Distribution dimension score (0-20)", example = "15")
    val distribution: Int?,
    @Schema(description = "Activity dimension score (0-20)", example = "12") val activity: Int?,
    @Schema(description = "Community dimension score (0-20)", example = "18") val community: Int?,
    @Schema(description = "Health dimension score (0-20)", example = "14") val health: Int?,
)
