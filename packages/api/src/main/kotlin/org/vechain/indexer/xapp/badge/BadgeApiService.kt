package org.vechain.indexer.xapp.badge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.vechain.indexer.IndexerService
import org.vechain.indexer.b3tr.xAlloc.repository.XAllocResultRepository
import org.vechain.indexer.rest.PaginatedResponse
import org.vechain.indexer.rest.paginatedResponse
import org.vechain.indexer.xapp.badge.repository.BadgeAwardRepository

@Profile("b3tr", "xapp-badge")
@Service
open class BadgeApiService(
    private val repository: BadgeAwardRepository,
    private val xAllocResultRepository: XAllocResultRepository,
    private val mongoTemplate: MongoTemplate,
) : IndexerService {

    companion object {
        private val DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.of("UTC"))

        private val BADGE_NAMES =
            mapOf(
                BadgeType.TOP_ECOSYSTEM_DAPP to "Top Ecosystem dApp",
                BadgeType.TOP_DISTRIBUTION_PERFORMER to "Top Distribution Performer",
                BadgeType.NAVIGATORS_PICK to "Navigators' Pick",
            )

        private val BADGE_DESCRIPTIONS =
            mapOf(
                BadgeType.TOP_ECOSYSTEM_DAPP to
                    "Awarded to apps ranked in the top 10% based on scoring results each round.",
                BadgeType.TOP_DISTRIBUTION_PERFORMER to
                    "Awarded to apps ranked in the top 10% based on distribution efficiency each round.",
                BadgeType.NAVIGATORS_PICK to
                    "Awarded to the top 3 apps supported by Navigators each round.",
            )
    }

    open fun getAppBadges(appId: String): List<BadgeSummary> {
        val allAwards = repository.findAllByAppId(appId)
        return BadgeType.entries.map { type ->
            val typeAwards = allAwards.filter { it.badgeType == type }
            BadgeSummary(
                badgeType = type,
                badgeName = BADGE_NAMES[type] ?: type.name,
                isPublished = typeAwards.isEmpty() || typeAwards.all { it.isPublished },
                totalCount = typeAwards.size,
                latestRound = typeAwards.maxByOrNull { it.round }?.round,
            )
        }
    }

    open fun getBadgeDetail(appId: String, badgeType: BadgeType): BadgeDetail {
        val awards = repository.findAllByAppIdAndBadgeType(appId, badgeType)
        return BadgeDetail(
            badgeType = badgeType,
            badgeName = BADGE_NAMES[badgeType] ?: badgeType.name,
            isPublished = awards.isEmpty() || awards.all { it.isPublished },
            description = BADGE_DESCRIPTIONS[badgeType] ?: "",
            totalCount = awards.size,
            latestRound = awards.maxByOrNull { it.round }?.round,
            records =
                awards
                    .sortedByDescending { it.round }
                    .map { BadgeRecord(round = it.round, rank = it.rank, date = it.blockTimestamp) },
        )
    }

    open fun updateBadgeVisibility(appId: String, badgeType: BadgeType, isPublished: Boolean) {
        val query = Query(Criteria.where("appId").`is`(appId).and("badgeType").`is`(badgeType))
        val update = Update.update("isPublished", isPublished)
        mongoTemplate.updateMulti(query, update, BadgeAward::class.java)
    }

    open fun getBadgeRecords(
        appId: String,
        badgeType: BadgeType,
        pageable: Pageable,
    ): PaginatedResponse<BadgeRecord> {
        val results =
            repository.findAllByAppIdAndBadgeTypeOrderByRoundDesc(appId, badgeType, pageable)
        val records =
            results.map { BadgeRecord(round = it.round, rank = it.rank, date = it.blockTimestamp) }
        val slice: Slice<BadgeRecord> = SliceImpl(records.content, pageable, results.hasNext())
        return paginatedResponse(slice)
    }

    open fun getBadgeStats(appId: String, badgeType: String): BadgeStatsResponse {
        val type = BadgeType.fromValue(badgeType)
        val awards = repository.findAllByAppIdAndBadgeType(appId, type)
        val roundIds = awards.map { it.round.toInt() }.distinct()
        val roundTimestamps =
            roundIds
                .mapNotNull { roundId ->
                    xAllocResultRepository
                        .findByRoundId(roundId)
                        .firstOrNull()
                        ?.blockTimestamp
                        ?.let { roundId.toLong() to it }
                }
                .toMap()
        return BadgeStatsResponse(
            totalCount = awards.size,
            latestRound = awards.maxByOrNull { it.round }?.round,
            rounds =
                awards
                    .sortedByDescending { it.round }
                    .map {
                        BadgeRoundInfo(
                            round = it.round,
                            rank = it.rank,
                            date =
                                roundTimestamps[it.round]?.let { ts ->
                                    DATE_FORMATTER.format(Instant.ofEpochSecond(ts))
                                } ?: "",
                        )
                    },
        )
    }

    open fun getPublicBadges(appId: String): List<BadgeAward> {
        val allPublic = repository.findAllPublicByAppId(appId)
        if (allPublic.isEmpty()) return emptyList()
        val latestRound = allPublic.maxOf { it.round }
        return allPublic.filter { it.round == latestRound }
    }

    override fun getLatestIndexedBlocks(): Map<String, Long> = mapOf()
}
