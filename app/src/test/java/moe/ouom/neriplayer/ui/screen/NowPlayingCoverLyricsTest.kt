package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingSyncedLyricContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingSecondaryLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingLyricsPosition
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldAdvanceNowPlayingLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldShowNowPlayingEmbeddedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCoverLyricsTest {
    @Test
    fun `embedded lyrics require portrait enabled mode and lyric content`() {
        assertTrue(shouldShowNowPlayingEmbeddedLyrics(false, true, true))
        assertFalse(shouldShowNowPlayingEmbeddedLyrics(true, true, true))
        assertFalse(shouldShowNowPlayingEmbeddedLyrics(false, false, true))
        assertFalse(shouldShowNowPlayingEmbeddedLyrics(false, true, false))
    }

    @Test
    fun `secondary lyrics and preview position follow the current selection`() {
        val secondary = emptyList<LyricEntry>()
        assertSame(secondary, nowPlayingSecondaryLyrics(true, secondary))
        assertNull(nowPlayingSecondaryLyrics(false, secondary))
        assertEquals(125L, resolveNowPlayingLyricsPosition(125L, 500L))
        assertEquals(500L, resolveNowPlayingLyricsPosition(null, 500L))
        assertTrue(shouldAdvanceNowPlayingLyrics(true, null))
        assertFalse(shouldAdvanceNowPlayingLyrics(true, 125L))
        assertFalse(shouldAdvanceNowPlayingLyrics(true, 0L))
        assertFalse(shouldAdvanceNowPlayingLyrics(false, null))
        assertFalse(shouldAdvanceNowPlayingLyrics(false, 125L))
    }

    @Test
    fun `synced lyric content retains session and hides phonetic duplicate lines`() {
        val lines = emptyList<LyricEntry>()
        val song = SongItem(
            id = 8L,
            name = "Lyrics",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 5_000L,
            coverUrl = null
        )
        val missing = buildNowPlayingSyncedLyricContent(lines, lines, false, false, null, 30L)
        assertNull(missing.playbackSessionKey)
        assertNull(missing.translatedLines)
        assertFalse(missing.showTranslatedLines)
        val translated = buildNowPlayingSyncedLyricContent(lines, lines, true, false, song, 30L)
        assertEquals(song.stableKey(), translated.playbackSessionKey)
        assertSame(lines, translated.translatedLines)
        assertTrue(translated.showTranslatedLines)
        assertFalse(buildNowPlayingSyncedLyricContent(lines, lines, true, true, song, 30L).showTranslatedLines)
        assertNull(buildNowPlayingSyncedLyricContent(lines, lines, false, true, song, 30L).translatedLines)
    }
}
