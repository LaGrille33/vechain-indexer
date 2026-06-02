package org.vechain.indexer.b3tr.action.response

data class RoundUserStats(
    val round: Int,
    val roundDate: Long,
    val activeUsers: Long,
    val newUsers: Long,
)

data class AppRoundUserStatsResponse(val appId: String, val datas: List<RoundUserStats>)
