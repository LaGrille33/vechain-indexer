package org.vechain.indexer.xapp.metadata.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.metadata.AppMetadata

@Profile("b3tr", "xapp-metadata")
@Repository
interface AppMetadataRepository : BaseIndexedRepository<AppMetadata, String> {
    @Query("{ 'appId': ?0 }") fun findByAppId(appId: String): AppMetadata?
}
