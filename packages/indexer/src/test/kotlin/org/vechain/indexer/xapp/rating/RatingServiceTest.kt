package org.vechain.indexer.xapp.rating

import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.vechain.indexer.config.InlineVersioningProperties
import org.vechain.indexer.event.model.generic.IndexedEvent
import org.vechain.indexer.xapp.rating.repository.AppRatingRepository

class RatingServiceTest {

    private val repository = mockk<AppRatingRepository>(relaxed = true)
    private val mongoTemplate =
        mockk<org.springframework.data.mongodb.core.MongoTemplate>(relaxed = true)
    private val inlineVersioningProperties = mockk<InlineVersioningProperties>(relaxed = true)

    @BeforeEach
    fun setup() {
        // No pre-existing ratings: keep the accumulator from casting relaxed-mock junk.
        every { repository.findAllById(any<Iterable<String>>()) } returns emptyList()
        every { repository.findById(any<String>()) } returns Optional.empty()
    }

    private fun service() =
        RatingService(
            repository = repository,
            mongoTemplate = mongoTemplate,
            inlineVersioningProperties = inlineVersioningProperties,
        )

    private fun event(type: String, appId: String, rater: String, rating: Int): IndexedEvent {
        val event = mockk<IndexedEvent>(relaxed = true)
        every { event.eventType } returns type
        every { event.blockId } returns "0xblock"
        every { event.blockNumber } returns 100L
        every { event.blockTimestamp } returns 200L
        every { event.params.getReturnValues() } returns
            mapOf("appId" to appId, "rater" to rater, "rating" to rating)
        return event
    }

    @Test
    fun `RatingSubmitted produces an upsertable rating keyed by appId and rater`() {
        val appId = "0x" + "11".repeat(32)
        val rater = "0x" + "22".repeat(20)

        val (updated, _) =
            runBlocking {
                service().processEvents(listOf(event("RatingSubmitted", appId, rater, 4)))
            }

        assertEquals(1, updated.size)
        assertEquals(appId.lowercase(), updated[0].appId.lowercase())
        assertEquals(rater.lowercase(), updated[0].rater.lowercase())
        assertEquals(4, updated[0].rating)
        assertEquals(
            AppRating.documentId(updated[0].appId, updated[0].rater),
            updated[0].getDocumentId(),
        )
    }

    @Test
    fun `non-rating events are ignored`() {
        val appId = "0x" + "11".repeat(32)
        val rater = "0x" + "22".repeat(20)

        val (updated, existing) =
            runBlocking { service().processEvents(listOf(event("SomethingElse", appId, rater, 3))) }

        assertTrue(updated.isEmpty())
        assertTrue(existing.isEmpty())
    }

    @Test
    fun `processEvents returns empty for empty input`() {
        val result = runBlocking { service().processEvents(emptyList()) }
        assertTrue(result.first.isEmpty())
        assertTrue(result.second.isEmpty())
    }
}
