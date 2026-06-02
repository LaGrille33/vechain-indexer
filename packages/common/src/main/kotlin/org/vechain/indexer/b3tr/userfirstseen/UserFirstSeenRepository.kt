package org.vechain.indexer.b3tr.userfirstseen

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query

interface UserFirstSeenRepository : MongoRepository<UserFirstSeen, String> {
    fun countByAppId(appId: String): Long

    @Cacheable(
        value = ["user_first_seen_countByAppIdAndFirstRoundId"],
        key = "#appId + '-' + #firstRoundId",
    )
    @Query(value = "{ 'appId': ?0, 'firstRoundId': ?1 }", count = true)
    fun countByAppIdAndFirstRoundId(appId: String, firstRoundId: Int): Long
}
