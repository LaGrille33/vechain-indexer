package org.vechain.indexer.b3tr.navigator

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.b3tr.round.repository.CycleInfoRepository

/**
 * Unit test for the navigatorsPick badge awarding that runs inline right after the navigator dApp
 * vote ranking is built — decoupled from the (racy) per-round scoring trigger so the badge is
 * awarded whenever a ranking is produced.
 */
internal class NavigatorVoteRoundReportBadgeTest {
    private val mongoTemplate = mockk<MongoTemplate>(relaxed = true)
    private val cycleInfoRepository = mockk<CycleInfoRepository>(relaxed = true)
    private val navigatorCitizenRepository = mockk<NavigatorCitizenRepository>(relaxed = true)

    private val service =
        NavigatorVoteRoundReportAggregationService(
            mongoTemplate,
            cycleInfoRepository,
            navigatorCitizenRepository,
        )

    @Test
    fun `awards navigatorsPick to the top 3 ranked dapps and skips the rest`() {
        val ranking =
            NavigatorDappVoteRoundRanking(
                id = NavigatorDappVoteRoundRanking.idForRound(33),
                blockId = "",
                blockNumber = 1L,
                blockTimestamp = 1000L,
                roundId = 33,
                reportGeneratedAt = 0L,
                dappCount = 4,
                totalWeight = BigDecimal.ZERO,
                dappRankings =
                    listOf(
                        NavigatorDappVoteRankingEntry(1, "0xapp1", BigInteger.valueOf(40), 1),
                        NavigatorDappVoteRankingEntry(2, "0xapp2", BigInteger.valueOf(30), 1),
                        NavigatorDappVoteRankingEntry(3, "0xapp3", BigInteger.valueOf(20), 1),
                        NavigatorDappVoteRankingEntry(4, "0xapp4", BigInteger.valueOf(10), 1),
                    ),
            )

        val queries = mutableListOf<Query>()
        every {
            mongoTemplate.upsert(
                capture(queries),
                any<Update>(),
                IndexerNames.XAPP_BADGE_AWARD.COLLECTION,
            )
        } returns mockk(relaxed = true)

        invokeAward(ranking, 2000L)

        val ids = queries.map { it.queryObject.getString("_id") }
        assertEquals(3, ids.size, "only the top 3 ranks get a badge")
        assertTrue(ids.contains("0xapp1_navigatorsPick_33"))
        assertTrue(ids.contains("0xapp2_navigatorsPick_33"))
        assertTrue(ids.contains("0xapp3_navigatorsPick_33"))
        assertFalse(ids.any { it.contains("0xapp4") }, "rank 4 must not be awarded")
    }

    private fun invokeAward(ranking: NavigatorDappVoteRoundRanking, roundBlockTimestamp: Long) {
        val method =
            NavigatorVoteRoundReportAggregationService::class
                .java
                .getDeclaredMethod(
                    "awardNavigatorsPickBadges",
                    NavigatorDappVoteRoundRanking::class.java,
                    Long::class.javaPrimitiveType,
                )
        method.isAccessible = true
        method.invoke(service, ranking, roundBlockTimestamp)
    }
}
