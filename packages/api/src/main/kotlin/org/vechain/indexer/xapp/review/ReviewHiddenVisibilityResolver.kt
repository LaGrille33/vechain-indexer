package org.vechain.indexer.xapp.review

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.model.abi.AbiElement
import org.vechain.indexer.thor.AddressUtils
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.BlockRevision
import org.vechain.indexer.thor.model.InspectionResult
import org.vechain.indexer.transaction.TransactionUtils.isSuccessWithData
import org.vechain.indexer.utils.ContractUtils
import org.web3j.crypto.Hash

/**
 * Decides whether a wallet may see hidden reviews, by reading on-chain state at request time
 * (mirroring how the VeBetterDAO frontend resolves privileges via direct contract reads):
 * - a global review-manager role: `XAppReviewManager.hasRole(MODERATOR_ROLE, wallet)` or
 *   `hasRole(DEFAULT_ADMIN_ROLE, wallet)`
 *
 * App admins (`X2EarnApps.isAppAdmin`) are intentionally NOT granted hidden-review visibility; only
 * review-manager admin/moderator roles are. (A hidden review's author still sees their own review
 * via the repository query layer, independent of this resolver.)
 *
 * The two checks are issued as a single batched `inspectClauses` call; if either returns true the
 * wallet may see hidden reviews. Results are cached per appId+wallet for a short TTL, and any error
 * (node down, revert, decode failure) fails open to `false` so the endpoint degrades to the public
 * view instead of erroring.
 */
@Profile("b3tr", "xapp-review", "xapp-review-vote")
@Service
open class ReviewHiddenVisibilityResolver(
    private val thorClient: ThorClient,
    @Value(
        "\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT:\${XAPP_REVIEW_MANAGER_CONTRACT:}}"
    )
    private val xAppReviewManagerContract: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = TimeUnit.MINUTES.toMillis(1)

    private data class CacheEntry(val value: Boolean, val timestamp: Long)

    // AccessControl roles on XAppReviewManager. DEFAULT_ADMIN_ROLE is bytes32(0);
    // MODERATOR_ROLE = keccak256("MODERATOR_ROLE").
    private val moderatorRole: ByteArray = Hash.sha3("MODERATOR_ROLE".toByteArray())
    private val defaultAdminRole: ByteArray = ByteArray(32)

    private val hasRoleAbi: AbiElement by lazy { loadAbi("abis/b3tr", "hasRole") }

    /**
     * True if [wallet] holds a review-manager admin/moderator role (XAppReviewManager
     * DEFAULT_ADMIN_ROLE / MODERATOR_ROLE), granting visibility of hidden reviews for [appId].
     * Returns false without hitting the node when the review-manager contract is not configured.
     */
    open fun canSeeHiddenReviews(appId: String, wallet: String): Boolean {
        val app = normalise(appId)
        val who = normalise(wallet)
        if (xAppReviewManagerContract.isBlank()) return false

        val key = "$app:$who"
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.timestamp < cacheTtlMs) return it.value }

        val value =
            try {
                checkOnChain(who)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to resolve hidden-review visibility for appId={} wallet={}: {}",
                    app,
                    who,
                    e.message,
                )
                false
            }
        cache[key] = CacheEntry(value, now)
        return value
    }

    private fun checkOnChain(wallet: String): Boolean {
        val walletBigInt = AddressUtils.toBigInt(wallet)

        val clauses =
            listOf(
                ContractUtils.createClause(
                    xAppReviewManagerContract,
                    hasRoleAbi,
                    moderatorRole,
                    walletBigInt,
                ),
                ContractUtils.createClause(
                    xAppReviewManagerContract,
                    hasRoleAbi,
                    defaultAdminRole,
                    walletBigInt,
                ),
            )

        val responses = runBlocking {
            thorClient.inspectClauses(clauses, BlockRevision.Keyword.BEST)
        }
        // Every clause above returns a single bool, so they share an output shape.
        return responses.any { decodeBool(it) }
    }

    private fun decodeBool(res: InspectionResult): Boolean {
        if (!isSuccessWithData(res)) return false
        // Every clause above returns a single ABI-encoded bool: a 32-byte word that is true iff any
        // byte is non-zero (false == all zeros). Parse the word directly — robust and avoids the
        // shaded web3j Bool wrapper returned by FunctionReturnDecoder.
        return res.data.removePrefix("0x").any { it != '0' }
    }

    private fun loadAbi(dir: String, name: String): AbiElement =
        AbiLoader.loadFunctions(dir, listOf(name)).firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Function '$name' not found in $dir ABIs")
}
