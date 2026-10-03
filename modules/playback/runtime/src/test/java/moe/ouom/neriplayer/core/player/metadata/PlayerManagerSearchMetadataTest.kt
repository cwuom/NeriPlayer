package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.lyrics.parser.resolveStoredLyricText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerSearchMetadataTest {

    @Test
    fun `manual replacement clears the old romanized track while retaining its restoration baseline`() {
        for (customOverride in listOf(false, true)) {
            for (baseline in listOf(null, "", "[00:01.00]first baseline")) {
                val original = SongItem(1L, "song", "artist", "netease", 0L, 1000L, null,
                    matchedLyric = "[00:01.00]old original", matchedTranslatedLyric = "[00:01.00]old translation",
                    matchedRomanizedLyric = "[00:01.00]old romanized", originalRomanizedLyric = baseline,
                    matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "old-match",
                    lyricSyncEdited = false, lyricSyncRevision = 10)

                val matched = applyManualSearchMetadata(original, "new song", "new artist", null,
                    "[00:02.00]new original", "[00:02.00]new translation", MusicPlatform.QQ_MUSIC, "new-match",
                    customOverride, preserveExistingMatchedLyrics = false, lyricRevision = 11)

                assertEquals("[00:02.00]new original", matched.matchedLyric)
                assertEquals("[00:02.00]new translation", matched.matchedTranslatedLyric)
                assertEquals("", matched.matchedRomanizedLyric)
                assertEquals(baseline ?: original.matchedRomanizedLyric, matched.originalRomanizedLyric)
                assertEquals("", resolveStoredLyricText(matched.matchedRomanizedLyric, matched.originalRomanizedLyric))
                assertEquals(emptyList<Any>(), PlayerLyricsProvider.parseConfirmedUserLyricEntries(matched, matched.matchedRomanizedLyric))
                assertEquals(true, matched.lyricSyncEdited)
                assertEquals(11L, matched.lyricSyncRevision)
                assertEquals(MusicPlatform.QQ_MUSIC, matched.matchedLyricSource)
                assertEquals("new-match", matched.matchedSongId)
            }
        }
    }

    @Test
    fun `search summary fallback keeps romanization and the existing edit state in both metadata branches`() {
        for (customOverride in listOf(false, true)) {
            for (romanized in listOf(null, "", "[00:01.00]kept romanized")) {
                for (edited in listOf(null, false, true)) {
                    val original = SongItem(1L, "song", "artist", "netease", 0L, 1000L, null,
                        matchedLyric = "[00:01.00]kept original", matchedTranslatedLyric = "[00:01.00]kept translation",
                        matchedRomanizedLyric = romanized, originalRomanizedLyric = "[00:00.00]baseline romanized",
                        matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "old-match",
                        lyricSyncEdited = edited, lyricSyncRevision = 10)

                    val matched = applyManualSearchMetadata(original, "new song", "new artist", null,
                        null, null, MusicPlatform.QQ_MUSIC, "new-match", customOverride,
                        preserveExistingMatchedLyrics = true, lyricRevision = 11)

                    assertEquals(original.matchedLyric, matched.matchedLyric)
                    assertEquals(original.matchedTranslatedLyric, matched.matchedTranslatedLyric)
                    assertEquals(romanized, matched.matchedRomanizedLyric)
                    assertEquals(original.originalRomanizedLyric, matched.originalRomanizedLyric)
                    assertEquals(edited, matched.lyricSyncEdited)
                    assertEquals(10L, matched.lyricSyncRevision)
                    assertEquals(original.matchedLyricSource, matched.matchedLyricSource)
                    assertEquals(original.matchedSongId, matched.matchedSongId)
                }
            }
        }
    }

    @Test
    fun `manual matching preserves cached romanized baseline for later lyric restoration`() {
        val original = SongItem(1L, "song", "artist", "netease", 0L, 1L, null,
            matchedRomanizedLyric = "cached romanized", lyricSyncEdited = false)
        for (customOverride in listOf(false, true)) {
            val matched = applyManualSearchMetadata(
                original, "new song", "new artist", null, "selected lyric", null,
                MusicPlatform.CLOUD_MUSIC, "matched", customOverride
            )
            assertEquals("cached romanized", matched.originalRomanizedLyric)
            assertTrue(matched.lyricSyncEdited == true)
        }
    }

    @Test
    fun `search summary fallback keeps selected display metadata`() {
        val selectedSong = SongSearchInfo(
            id = "remote-id",
            songName = "Selected title",
            singer = "Selected artist",
            duration = "03:30",
            source = MusicPlatform.CLOUD_MUSIC,
            albumName = "Selected album",
            coverUrl = "https://example.com/selected.jpg"
        )

        val details = selectedSong.toBasicSongDetails()

        assertEquals("remote-id", details.id)
        assertEquals("Selected title", details.songName)
        assertEquals("Selected artist", details.singer)
        assertEquals("Selected album", details.album)
        assertEquals("https://example.com/selected.jpg", details.coverUrl)
        assertNull(details.lyric)
        assertNull(details.translatedLyric)
    }

    @Test
    fun `search summary fallback preserves existing matched lyrics`() {
        val originalSong = SongItem(
            id = 1L,
            name = "Old title",
            artist = "Old artist",
            album = "netease:album",
            albumId = 2L,
            durationMs = 1000L,
            coverUrl = null,
            matchedLyric = "[00:00.00]old lyric",
            matchedTranslatedLyric = "[00:00.00]old translation",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "old-id"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "New title",
            singer = "New artist",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "new-id",
            useCustomOverride = false,
            preserveExistingMatchedLyrics = true
        )

        assertEquals("New title", updatedSong.name)
        assertEquals("[00:00.00]old lyric", updatedSong.matchedLyric)
        assertEquals("[00:00.00]old translation", updatedSong.matchedTranslatedLyric)
        assertEquals(MusicPlatform.CLOUD_MUSIC, updatedSong.matchedLyricSource)
        assertEquals("old-id", updatedSong.matchedSongId)
    }

    @Test
    fun `search summary fallback preserves explicitly cleared lyrics`() {
        val originalSong = SongItem(
            id = 11L,
            name = "Old title",
            artist = "Old artist",
            album = "netease:album",
            albumId = 12L,
            durationMs = 1000L,
            coverUrl = null,
            matchedLyric = "",
            matchedTranslatedLyric = "",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "old-id",
            originalLyric = "[00:00.00]old lyric",
            originalTranslatedLyric = "[00:00.00]old translation"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "New title",
            singer = "New artist",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "new-id",
            useCustomOverride = false,
            preserveExistingMatchedLyrics = true
        )

        assertEquals("", updatedSong.matchedLyric)
        assertEquals("", updatedSong.matchedTranslatedLyric)
        assertEquals(MusicPlatform.CLOUD_MUSIC, updatedSong.matchedLyricSource)
        assertEquals("old-id", updatedSong.matchedSongId)
        assertEquals(
            "",
            resolveStoredLyricText(updatedSong.matchedLyric, updatedSong.originalLyric)
        )
    }

    @Test
    fun `automatic metadata matching requires usable lyrics`() {
        val details = SongDetails(
            id = "id",
            songName = "title",
            singer = "artist",
            album = "album",
            coverUrl = null,
            lyric = null,
            translatedLyric = null
        )

        assertFalse(details.hasUsableLyrics())
        assertFalse(details.copy(lyric = "", translatedLyric = " ").hasUsableLyrics())
        assertTrue(details.copy(lyric = "[00:00.00]lyric").hasUsableLyrics())
        assertTrue(details.copy(translatedLyric = "[00:00.00]translation").hasUsableLyrics())
    }

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
    fun `downloaded song replacement overwrites existing custom display metadata`() {
        val originalSong = SongItem(
            id = 9L,
            name = "文件标题",
            artist = "文件歌手",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = "old-cover",
            mediaUri = "content://song",
            customName = "旧匹配标题",
            customArtist = "旧匹配歌手",
            customCoverUrl = "old-custom-cover"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "新匹配标题",
            singer = "新匹配歌手",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "qq:new",
            useCustomOverride = true
        )

        assertEquals("新匹配标题", updatedSong.displayName())
        assertEquals("新匹配歌手", updatedSong.displayArtist())
        assertEquals("new-cover", updatedSong.displayCoverUrl())
        assertEquals("qq:new", updatedSong.matchedSongId)
    }

    @Test
    fun `remote song replacement rewrites base metadata and text display`() {
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
        assertEquals("新标题", updatedSong.displayName())
        assertEquals("新歌手", updatedSong.displayArtist())
        assertEquals("new-cover", updatedSong.displayCoverUrl())
    }

    @Test
    fun `remote song replacement clears existing custom display metadata`() {
        val originalSong = SongItem(
            id = 6L,
            name = "夜曲",
            artist = "周杰伦",
            album = "云音乐",
            albumId = 10L,
            durationMs = 1000L,
            coverUrl = "old-cover",
            mediaUri = "https://example.com/audio.mp3",
            customName = "周杰伦 - 夜曲",
            customArtist = "Jay Chou",
            customCoverUrl = "custom-cover"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "夜曲",
            singer = "周杰伦",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "qq:456",
            useCustomOverride = false
        )

        assertEquals("夜曲", updatedSong.name)
        assertEquals("周杰伦", updatedSong.artist)
        assertEquals("new-cover", updatedSong.coverUrl)
        assertNull(updatedSong.customName)
        assertNull(updatedSong.customArtist)
        assertNull(updatedSong.customCoverUrl)
        assertEquals("夜曲", updatedSong.displayName())
        assertEquals("周杰伦", updatedSong.displayArtist())
        assertEquals("new-cover", updatedSong.displayCoverUrl())
        assertEquals("qq:456", updatedSong.matchedSongId)
    }

    @Test
    fun `remote song replacement clears stale copied custom display metadata`() {
        val originalSong = SongItem(
            id = 8L,
            name = "夜曲",
            artist = "周杰伦",
            album = "云音乐",
            albumId = 10L,
            durationMs = 1000L,
            coverUrl = "old-cover",
            mediaUri = "https://example.com/audio.mp3",
            customName = "周杰伦 - 夜曲",
            customArtist = "Jay Chou",
            originalName = "周杰伦 - 夜曲",
            originalArtist = "Jay Chou"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "夜曲",
            singer = "周杰伦",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "qq:stale",
            useCustomOverride = false
        )

        assertNull(updatedSong.customName)
        assertNull(updatedSong.customArtist)
        assertEquals("夜曲", updatedSong.displayName())
        assertEquals("周杰伦", updatedSong.displayArtist())
    }

    @Test
    fun `remote song replacement clears stale copied custom cover when new cover is available`() {
        val originalSong = SongItem(
            id = 7L,
            name = "明明就",
            artist = "周杰伦",
            album = "云音乐",
            albumId = 10L,
            durationMs = 1000L,
            coverUrl = "new-cover",
            mediaUri = "https://example.com/audio.mp3",
            customCoverUrl = "old-cover",
            originalCoverUrl = "old-cover"
        )

        val updatedSong = applyManualSearchMetadata(
            originalSong = originalSong,
            songName = "明明就",
            singer = "周杰伦",
            coverUrl = "new-cover",
            lyric = null,
            translatedLyric = null,
            matchedSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "qq:789",
            useCustomOverride = false
        )

        assertEquals("new-cover", updatedSong.coverUrl)
        assertNull(updatedSong.customCoverUrl)
        assertEquals("new-cover", updatedSong.displayCoverUrl())
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
        assertTrue(updatedSong.lyricSyncEdited == true)
        assertTrue(updatedSong.lyricSyncRevision > 0L)
    }

    @Test
    fun `automatic lyric cache update does not create an edit revision`() {
        val song = SongItem(id = 1L, name = "song", artist = "artist", album = "netease", albumId = 0L, durationMs = 1L, coverUrl = null)
        val updated = song.withUpdatedLyricsPreservingOriginal(newLyrics = "cached", userEdited = false)
        assertEquals(false, updated.lyricSyncEdited)
        assertEquals(0L, updated.lyricSyncRevision)
    }

    @Test
    fun `manual lyric revision remains monotonic after clock moves backwards`() {
        val song = SongItem(id = 1L, name = "song", artist = "artist", album = "netease", albumId = 0L, durationMs = 1L, coverUrl = null,
            lyricSyncRevision = Long.MAX_VALUE - 1000L)
        val updated = song.withUpdatedLyricsPreservingOriginal(newLyrics = "edit")
        assertEquals(Long.MAX_VALUE - 999L, updated.lyricSyncRevision)
    }

    @Test
    fun `youtube music skips cross platform automatic lyric matching`() {
        val song = SongItem(
            id = 4L,
            name = "标题",
            artist = "歌手",
            album = "YouTube Music",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = null
        )

        assertFalse(shouldAutoMatchExternalLyrics(song, isYouTubeMusicTrack = true))
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
