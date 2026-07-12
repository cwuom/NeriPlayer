package moe.ouom.neriplayer.ui.component

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

private const val NO_LYRIC_LINE_INDEX = -1
private const val MIN_LYRIC_SEEK_HAPTIC_INTERVAL_MS = 55L

@Composable
fun rememberLyricSeekHapticFeedback(
    lyrics: List<LyricEntry>,
    lyricOffsetMs: Long = 0L
): LyricSeekHapticFeedback {
    val context = LocalContext.current.applicationContext
    return remember(context, lyrics, lyricOffsetMs) {
        LyricSeekHapticFeedback(
            context = context,
            lyrics = lyrics,
            lyricOffsetMs = lyricOffsetMs
        )
    }
}

class LyricSeekHapticFeedback internal constructor(
    private val context: Context,
    private val lyrics: List<LyricEntry>,
    private val lyricOffsetMs: Long
) {
    private var hasBaseline = false
    private var lastLineIndex = NO_LYRIC_LINE_INDEX
    private var lastFeedbackUptimeMs = 0L

    fun onSeekStart(positionMs: Long) {
        hasBaseline = true
        lastLineIndex = resolveLineIndex(positionMs)
        lastFeedbackUptimeMs = 0L
    }

    fun onSeekMove(positionMs: Long) {
        val lineIndex = resolveLineIndex(positionMs)
        if (!hasBaseline) {
            hasBaseline = true
            lastLineIndex = lineIndex
            return
        }

        if (lineIndex == lastLineIndex) return

        lastLineIndex = lineIndex
        if (lineIndex == NO_LYRIC_LINE_INDEX) return

        val now = SystemClock.uptimeMillis()
        if (now - lastFeedbackUptimeMs < MIN_LYRIC_SEEK_HAPTIC_INTERVAL_MS) return

        lastFeedbackUptimeMs = now
        context.performHapticFeedback(HapticFeedbackEffect.Tick)
    }

    fun onSeekEnd() {
        hasBaseline = false
        lastLineIndex = NO_LYRIC_LINE_INDEX
        lastFeedbackUptimeMs = 0L
    }

    private fun resolveLineIndex(positionMs: Long): Int {
        if (lyrics.isEmpty()) return NO_LYRIC_LINE_INDEX

        val lyricTimeMs = (positionMs + lyricOffsetMs).coerceAtLeast(0L)
        if (lyricTimeMs < lyrics.first().startTimeMs) return NO_LYRIC_LINE_INDEX

        val lineIndex = findCurrentLineIndex(lyrics, lyricTimeMs)
        return if (lineIndex in lyrics.indices) lineIndex else NO_LYRIC_LINE_INDEX
    }
}
