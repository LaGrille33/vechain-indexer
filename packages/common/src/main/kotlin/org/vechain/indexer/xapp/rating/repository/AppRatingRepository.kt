package org.vechain.indexer.xapp.rating.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.rating.AppRating

@Profile("b3tr", "xapp-rating")
@Repository
interface AppRatingRepository : BaseIndexedRepository<AppRating, String> {
    @Query("{ 'appId': ?0 }")
    fun findAllByAppId(appId: String, pageable: Pageable): Slice<AppRating>

    @Query("{ 'appId': ?0, 'rater': ?1 }")
    fun findByAppIdAndRater(appId: String, rater: String): AppRating?

    @Query("{ 'rater': ?0 }")
    fun findAllByRater(rater: String, pageable: Pageable): Slice<AppRating>
}
