package org.vechain.indexer.xapp.review

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.Clause
import org.vechain.indexer.thor.model.InspectionResult
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue

internal class ReviewHiddenVisibilityResolverTest {
    private lateinit var thorClient: ThorClient

    @BeforeEach
    fun freshMock() {
        thorClient = mockk()
    }

    private val reviewMgrAddr = "0xd369454a6fdca471831ef87f488b1f67418a336a"
    private val appId = "0x" + "ab".repeat(32)
    private val wallet = "0x" + "12".repeat(20)

    // ABI bool: 32 bytes, true = ...01, false = ...00
    private val boolTrue = "0x" + "0".repeat(63) + "1"
    private val boolFalse = "0x" + "0".repeat(64)

    private fun boolResult(value: Boolean): InspectionResult = mockk {
        every { reverted } returns false
        every { vmError } returns null
        every { data } returns if (value) boolTrue else boolFalse
    }

    private fun revertResult(): InspectionResult = mockk {
        every { reverted } returns true
        every { vmError } returns "execution reverted"
        every { data } returns "0x"
    }

    private fun resolver(mgr: String = reviewMgrAddr) =
        ReviewHiddenVisibilityResolver(thorClient, mgr)

    @Test
    fun `queries both hasRole checks on the review-manager contract and ORs them`() {
        val clausesSlot = slot<List<Clause>>()
        coEvery { thorClient.inspectClauses(capture(clausesSlot), any()) } returns
            listOf(boolResult(true), boolResult(true))

        val result = resolver().canSeeHiddenReviews(appId, wallet)

        expectThat(result).isTrue()
        val clauses = clausesSlot.captured
        expectThat(clauses).hasSize(2)
        // Both clauses are hasRole(bytes32 role, address) on XAppReviewManager — selector
        // 0x91d14854.
        expectThat(clauses[0].to?.lowercase()).isEqualTo(reviewMgrAddr)
        expectThat(clauses[0].data?.lowercase()?.substring(0, 10)).isEqualTo("0x91d14854")
        expectThat(clauses[1].to?.lowercase()).isEqualTo(reviewMgrAddr)
        expectThat(clauses[1].data?.lowercase()?.substring(0, 10)).isEqualTo("0x91d14854")
    }

    @Test
    fun `OR semantics - visible when either role flag is true`() {
        val cases =
            listOf(
                Pair(true to true, true),
                Pair(true to false, true),
                Pair(false to true, true),
                Pair(false to false, false),
            )
        cases.forEachIndexed { i, (flags, expected) ->
            val (moderator, admin) = flags
            // Distinct appId per case so the cache does not mask the result.
            val localAppId = "0x" + i.toString(16).padStart(64, '0')
            coEvery { thorClient.inspectClauses(any(), any()) } returns
                listOf(boolResult(moderator), boolResult(admin))
            expectThat(resolver().canSeeHiddenReviews(localAppId, wallet)).isEqualTo(expected)
        }
    }

    @Test
    fun `fails open to false when the node call throws`() {
        coEvery { thorClient.inspectClauses(any(), any()) } throws RuntimeException("boom")
        expectThat(resolver().canSeeHiddenReviews(appId, wallet)).isFalse()
    }

    @Test
    fun `a reverted role clause is treated as false without sinking a true role clause`() {
        coEvery { thorClient.inspectClauses(any(), any()) } returns
            listOf(boolResult(true), revertResult())
        expectThat(resolver().canSeeHiddenReviews(appId, wallet)).isTrue()
    }

    @Test
    fun `caches the result for the same appId and wallet`() {
        coEvery { thorClient.inspectClauses(any(), any()) } returns
            listOf(boolResult(false), boolResult(false))
        val r = resolver()
        r.canSeeHiddenReviews(appId, wallet)
        r.canSeeHiddenReviews(appId, wallet)
        coVerify(exactly = 1) { thorClient.inspectClauses(any(), any()) }
    }

    @Test
    fun `returns false without calling the node when the review-manager contract is blank`() {
        val r = resolver(mgr = "")
        expectThat(r.canSeeHiddenReviews(appId, wallet)).isFalse()
        coVerify(exactly = 0) { thorClient.inspectClauses(any(), any()) }
    }
}
