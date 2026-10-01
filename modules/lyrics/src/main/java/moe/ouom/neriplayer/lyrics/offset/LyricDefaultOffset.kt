package moe.ouom.neriplayer.lyrics.offset

import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import kotlin.math.roundToLong

const val MIN_LYRIC_DEFAULT_OFFSET_MS = -5000L
const val MAX_LYRIC_DEFAULT_OFFSET_MS = 5000L
const val LYRIC_DEFAULT_OFFSET_STEP_MS = 50L

fun normalizeLyricDefaultOffsetMs(value: Long): Long {
    val stepAligned =
        (value.toDouble() / LYRIC_DEFAULT_OFFSET_STEP_MS).roundToLong() * LYRIC_DEFAULT_OFFSET_STEP_MS
    return stepAligned.coerceIn(MIN_LYRIC_DEFAULT_OFFSET_MS, MAX_LYRIC_DEFAULT_OFFSET_MS)
}

internal fun resolveLyricDefaultOffsetMs(
    lyricSource: MusicPlatform?,
    cloudMusicDefaultOffsetMs: Long,
    qqMusicDefaultOffsetMs: Long,
    kugouDefaultOffsetMs: Long = DEFAULT_KUGOU_LYRIC_OFFSET_MS,
    lrclibDefaultOffsetMs: Long = DEFAULT_LRCLIB_LYRIC_OFFSET_MS,
    amllTtmlDefaultOffsetMs: Long = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS,
    preferredLyricSource: LyricSourcePreference? = null
): Long {
    return when (preferredLyricSource) {
        LyricSourcePreference.Kugou -> kugouDefaultOffsetMs
        LyricSourcePreference.LrcLib -> lrclibDefaultOffsetMs
        LyricSourcePreference.AmllTtml -> amllTtmlDefaultOffsetMs
        LyricSourcePreference.QqMusic -> qqMusicDefaultOffsetMs
        LyricSourcePreference.CloudMusic -> cloudMusicDefaultOffsetMs
        else -> if (lyricSource == MusicPlatform.QQ_MUSIC) {
            qqMusicDefaultOffsetMs
        } else {
            cloudMusicDefaultOffsetMs
        }
    }
}

fun resolveEffectiveLyricOffsetMs(
    lyricSource: MusicPlatform?,
    cloudMusicDefaultOffsetMs: Long,
    qqMusicDefaultOffsetMs: Long,
    userLyricOffsetMs: Long,
    kugouDefaultOffsetMs: Long = DEFAULT_KUGOU_LYRIC_OFFSET_MS,
    lrclibDefaultOffsetMs: Long = DEFAULT_LRCLIB_LYRIC_OFFSET_MS,
    amllTtmlDefaultOffsetMs: Long = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS,
    preferredLyricSource: LyricSourcePreference? = null
): Long {
    return saturatingAddLyricOffsetMs(
        value = resolveLyricDefaultOffsetMs(
            lyricSource = lyricSource,
            cloudMusicDefaultOffsetMs = cloudMusicDefaultOffsetMs,
            qqMusicDefaultOffsetMs = qqMusicDefaultOffsetMs,
            kugouDefaultOffsetMs = kugouDefaultOffsetMs,
            lrclibDefaultOffsetMs = lrclibDefaultOffsetMs,
            amllTtmlDefaultOffsetMs = amllTtmlDefaultOffsetMs,
            preferredLyricSource = preferredLyricSource
        ),
        delta = userLyricOffsetMs
    )
}

fun saturatingAddLyricOffsetMs(value: Long, delta: Long): Long {
    return when {
        delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }
}

private fun saturatingSubtractLyricOffsetMs(value: Long, delta: Long): Long {
    return when {
        delta > 0L && value < Long.MIN_VALUE + delta -> Long.MIN_VALUE
        delta < 0L && value > Long.MAX_VALUE + delta -> Long.MAX_VALUE
        else -> value - delta
    }
}

fun shouldRebaseLyricOffsetForSource(
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

fun rebaseLyricUserOffsetMs(
    userOffsetMs: Long,
    previousDefaultOffsetMs: Long,
    newDefaultOffsetMs: Long
): Long {
    return saturatingSubtractLyricOffsetMs(
        value = saturatingAddLyricOffsetMs(
            value = userOffsetMs,
            delta = previousDefaultOffsetMs
        ),
        delta = newDefaultOffsetMs
    )
}
