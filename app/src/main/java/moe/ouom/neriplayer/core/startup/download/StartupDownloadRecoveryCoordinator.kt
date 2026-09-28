package moe.ouom.neriplayer.core.startup.download

import android.content.Context
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import kotlin.time.Duration.Companion.milliseconds

internal class StartupDownloadRecoveryCoordinator(
    private val context: Context,
    private val awaitResumed: suspend () -> Unit,
    private val requestRecoveryDecision: (Context, String) -> Unit = { targetContext, reason ->
        GlobalDownloadManager.requestPendingDownloadRecoveryDecisionIfNeeded(
            context = targetContext,
            reason = reason
        )
    }
) {
    suspend fun requestWhenMainReady() {
        awaitResumed()
        StartupDownloadRecoveryPlan.defaultAttempts().forEach { attempt ->
            delay(attempt.delayMs.milliseconds)
            requestRecoveryDecision(context, attempt.reason)
        }
    }
}
