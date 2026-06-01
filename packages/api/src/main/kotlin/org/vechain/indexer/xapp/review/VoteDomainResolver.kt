package org.vechain.indexer.xapp.review

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.event.AbiLoader
import org.vechain.indexer.event.utils.FunctionReturnDecoder
import org.vechain.indexer.thor.AddressUtils
import org.vechain.indexer.thor.HexUtils.normalise
import org.vechain.indexer.thor.client.ThorClient
import org.vechain.indexer.thor.model.BlockRevision
import org.vechain.indexer.transaction.TransactionUtils.isSuccessWithData
import org.vechain.indexer.utils.ContractUtils

@Profile("b3tr", "xapp-review-vote")
@Service
open class VoteDomainResolver(
    private val thorClient: ThorClient,
    @Value(
        "\${business-event.substitutions.VET_DOMAINS_RESOLVE_UTILS_CONTRACT:\${VET_DOMAINS_RESOLVE_UTILS_CONTRACT:}}"
    )
    private val resolveUtilsContract: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class CacheEntry(val domain: String?, val timestamp: Long)

    private val cacheTtlMs = TimeUnit.MINUTES.toMillis(10)

    /**
     * Batch-resolves wallet addresses to vet-domain names. Returns a map of address → domain (null
     * if no domain found). Results are cached for 10 minutes.
     */
    open fun resolveDomains(addresses: List<String>): Map<String, String?> {
        if (resolveUtilsContract.isBlank()) {
            return addresses.associateWith { null }
        }

        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, String?>()
        val toResolve = mutableListOf<String>()

        for (addr in addresses) {
            val normalised = normalise(addr)
            val cached = cache[normalised]
            if (cached != null && now - cached.timestamp < cacheTtlMs) {
                result[normalised] = cached.domain
            } else {
                toResolve.add(normalised)
            }
        }

        if (toResolve.isNotEmpty()) {
            try {
                val resolved = callGetNames(toResolve)
                for ((addr, domain) in resolved) {
                    cache[addr] = CacheEntry(domain, now)
                    result[addr] = domain
                }
            } catch (e: Exception) {
                logger.warn("Failed to batch-resolve domains: {}", e.message, e)
                for (addr in toResolve) {
                    result.putIfAbsent(addr, null)
                }
            }
        }

        return result
    }

    private fun callGetNames(addresses: List<String>): Map<String, String?> {
        val abi =
            AbiLoader.loadFunctions("abis/vet-domains", listOf("getNames")).firstOrNull()
                ?: throw IllegalArgumentException("getNames ABI not found")

        val bigints = addresses.map { AddressUtils.toBigInt(it) }
        @Suppress("UNCHECKED_CAST")
        val addressArray =
            java.lang.reflect.Array.newInstance(java.math.BigInteger::class.java, bigints.size)
                as Array<java.math.BigInteger>
        bigints.forEachIndexed { i, v -> addressArray[i] = v }
        val clause = ContractUtils.createClause(resolveUtilsContract, abi, addressArray)

        val response =
            kotlinx.coroutines
                .runBlocking {
                    thorClient.inspectClauses(listOf(clause), BlockRevision.Keyword.BEST)
                }
                .firstOrNull()
                ?: throw IllegalStateException("No response from resolveUtils contract")

        if (!isSuccessWithData(response)) {
            throw IllegalStateException("getNames call failed: ${response.vmError}")
        }

        val decoded = FunctionReturnDecoder.decode(response.data, abi.outputs)
        val names = (decoded["names"] as? List<*>) ?: (decoded[""] as? List<*>) ?: emptyList<Any>()

        return addresses
            .zip(names) { addr, name ->
                val domain = name?.toString()?.takeIf { it.isNotEmpty() }
                normalise(addr) to domain
            }
            .toMap()
    }
}
