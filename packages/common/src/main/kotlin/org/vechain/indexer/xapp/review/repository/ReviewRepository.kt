package org.vechain.indexer.xapp.review.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.review.Review

@Profile("b3tr", "xapp-review")
@Repository
interface ReviewRepository : BaseIndexedRepository<Review, String> {
    @Query("{ 'appId': ?0, 'isHidden': false }")
    fun findAllByAppId(appId: String, pageable: Pageable): Slice<Review>

    @Query("{ 'appId': ?0, 'isHidden': false }", sort = "{'blockTimestamp': -1}")
    fun findLatestByAppId(appId: String, pageable: Pageable): Slice<Review>

    @Query("{ 'author': ?0 }")
    fun findAllByAuthor(author: String, pageable: Pageable): Slice<Review>

    @Query("{ 'appId': ?0, 'author': ?1 }")
    fun findAllByAppIdAndAuthor(appId: String, author: String, pageable: Pageable): Slice<Review>
}
