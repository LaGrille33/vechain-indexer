package org.vechain.indexer.b3tr.round.repository

import org.springframework.data.mongodb.repository.MongoRepository
import org.vechain.indexer.b3tr.round.CycleInfo

interface CycleInfoRepository : MongoRepository<CycleInfo, String> {
    fun findTopByOrderByRoundIdDesc(): CycleInfo?

    fun findByRoundId(roundId: Int): CycleInfo?

    fun findByRoundIdIn(roundIds: List<Int>): List<CycleInfo>
}
