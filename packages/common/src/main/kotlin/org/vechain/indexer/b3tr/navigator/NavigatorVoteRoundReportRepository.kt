package org.vechain.indexer.b3tr.navigator

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import org.vechain.indexer.BaseIndexedRepository

@Profile("b3tr", "b3tr-navigator", "b3tr-navigator-vote-report")
@Repository
interface NavigatorVoteRoundReportRepository :
    BaseIndexedRepository<NavigatorVoteRoundReport, String> {
    fun findByRoundId(roundId: Int): NavigatorVoteRoundReport?
}
