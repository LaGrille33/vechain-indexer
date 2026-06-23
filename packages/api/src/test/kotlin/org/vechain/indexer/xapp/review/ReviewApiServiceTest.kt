package org.vechain.indexer.xapp.review

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.vechain.indexer.xapp.review.repository.ReviewRepository
import org.vechain.indexer.xapp.review.repository.ReviewVoteRepository
import strikt.api.expectThat
import strikt.assertions.isEmpty

internal class ReviewApiServiceTest {
    private val reviewRepository: ReviewRepository = mockk()
    private val reviewVoteRepository: ReviewVoteRepository = mockk(relaxed = true)
    private val mongoTemplate =
        mockk<org.springframework.data.mongodb.core.MongoTemplate>(relaxed = true)

    private val appId = "0xapp"
    private val viewer = "0xviewer"
    private val author = "0xauthor"
    private val pageable = PageRequest.of(0, 10)

    private fun emptySlice() = PageImpl<Review>(emptyList(), pageable, 0)

    private fun service(visibilityResolver: ReviewHiddenVisibilityResolver? = null) =
        ReviewApiService(
            reviewRepository,
            reviewVoteRepository,
            mongoTemplate,
            domainResolver = null,
            visibilityResolver = visibilityResolver,
        )

    @Test
    fun `admin without author filter uses the unfiltered hidden query and count`() {
        val resolver =
            mockk<ReviewHiddenVisibilityResolver> {
                every { canSeeHiddenReviews(appId, viewer) } returns true
            }
        every { reviewRepository.findAllByAppIdIncludingHidden(appId, any()) } returns emptySlice()
        every { reviewRepository.countByAppId(appId) } returns 7L

        val result =
            service(resolver)
                .getAppReviews(
                    appId,
                    author = null,
                    viewer = viewer,
                    sortBy = null,
                    pageable = pageable,
                )

        verify(exactly = 1) { reviewRepository.findAllByAppIdIncludingHidden(appId, any()) }
        verify(exactly = 1) { reviewRepository.countByAppId(appId) }
        expectThat(result.data).isEmpty()
    }

    @Test
    fun `admin with author filter reuses the author query and count`() {
        val resolver =
            mockk<ReviewHiddenVisibilityResolver> {
                every { canSeeHiddenReviews(appId, viewer) } returns true
            }
        every { reviewRepository.findAllByAppIdAndAuthor(appId, author, any()) } returns
            emptySlice()
        every { reviewRepository.countByAppIdAndAuthor(appId, author) } returns 3L

        service(resolver)
            .getAppReviews(
                appId,
                author = author,
                viewer = viewer,
                sortBy = null,
                pageable = pageable,
            )

        verify(exactly = 1) { reviewRepository.findAllByAppIdAndAuthor(appId, author, any()) }
        verify(exactly = 1) { reviewRepository.countByAppIdAndAuthor(appId, author) }
    }

    @Test
    fun `non-admin viewer keeps the existing viewer-hidden query`() {
        val resolver =
            mockk<ReviewHiddenVisibilityResolver> {
                every { canSeeHiddenReviews(appId, viewer) } returns false
            }
        every { reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, any()) } returns
            emptySlice()
        every { reviewRepository.countByAppIdWithViewerHidden(appId, viewer) } returns 2L

        service(resolver)
            .getAppReviews(
                appId,
                author = null,
                viewer = viewer,
                sortBy = null,
                pageable = pageable,
            )

        verify(exactly = 1) {
            reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, any())
        }
        verify(exactly = 1) { reviewRepository.countByAppIdWithViewerHidden(appId, viewer) }
    }

    @Test
    fun `null resolver degrades to the existing viewer-hidden query`() {
        every { reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, any()) } returns
            emptySlice()
        every { reviewRepository.countByAppIdWithViewerHidden(appId, viewer) } returns 1L

        service(visibilityResolver = null)
            .getAppReviews(
                appId,
                author = null,
                viewer = viewer,
                sortBy = null,
                pageable = pageable,
            )

        verify(exactly = 1) {
            reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, any())
        }
    }
}
