package org.vechain.indexer.b3tr.userfirstseen

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.utils.IdUtils.generateId

@Document(collection = IndexerNames.USER_FIRST_SEEN.COLLECTION)
data class UserFirstSeen(
    @Id val id: String,
    val appId: String,
    val user: String,
    val firstRoundId: Int,
    val updatedAt: Long,
) {
    constructor(
        appId: String,
        user: String,
        firstRoundId: Int,
        updatedAt: Long,
    ) : this(
        id = generateId(appId, user),
        appId = appId,
        user = user,
        firstRoundId = firstRoundId,
        updatedAt = updatedAt,
    )
}
