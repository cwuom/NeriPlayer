package moe.ouom.neriplayer.lyrics.output

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.lyrics.offset.resolveEffectiveLyricOffsetMs

data class LyriconPreferences(
    val enabled: Boolean,
    val preferredSource: LyricSourcePreference = LyricSourcePreference.Automatic,
    val cloudMusicOffsetMs: Long = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
    val qqMusicOffsetMs: Long = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
    val kugouOffsetMs: Long = DEFAULT_KUGOU_LYRIC_OFFSET_MS,
    val lrcLibOffsetMs: Long = DEFAULT_LRCLIB_LYRIC_OFFSET_MS,
    val amllTtmlOffsetMs: Long = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS,
) {
    internal fun offsetFor(song: SongItem, resolvedSource: LyricSourcePreference?): Long {
        return resolveEffectiveLyricOffsetMs(
            lyricSource = song.matchedLyricSource,
            cloudMusicDefaultOffsetMs = cloudMusicOffsetMs,
            qqMusicDefaultOffsetMs = qqMusicOffsetMs,
            userLyricOffsetMs = song.userLyricOffsetMs,
            kugouDefaultOffsetMs = kugouOffsetMs,
            lrclibDefaultOffsetMs = lrcLibOffsetMs,
            amllTtmlDefaultOffsetMs = amllTtmlOffsetMs,
            preferredLyricSource = resolvedSource,
        )
    }
}
