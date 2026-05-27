package org.vechain.indexer.b3tr.userfirstseen

import org.springframework.data.mongodb.repository.MongoRepository

interface UserFirstSeenRepository : MongoRepository<UserFirstSeen, String> {
    fun countByAppId(appId: String): Long
}
