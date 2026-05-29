package org.vechain.indexer.xapp.review.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.review.ReviewVoteDoc

@Profile("b3tr", "xapp-review-vote")
@Repository
interface ReviewVoteRepository : BaseIndexedRepository<ReviewVoteDoc, String> {
    @Query("{ 'reviewId': ?0 }")
    fun findAllByReviewId(reviewId: Long, pageable: Pageable): Slice<ReviewVoteDoc>

    @Query("{ 'reviewId': ?0, 'voter': ?1 }")
    fun findByReviewIdAndVoter(reviewId: Long, voter: String): ReviewVoteDoc?

    @Query("{ 'voter': ?0 }")
    fun findAllByVoter(voter: String, pageable: Pageable): Slice<ReviewVoteDoc>

    @Query(value = "{ 'reviewId': ?0 }", count = true) fun countByReviewId(reviewId: Long): Long

    @Query(value = "{ 'reviewId': ?0, 'voteType': ?1 }", count = true)
    fun countByReviewIdAndVoteType(reviewId: Long, voteType: Int): Long

    @Query("{ 'reviewId': ?0, 'voter': { '\$regex': ?1, '\$options': 'i' } }")
    fun findByReviewIdAndVoterContaining(
        reviewId: Long,
        voter: String,
        pageable: Pageable,
    ): Slice<ReviewVoteDoc>
}
