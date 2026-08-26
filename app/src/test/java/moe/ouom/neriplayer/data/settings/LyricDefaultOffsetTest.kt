package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricDefaultOffsetTest {

    @Test
    fun normalizeLyricDefaultOffsetMs_roundsToNearestStep() {
        assertEquals(0L, normalizeLyricDefaultOffsetMs(10L))
        assertEquals(50L, normalizeLyricDefaultOffsetMs(26L))
        assertEquals(-50L, normalizeLyricDefaultOffsetMs(-26L))
    }

    @Test
    fun normalizeLyricDefaultOffsetMs_clampsBounds() {
        assertEquals(MAX_LYRIC_DEFAULT_OFFSET_MS, normalizeLyricDefaultOffsetMs(MAX_LYRIC_DEFAULT_OFFSET_MS + 100L))
        assertEquals(MIN_LYRIC_DEFAULT_OFFSET_MS, normalizeLyricDefaultOffsetMs(MIN_LYRIC_DEFAULT_OFFSET_MS - 100L))
    }

    @Test
    fun resolveLyricDefaultOffsetMs_prefersQqSettingForQqLyrics() {
        assertEquals(
            DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
            resolveLyricDefaultOffsetMs(
                lyricSource = MusicPlatform.QQ_MUSIC,
                cloudMusicDefaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
                qqMusicDefaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
            )
        )
        assertEquals(
            DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
            resolveLyricDefaultOffsetMs(
                lyricSource = MusicPlatform.CLOUD_MUSIC,
                cloudMusicDefaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
                qqMusicDefaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
            )
        )
    }

    @Test
    fun resolveEffectiveLyricOffsetMs_combinesPlatformDefaultAndUserOffset() {
        assertEquals(
            1_250L,
            resolveEffectiveLyricOffsetMs(
                lyricSource = MusicPlatform.CLOUD_MUSIC,
                cloudMusicDefaultOffsetMs = 1_000L,
                qqMusicDefaultOffsetMs = 500L,
                userLyricOffsetMs = 250L,
            )
        )
        assertEquals(
            -100L,
            resolveEffectiveLyricOffsetMs(
                lyricSource = MusicPlatform.QQ_MUSIC,
                cloudMusicDefaultOffsetMs = 1_000L,
                qqMusicDefaultOffsetMs = 500L,
                userLyricOffsetMs = -600L,
            )
        )
    }

    @Test
    fun resolveEffectiveLyricOffsetMs_saturatesOverflow() {
        assertEquals(
            Long.MAX_VALUE,
            resolveEffectiveLyricOffsetMs(
                lyricSource = MusicPlatform.CLOUD_MUSIC,
                cloudMusicDefaultOffsetMs = 1_000L,
                qqMusicDefaultOffsetMs = 500L,
                userLyricOffsetMs = Long.MAX_VALUE,
            )
        )
        assertEquals(
            Long.MIN_VALUE,
            resolveEffectiveLyricOffsetMs(
                lyricSource = MusicPlatform.QQ_MUSIC,
                cloudMusicDefaultOffsetMs = 1_000L,
                qqMusicDefaultOffsetMs = -1_000L,
                userLyricOffsetMs = Long.MIN_VALUE,
            )
        )
    }

    @Test
    fun shouldRebaseLyricOffsetForSource_onlyTouchesCustomizedSongsOfTheTargetSource() {
        assertTrue(
            shouldRebaseLyricOffsetForSource(
                lyricSource = MusicPlatform.CLOUD_MUSIC,
                targetSource = MusicPlatform.CLOUD_MUSIC,
                userOffsetMs = 100L
            )
        )
        assertFalse(
            shouldRebaseLyricOffsetForSource(
                lyricSource = MusicPlatform.CLOUD_MUSIC,
                targetSource = MusicPlatform.CLOUD_MUSIC,
                userOffsetMs = 0L
            )
        )
        assertFalse(
            shouldRebaseLyricOffsetForSource(
                lyricSource = MusicPlatform.QQ_MUSIC,
                targetSource = MusicPlatform.CLOUD_MUSIC,
                userOffsetMs = 100L
            )
        )
    }

    @Test
    fun rebaseLyricUserOffsetMs_preservesAbsoluteTiming() {
        val rebased = rebaseLyricUserOffsetMs(
            userOffsetMs = 250L,
            previousDefaultOffsetMs = 1000L,
            newDefaultOffsetMs = 700L
        )

        assertEquals(550L, rebased)
        assertEquals(1_250L, 1_000L + 250L)
        assertEquals(1_250L, 700L + rebased)
    }

    @Test
    fun rebaseLyricUserOffsetMs_saturatesOverflow() {
        assertEquals(
            Long.MAX_VALUE,
            rebaseLyricUserOffsetMs(
                userOffsetMs = Long.MAX_VALUE,
                previousDefaultOffsetMs = 1_000L,
                newDefaultOffsetMs = -1_000L
            )
        )
        assertEquals(
            Long.MIN_VALUE,
            rebaseLyricUserOffsetMs(
                userOffsetMs = Long.MIN_VALUE,
                previousDefaultOffsetMs = -1_000L,
                newDefaultOffsetMs = 1_000L
            )
        )
    }
}
