package org.vechain.indexer.xapp.badge.repository

import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository
import org.vechain.indexer.xapp.badge.BadgeAward
import org.vechain.indexer.xapp.badge.BadgeType

@Profile("b3tr", "xapp-badge")
@Repository
interface BadgeAwardRepository : BaseIndexedRepository<BadgeAward, String> {

    fun findAllByAppId(appId: String): List<BadgeAward>

    fun findAllByAppIdAndBadgeType(appId: String, badgeType: BadgeType): List<BadgeAward>

    fun findAllByAppIdAndBadgeTypeOrderByRoundDesc(
        appId: String,
        badgeType: BadgeType,
        pageable: Pageable,
    ): Slice<BadgeAward>

    fun countByAppIdAndBadgeType(appId: String, badgeType: BadgeType): Long

    fun findTopByAppIdAndBadgeTypeOrderByRoundDesc(appId: String, badgeType: BadgeType): BadgeAward?

    @Query("{ 'appId': ?0, 'isPublished': true }")
    fun findAllPublicByAppId(appId: String): List<BadgeAward>
}
