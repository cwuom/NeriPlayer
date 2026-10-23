package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger

internal fun CoroutineScope.observeColorOsLyricMetadata(
    timeline: StateFlow<ColorOsLyricTimeline?>,
    enabled: StateFlow<Boolean>,
    onChanged: () -> Unit,
): Job = launch {
    combine(timeline, enabled) { snapshot, outputEnabled -> snapshot to outputEnabled }
        .collect {
            try {
                onChanged()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                NPLogger.e("NERI-APS", "ColorOS lyric metadata handler failed", error)
            }
        }
}
