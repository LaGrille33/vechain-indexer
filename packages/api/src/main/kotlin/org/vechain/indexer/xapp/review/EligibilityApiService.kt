package org.vechain.indexer.xapp.review

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.vechain.indexer.b3tr.action.repository.AppAllTimeActionSummaryRepository

/**
 * Reward-eligibility pre-check for rating/review.
 *
 * A user may rate/review an app only if they have received a B3TR reward from it. The authoritative
 * enforcer is XAppReviewManager v3 (on-chain, via VeBetterPassport.userUniqueAppInteraction); this
 * endpoint is a UX pre-check backed by the indexer's all-time action summary
 * (`b3tr_app_action_summaries_all_time`), which records every reward a wallet has received per app.
 *
 * The two signals agree in practice: X2EarnRewardsPool registers the passport interaction each time
 * it distributes a reward to an endorsed app, and the same distribution is what this summary
 * counts. Reading off-chain avoids fragile on-chain ABI calls and directly answers "did this wallet
 * receive a reward from this app", which is what the contract gate ultimately requires.
 */
@Profile("b3tr")
@Service
open class EligibilityApiService(private val repository: AppAllTimeActionSummaryRepository) {
    @Schema(description = "Whether the wallet may rate/review the app")
    data class EligibilityResult(
        @Schema(description = "App ID (bytes32 hex, lowercased)") val appId: String,
        @Schema(description = "Wallet address (lowercased)") val wallet: String,
        @Schema(description = "True if the wallet has received a B3TR reward from the app")
        val eligible: Boolean,
        @Schema(
            description = "Total B3TR rewarded to the wallet for the app (null if none)",
            nullable = true,
        )
        val totalRewardAmount: BigDecimal?,
        @Schema(
            description = "Number of rewarded actions for the wallet on the app (null if none)",
            nullable = true,
        )
        val actionsRewarded: Long?,
        @Schema(description = "Reason when not eligible", nullable = true) val reason: String?,
    )

    open fun getEligibility(appId: String, wallet: String): EligibilityResult {
        val app = appId.lowercase()
        val who = wallet.lowercase()
        val summary = repository.findByAppIdAndUser(app, who)
        val eligible = summary != null && summary.totalRewardAmount > BigDecimal.ZERO
        return EligibilityResult(
            appId = app,
            wallet = who,
            eligible = eligible,
            totalRewardAmount = summary?.totalRewardAmount,
            actionsRewarded = summary?.actionsRewarded,
            reason = if (eligible) null else "no-reward-from-app",
        )
    }
}
