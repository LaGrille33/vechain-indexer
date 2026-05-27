package org.vechain.indexer.b3tr.onchain.repository

import org.springframework.data.mongodb.repository.MongoRepository
import org.vechain.indexer.b3tr.onchain.EcosystemRoundSnapshot

interface EcosystemRoundSnapshotRepository : MongoRepository<EcosystemRoundSnapshot, String> {
    fun findByRoundId(roundId: Int): EcosystemRoundSnapshot?

    fun findTopByOrderByRoundIdDesc(): EcosystemRoundSnapshot?
}
