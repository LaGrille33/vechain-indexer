package org.vechain.indexer.xapp.scoring.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.scoring.ScoringRound

@Profile("b3tr", "xapp-scoring")
@Repository
interface ScoringRoundRepository : BaseIndexedRepository<ScoringRound, String> {
    fun findAllByOrderByRoundDesc(pageable: Pageable): Slice<ScoringRound>

    fun findByRound(round: Long): ScoringRound?
}
