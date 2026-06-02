package org.vechain.indexer.b3tr.cycle.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.mongodb.repository.MongoRepository
import org.vechain.indexer.b3tr.cycle.AppCycleScore

interface AppCycleScoreRepository : MongoRepository<AppCycleScore, String> {
    fun findByRoundId(roundId: Int): List<AppCycleScore>

    fun findByAppIdAndRoundId(appId: String, roundId: Int): AppCycleScore?

    fun findByRoundIdOrderByTotalDesc(roundId: Int): List<AppCycleScore>

    fun findTopByRoundIdOrderByRoundIdDesc(roundId: Int): AppCycleScore?

    fun findAllByAppIdOrderByRoundIdDesc(appId: String, pageable: Pageable): Slice<AppCycleScore>

    fun countByRoundIdAndTotalGreaterThan(roundId: Int, total: Int): Int
}
