package org.vechain.indexer.b3tr.navigator

import com.fasterxml.jackson.annotation.JsonIgnore
import java.math.BigDecimal
import java.math.BigInteger
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import org.vechain.indexer.IndexedDocument
import org.vechain.indexer.IndexerNames

@Document(collection = IndexerNames.NAVIGATOR_DAPP_VOTE_ROUND_RANKING.COLLECTION)
data class NavigatorDappVoteRoundRanking
@ConstructorBinding
constructor(
    @Id val id: String,
    @JsonIgnore override val blockId: String,
    @JsonIgnore override val blockNumber: Long,
    @JsonIgnore override val blockTimestamp: Long,
    val roundId: Int,
    val reportGeneratedAt: Long,
    val dappCount: Long,
    @Field(targetType = FieldType.DECIMAL128) val totalWeight: BigDecimal,
    val dappRankings: List<NavigatorDappVoteRankingEntry>,
) : IndexedDocument {
    companion object {
        fun idForRound(roundId: Int): String = "round_$roundId"
    }
}

data class NavigatorDappVoteRankingEntry
@ConstructorBinding
constructor(
    val rank: Int,
    val appId: String,
    val weight: BigInteger,
    val contributingNavigators: Long,
)
