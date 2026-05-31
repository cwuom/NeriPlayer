package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerSearchMetadataTest {

    @Test
    fun `downloaded song replacement uses custom override`() {
        val originalSong = SongItem(
            id = 1L,
            name = "旧标题",
            artist = "旧歌手",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = "old-cover",
            mediaUri = "content://song"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "新标题",
            singer = "新歌手",
            coverUrl = "new-cover",
            lyric = "[00:00.00]歌词",
            translatedLyric = null,
            matchedSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "123",
            useCustomOverride = true
        )

        assertEquals("旧标题", updatedSong.name)
        assertEquals("旧歌手", updatedSong.artist)
        assertEquals("新标题", updatedSong.customName)
        assertEquals("新歌手", updatedSong.customArtist)
        assertEquals("new-cover", updatedSong.customCoverUrl)
        assertEquals("123", updatedSong.matchedSongId)
    }

    @Test
    fun `remote song replacement rewrites base metadata`() {
        val originalSong = SongItem(
            id = 2L,
            name = "旧标题",
            artist = "旧歌手",
            album = "云音乐",
            albumId = 10L,
            durationMs = 1000L,
            coverUrl = "old-cover",
            mediaUri = "https://example.com/audio.mp3"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "新标题",
            singer = "新歌手",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "456",
            useCustomOverride = false
        )

        assertEquals("新标题", updatedSong.name)
        assertEquals("新歌手", updatedSong.artist)
        assertEquals("new-cover", updatedSong.coverUrl)
        assertNull(updatedSong.customName)
        assertNull(updatedSong.customArtist)
        assertNull(updatedSong.customCoverUrl)
    }

    @Test
    fun `withUpdatedLyricsPreservingOriginal migrates legacy matched lyrics before override`() {
        val updatedSong = SongItem(
            id = 3L,
            name = "标题",
            artist = "歌手",
            album = "专辑",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = null,
            matchedLyric = "[00:00.00]旧原文",
            matchedTranslatedLyric = "[00:00.00]旧译文"
        ).withUpdatedLyricsPreservingOriginal(
            newLyrics = "[00:00.00]新原文",
            newTranslatedLyric = "[00:00.00]新译文"
        )

        assertEquals("[00:00.00]新原文", updatedSong.matchedLyric)
        assertEquals("[00:00.00]新译文", updatedSong.matchedTranslatedLyric)
        assertEquals("[00:00.00]旧原文", updatedSong.originalLyric)
        assertEquals("[00:00.00]旧译文", updatedSong.originalTranslatedLyric)
    }

    @Test
    fun `auto lyric matching is allowed only for unmatched youtube music songs`() {
        val song = SongItem(
            id = 4L,
            name = "标题",
            artist = "歌手",
            album = "YouTube Music",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = null
        )

        assertTrue(shouldAutoMatchExternalLyrics(song, isYouTubeMusicTrack = true))
        assertFalse(shouldAutoMatchExternalLyrics(song, isYouTubeMusicTrack = false))
    }

    @Test
    fun `auto lyric matching skips songs with existing or custom metadata`() {
        val song = SongItem(
            id = 5L,
            name = "标题",
            artist = "歌手",
            album = "YouTube Music",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = null
        )

        assertFalse(
            shouldAutoMatchExternalLyrics(
                song.copy(matchedLyric = "[00:00.00]已有歌词"),
                isYouTubeMusicTrack = true
            )
        )
        assertFalse(
            shouldAutoMatchExternalLyrics(
                song.copy(matchedSongId = "123"),
                isYouTubeMusicTrack = true
            )
        )
        assertFalse(
            shouldAutoMatchExternalLyrics(
                song.copy(customName = "手动标题"),
                isYouTubeMusicTrack = true
            )
        )
    }
}
