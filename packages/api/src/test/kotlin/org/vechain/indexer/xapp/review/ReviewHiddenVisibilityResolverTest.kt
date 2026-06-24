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
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
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

    private val appsAddr = "0xd0d5dc52b273dbed064dd61d980c92462865f5dc"
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

    private fun resolver(apps: String = appsAddr, mgr: String = reviewMgrAddr) =
        ReviewHiddenVisibilityResolver(thorClient, apps, mgr)

    /**
     * Independent oracle for the isAppAdmin(bytes32,address) calldata: selector (keccak of the
     * signature) + bytes32 appId (full word) + address left-padded to a word.
     */
    private fun expectedIsAppAdminCalldata(): String {
        val selector =
            Numeric.toHexString(Hash.sha3("isAppAdmin(bytes32,address)".toByteArray()))
                .substring(0, 10)
        val appIdWord = appId.removePrefix("0x")
        val walletWord = wallet.removePrefix("0x").padStart(64, '0')
        return (selector + appIdWord + walletWord).lowercase()
    }

    @Test
    fun `returns true and queries isAppAdmin plus both roles on the right contracts`() {
        val clausesSlot = slot<List<Clause>>()
        coEvery { thorClient.inspectClauses(capture(clausesSlot), any()) } returns
            listOf(boolResult(true), boolResult(true), boolResult(true))

        val result = resolver().canSeeHiddenReviews(appId, wallet)

        expectThat(result).isTrue()
        val clauses = clausesSlot.captured
        expectThat(clauses).hasSize(3)
        // [0] isAppAdmin(bytes32 appId, address wallet) on X2EarnApps — exact calldata match.
        expectThat(clauses[0].to?.lowercase()).isEqualTo(appsAddr)
        expectThat(clauses[0].data?.lowercase()).isEqualTo(expectedIsAppAdminCalldata())
        // [1],[2] hasRole(bytes32 role, address) on XAppReviewManager — selector 0x91d14854.
        expectThat(clauses[1].to?.lowercase()).isEqualTo(reviewMgrAddr)
        expectThat(clauses[1].data?.lowercase()?.substring(0, 10)).isEqualTo("0x91d14854")
        expectThat(clauses[2].to?.lowercase()).isEqualTo(reviewMgrAddr)
        expectThat(clauses[2].data?.lowercase()?.substring(0, 10)).isEqualTo("0x91d14854")
    }

    @Test
    fun `OR semantics - visible when any of the three flags is true`() {
        val cases =
            listOf(
                Triple(true, true, true) to true,
                Triple(true, false, false) to true,
                Triple(false, true, false) to true,
                Triple(false, false, true) to true,
                Triple(false, false, false) to false,
            )
        cases.forEachIndexed { i, (flags, expected) ->
            val (a, b, c) = flags
            // Distinct appId per case so the cache does not mask the result.
            val localAppId = "0x" + i.toString(16).padStart(64, '0')
            coEvery { thorClient.inspectClauses(any(), any()) } returns
                listOf(boolResult(a), boolResult(b), boolResult(c))
            expectThat(resolver().canSeeHiddenReviews(localAppId, wallet)).isEqualTo(expected)
        }
    }

    @Test
    fun `fails open to false when the node call throws`() {
        coEvery { thorClient.inspectClauses(any(), any()) } throws RuntimeException("boom")
        expectThat(resolver().canSeeHiddenReviews(appId, wallet)).isFalse()
    }

    @Test
    fun `a reverted role clause is treated as false without sinking a true isAppAdmin`() {
        coEvery { thorClient.inspectClauses(any(), any()) } returns
            listOf(boolResult(true), revertResult(), revertResult())
        expectThat(resolver().canSeeHiddenReviews(appId, wallet)).isTrue()
    }

    @Test
    fun `caches the result for the same appId and wallet`() {
        coEvery { thorClient.inspectClauses(any(), any()) } returns
            listOf(boolResult(false), boolResult(false), boolResult(false))
        val r = resolver()
        r.canSeeHiddenReviews(appId, wallet)
        r.canSeeHiddenReviews(appId, wallet)
        coVerify(exactly = 1) { thorClient.inspectClauses(any(), any()) }
    }

    @Test
    fun `returns false without calling the node when both contract addresses are blank`() {
        val r = resolver(apps = "", mgr = "")
        expectThat(r.canSeeHiddenReviews(appId, wallet)).isFalse()
        coVerify(exactly = 0) { thorClient.inspectClauses(any(), any()) }
    }
}
