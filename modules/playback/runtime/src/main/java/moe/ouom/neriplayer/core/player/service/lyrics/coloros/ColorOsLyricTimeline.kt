package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import java.util.Collections

internal data class ColorOsLyricRequest(val songKey: String?, val generation: Long, val requestId: Long)

internal data class ColorOsLyricTimeline(
    val request: ColorOsLyricRequest,
    val lyrics: List<LyricEntry>,
    val translations: List<LyricEntry>,
    val offsetMs: Long,
)

internal class ColorOsLyricTimelineOwner {
    private val mutableTimeline = MutableStateFlow<ColorOsLyricTimeline?>(null)
    val timeline: StateFlow<ColorOsLyricTimeline?> = mutableTimeline
    private var currentRequest = ColorOsLyricRequest(null, 0L, 0L)

    @Synchronized
    fun begin(songKey: String?): ColorOsLyricRequest {
        val generation = currentRequest.generation + if (songKey != null && songKey != currentRequest.songKey) 1L else 0L
        currentRequest = ColorOsLyricRequest(songKey, generation, currentRequest.requestId + 1L)
        mutableTimeline.value = null
        return currentRequest
    }

    @Synchronized
    fun isCurrent(request: ColorOsLyricRequest): Boolean = request == currentRequest

    @Synchronized
    fun publish(
        request: ColorOsLyricRequest,
        lyrics: List<LyricEntry>,
        translations: List<LyricEntry>,
        offsetMs: Long,
    ): Boolean {
        if (request.songKey == null || !isCurrent(request)) return false
        mutableTimeline.value = ColorOsLyricTimeline(
            request, lyrics.immutableCopy(), translations.immutableCopy(), offsetMs,
        )
        return true
    }

    @Synchronized
    fun updateOffset(offsetMs: Long) {
        val current = mutableTimeline.value ?: return
        if (current.offsetMs != offsetMs) mutableTimeline.value = current.copy(offsetMs = offsetMs)
    }

    @Synchronized
    fun clear() {
        mutableTimeline.value = null
    }
}

private fun List<LyricEntry>.immutableCopy(): List<LyricEntry> = Collections.unmodifiableList(
    map { line -> line.copy(words = line.words?.let { Collections.unmodifiableList(it.toList()) }) },
)
