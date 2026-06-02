package org.vechain.indexer.xapp.scoring

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.b3tr.cycle.repository.AppCycleScoreRepository
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository
import org.vechain.indexer.exception.ResourceNotFoundException
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.rest.paginatedResponse
import org.vechain.indexer.xapp.scoring.repository.ScoringRoundRepository

@Profile("b3tr", "xapp-scoring")
@Service
open class ScoringApiService(
    private val repository: ScoringRoundRepository,
    private val appCycleScoreRepository: AppCycleScoreRepository,
    private val cycleInfoRepository: CycleInfoRepository,
) : IndexerService {

    open fun getRounds(pageable: Pageable): PaginatedResponse<ScoringRound> {
        val results = repository.findAllByOrderByRoundDesc(pageable)
        return paginatedResponse(results)
    }

    open fun getRound(round: Long): ScoringRound =
        repository.findByIdOrNull(round.toString())
            ?: throw ResourceNotFoundException("Scoring round not found for round $round")

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
    @Schema(description = "Round date", example = "1779424224") val roundDate: Long?,
)
