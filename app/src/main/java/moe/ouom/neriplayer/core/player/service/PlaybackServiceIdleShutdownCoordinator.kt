package moe.ouom.neriplayer.core.player.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlaybackServiceIdleShutdownCoordinator(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val isEligible: () -> Boolean,
    private val currentStartId: () -> Int,
    private val onShutdown: (Int) -> Unit,
) {
    private var shutdownJob: Job? = null

    fun refresh() {
        if (!isEligible()) {
            cancel()
            return
        }
        if (shutdownJob?.isActive == true) return

        val scheduledStartId = currentStartId()
        shutdownJob = scope.launch {
            delay(delayMs)
            shutdownJob = null
            if (scheduledStartId != currentStartId() || !isEligible()) {
                refresh()
                return@launch
            }
            onShutdown(scheduledStartId)
        }
    }

    fun cancel() {
        shutdownJob?.cancel()
        shutdownJob = null
    }
}
