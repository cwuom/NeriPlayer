package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import kotlin.math.roundToLong

internal const val MIN_LYRIC_DEFAULT_OFFSET_MS = -2000L
internal const val MAX_LYRIC_DEFAULT_OFFSET_MS = 2000L
internal const val LYRIC_DEFAULT_OFFSET_STEP_MS = 50L
internal const val DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS = 1000L
internal const val DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS = 500L

fun normalizeLyricDefaultOffsetMs(value: Long): Long {
    val stepAligned =
        (value.toDouble() / LYRIC_DEFAULT_OFFSET_STEP_MS).roundToLong() * LYRIC_DEFAULT_OFFSET_STEP_MS
    return stepAligned.coerceIn(MIN_LYRIC_DEFAULT_OFFSET_MS, MAX_LYRIC_DEFAULT_OFFSET_MS)
}

internal fun resolveLyricDefaultOffsetMs(
    lyricSource: MusicPlatform?,
    cloudMusicDefaultOffsetMs: Long,
    qqMusicDefaultOffsetMs: Long
): Long {
    return if (lyricSource == MusicPlatform.QQ_MUSIC) {
        qqMusicDefaultOffsetMs
    } else {
        cloudMusicDefaultOffsetMs
    }
}

internal fun shouldRebaseLyricOffsetForSource(
    lyricSource: MusicPlatform?,
    targetSource: MusicPlatform,
    userOffsetMs: Long
): Boolean {
    if (userOffsetMs == 0L) {
        return false
    }
    return when (targetSource) {
        MusicPlatform.QQ_MUSIC -> lyricSource == MusicPlatform.QQ_MUSIC
        MusicPlatform.CLOUD_MUSIC -> lyricSource != MusicPlatform.QQ_MUSIC
    }
}

internal fun rebaseLyricUserOffsetMs(
    userOffsetMs: Long,
    previousDefaultOffsetMs: Long,
    newDefaultOffsetMs: Long
): Long {
    return userOffsetMs + previousDefaultOffsetMs - newDefaultOffsetMs
}
