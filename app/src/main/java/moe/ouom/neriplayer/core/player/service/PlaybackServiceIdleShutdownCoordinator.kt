package moe.ouom.neriplayer.core.player.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlaybackServiceIdleShutdownCoordinator(
    private val scope: CoroutineScope,
    delayMs: Long,
    private val isEligible: () -> Boolean,
    private val currentStartId: () -> Int,
    private val onShutdown: (Int) -> Unit,
) {
    private var delayMs = delayMs.coerceAtLeast(0L)
    private var shutdownJob: Job? = null

    fun refresh() {
        if (delayMs <= 0L || !isEligible()) {
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

    fun updateDelayMs(newDelayMs: Long) {
        val normalizedDelayMs = newDelayMs.coerceAtLeast(0L)
        if (normalizedDelayMs == delayMs) return

        cancel()
        delayMs = normalizedDelayMs
        refresh()
    }

    fun cancel() {
        shutdownJob?.cancel()
        shutdownJob = null
    }
}
