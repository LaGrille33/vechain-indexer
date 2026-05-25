package org.vechain.indexer.xapp.scoring

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.exception.ResourceNotFoundException
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.rest.paginatedResponse
import org.vechain.indexer.xapp.scoring.repository.ScoringRoundRepository

@Profile("b3tr", "xapp-scoring")
@Service
open class ScoringApiService(private val repository: ScoringRoundRepository) : IndexerService {

    open fun getRounds(pageable: Pageable): PaginatedResponse<ScoringRound> {
        val results = repository.findAllByOrderByRoundDesc(pageable)
        return paginatedResponse(results)
    }

    open fun getRound(round: Long): ScoringRound =
        repository.findByIdOrNull(round.toString())
            ?: throw ResourceNotFoundException("Scoring round not found for round $round")

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}
