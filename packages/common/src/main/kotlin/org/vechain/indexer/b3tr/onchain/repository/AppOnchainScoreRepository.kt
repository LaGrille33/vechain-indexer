package org.vechain.indexer.b3tr.onchain.repository

import org.springframework.data.mongodb.repository.MongoRepository
import org.vechain.indexer.b3tr.onchain.AppOnchainScore

interface AppOnchainScoreRepository : MongoRepository<AppOnchainScore, String> {
    fun findByRoundId(roundId: Int): List<AppOnchainScore>

    fun findByAppIdAndRoundId(appId: String, roundId: Int): AppOnchainScore?

    fun findByRoundIdOrderByAllocB3trDesc(roundId: Int): List<AppOnchainScore>

    fun findTopByRoundIdOrderByRoundIdDesc(roundId: Int): AppOnchainScore?
}
