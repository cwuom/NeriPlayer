package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingViewModelTest {

    @Test
    fun `buildLocalOriginalSongInfo restores embedded original lyrics when available`() {
        val song = SongItem(
            id = 1L,
            name = "当前标题",
            artist = "当前歌手",
            album = "当前专辑",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = "current-cover",
            mediaUri = "content://song",
            originalName = "原始标题",
            originalArtist = "原始歌手",
            originalCoverUrl = "origin-cover",
            originalLyric = "[00:00.00]原始歌词",
            originalTranslatedLyric = "[00:00.00]原始翻译"
        )

        val info = buildLocalOriginalSongInfo(song)

        assertEquals("原始标题", info.name)
        assertEquals("原始歌手", info.artist)
        assertEquals("origin-cover", info.coverUrl)
        assertFalse(info.shouldClearLyrics)
        assertEquals("[00:00.00]原始歌词", info.lyric)
        assertEquals("[00:00.00]原始翻译", info.translatedLyric)
    }

    @Test
    fun `buildLocalOriginalSongInfo clears lyrics when no original lyrics exist`() {
        val song = SongItem(
            id = 2L,
            name = "当前标题",
            artist = "当前歌手",
            album = "当前专辑",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = "current-cover",
            mediaUri = "content://song"
        )

        val info = buildLocalOriginalSongInfo(song)

        assertTrue(info.shouldClearLyrics)
        assertNull(info.lyric)
        assertNull(info.translatedLyric)
    }
}
