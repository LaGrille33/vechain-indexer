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

@Document(collection = IndexerNames.NAVIGATOR_VOTE_ROUND_REPORT.COLLECTION)
data class NavigatorVoteRoundReport
@ConstructorBinding
constructor(
    @Id val id: String,
    @JsonIgnore override val blockId: String,
    @JsonIgnore override val blockNumber: Long,
    @JsonIgnore override val blockTimestamp: Long,
    val roundId: Int,
    val reportStatus: String,
    val reportGeneratedAt: Long,
    val totalVotes: Long,
    val delegatedVotes: Long,
    val manualVotes: Long,
    val autoVotes: Long,
    val navigatorCount: Long,
    val roundDeadline: Long?,
    val roundSnapshot: Long?,
    @Field(targetType = FieldType.DECIMAL128) val totalDelegatedWeight: BigDecimal,
    val navigatorSummaries: List<NavigatorVoteSummary>,
) : IndexedDocument {
    companion object {
        fun idForRound(roundId: Int): String = "round_$roundId"
    }
}

data class NavigatorVoteSummary
@ConstructorBinding
constructor(
    val navigator: String,
    val voteEvents: Long,
    val citizenCount: Long,
    @Field(targetType = FieldType.DECIMAL128) val weightTotal: BigDecimal,
    val topApps: List<NavigatorAppWeight>,
)

data class NavigatorAppWeight
@ConstructorBinding
constructor(val appId: String, val weight: BigInteger)
