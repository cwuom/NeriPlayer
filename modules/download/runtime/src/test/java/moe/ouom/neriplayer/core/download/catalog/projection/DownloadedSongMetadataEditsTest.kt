package moe.ouom.neriplayer.core.download.catalog.projection

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.core.download.catalog.projectDownloadedSongMetadata
import moe.ouom.neriplayer.core.download.catalog.toMetadataPersistenceSong
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedSongMetadataEditsTest {
    @Test
    fun `editing preserves storage ownership while replacing every user override`() {
        val original = remoteDownload().copy(
            originalName = "Original title", originalArtist = "Original artist", originalCoverUrl = "original-cover",
            originalLyric = "original", originalTranslatedLyric = "translation", originalRomanizedLyric = "romanization"
        )
        val update = localEdit().copy(
            album = "  New album  ", coverUrl = "new-cover", customCoverUrl = "  custom-cover  ",
            matchedLyric = "", matchedTranslatedLyric = null, matchedRomanizedLyric = "new romanization",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "match-id", userLyricOffsetMs = -125L,
            customName = "Custom title", customArtist = "Custom artist"
        )

        val result = projectDownloadedSongMetadata(original, update)

        assertEquals(original.copy(
            name = "Edited title", artist = "Edited artist", album = "New album", durationMs = 900L,
            coverUrl = "new-cover", customCoverUrl = "custom-cover", matchedLyric = "",
            matchedTranslatedLyric = null, matchedRomanizedLyric = "new romanization",
            matchedLyricSource = "CLOUD_MUSIC", matchedSongId = "match-id", userLyricOffsetMs = -125L,
            customName = "Custom title", customArtist = "Custom artist"
        ), result)
    }

    @Test
    fun `remote baseline uses existing display values when original tags were never saved`() {
        val result = projectDownloadedSongMetadata(remoteDownload(), localEdit().copy(
            originalName = "Untrusted new baseline", originalArtist = "Untrusted new artist", originalCoverUrl = "new baseline"
        ))
        assertEquals("Downloaded title", result.originalName)
        assertEquals("Downloaded artist", result.originalArtist)
        assertEquals("https://example.com/cover.jpg", result.originalCoverUrl)
    }

    @Test
    fun `explicit empty original tags and lyrics remain authoritative`() {
        val original = remoteDownload().copy(
            originalName = "", originalArtist = "", originalCoverUrl = "",
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        val result = projectDownloadedSongMetadata(original, localEdit().copy(
            originalName = "new", originalArtist = "new", originalCoverUrl = "new",
            originalLyric = "new", originalTranslatedLyric = "new", originalRomanizedLyric = "new"
        ))
        assertEquals(listOf("", "", "", "", "", ""), listOf(result.originalName, result.originalArtist,
            result.originalCoverUrl, result.originalLyric, result.originalTranslatedLyric, result.originalRomanizedLyric))
    }

    @Test
    fun `local baseline accepts edited originals including explicit empty lyrics`() {
        val result = projectDownloadedSongMetadata(localDownload(), localEdit().copy(
            originalName = "new original", originalArtist = "new artist", originalCoverUrl = "new cover",
            originalLyric = "", originalTranslatedLyric = "translated", originalRomanizedLyric = "romanized"
        ))
        assertEquals("new original", result.originalName)
        assertEquals("new artist", result.originalArtist)
        assertEquals("new cover", result.originalCoverUrl)
        assertEquals("", result.originalLyric)
        assertEquals("translated", result.originalTranslatedLyric)
        assertEquals("romanized", result.originalRomanizedLyric)
    }

    @Test
    fun `remote baseline with no cover can recover the original from the edit`() {
        val result = projectDownloadedSongMetadata(remoteDownload().copy(coverUrl = null), localEdit().copy(originalCoverUrl = "new original"))
        assertEquals("new original", result.originalCoverUrl)
    }

    @Test
    fun `local channel identifiers cannot replace a known remote source`() {
        for (channel in listOf(null, "", " ", " LoCaL ")) {
            val original = remoteDownload().copy(sourceChannelId = null, sourceAudioId = null)
            val result = projectDownloadedSongMetadata(original, localEdit().copy(
                channelId = channel, audioId = "local-audio", subAudioId = "local-part"
            ))
            assertEquals(42L, result.id)
            assertEquals("42|netease|", result.stableKey)
            assertEquals("netease", result.sourceChannelId)
            assertEquals("42", result.sourceAudioId)
            assertNull(result.sourceSubAudioId)
        }
    }

    @Test
    fun `existing remote identifiers and playlist context win after normalization`() {
        val original = remoteDownload().copy(sourceChannelId = " netease ", sourceAudioId = " 42 ", sourceSubAudioId = " part ", sourcePlaylistContextId = "")
        val result = projectDownloadedSongMetadata(original, localEdit().copy(channelId = "bilibili", audioId = "99", subAudioId = "new-part", playlistContextId = "new-playlist"))
        assertEquals("netease", result.sourceChannelId)
        assertEquals("42", result.sourceAudioId)
        assertEquals("part", result.sourceSubAudioId)
        assertEquals("", result.sourcePlaylistContextId)
    }

    @Test
    fun `missing remote identifiers can be filled only by an explicit remote channel`() {
        val result = projectDownloadedSongMetadata(localDownload(), localEdit().copy(
            id = 99L, channelId = " netease ", audioId = " 123 ", subAudioId = " part ",
            sourceStableKey = "123|netease|", playlistContextId = "playlist"
        ))
        assertEquals(99L, result.id)
        assertEquals("123|netease|", result.stableKey)
        assertEquals("netease", result.sourceIdentityAlbum)
        assertEquals("netease", result.sourceChannelId)
        assertEquals("123", result.sourceAudioId)
        assertEquals("part", result.sourceSubAudioId)
        assertEquals("playlist", result.sourcePlaylistContextId)
    }

    @Test
    fun `non netease remote identity does not fabricate an audio identifier`() {
        val result = projectDownloadedSongMetadata(localDownload(), localEdit().copy(
            channelId = "youtube_music", audioId = " ", subAudioId = " ", sourceStableKey = "99|youtube_music|https://example.com/video"
        ))
        assertEquals("youtube_music", result.sourceChannelId)
        assertNull(result.sourceAudioId)
        assertNull(result.sourceSubAudioId)
        assertEquals("https://example.com/video", result.sourceMediaUri)
    }

    @Test
    fun `blank album and invalid duration retain stored values while remote playback url is ignored`() {
        for (duration in listOf(0L, -1L)) {
            val original = remoteDownload()
            val result = projectDownloadedSongMetadata(original, localEdit().copy(album = " ", durationMs = duration, mediaUri = "https://example.com/audio", coverUrl = null))
            assertEquals(original.album, result.album)
            assertEquals(original.durationMs, result.durationMs)
            assertEquals(original.mediaUri, result.mediaUri)
            assertEquals(original.localFileName, result.localFileName)
            assertEquals(original.coverUrl, result.coverUrl)
        }
    }

    @Test
    fun `blank custom cover restores local reference while nonblank custom cover retains the sidecar`() {
        for (custom in listOf(null, "", " ")) {
            val result = projectDownloadedSongMetadata(remoteDownload(), localEdit().copy(customCoverUrl = custom, coverUrl = "file:///new.jpg"))
            assertNull(result.customCoverUrl)
            assertEquals("file:///new.jpg", result.coverPath)
        }
        val result = projectDownloadedSongMetadata(remoteDownload(), localEdit().copy(customCoverUrl = " custom ", coverUrl = null))
        assertEquals("custom", result.customCoverUrl)
        assertEquals("/downloads/cover.jpg", result.coverPath)
    }

    @Test
    fun `projected edit remains stable when applied again and survives persistence conversion`() {
        val update = localEdit().copy(customName = "Custom title", matchedLyric = "", originalLyric = "original")
        val once = projectDownloadedSongMetadata(remoteDownload(), update)
        assertEquals(once, projectDownloadedSongMetadata(once, update))
        val persisted = once.toMetadataPersistenceSong(update)
        assertEquals(42L, persisted.id)
        assertEquals("42|netease|", persisted.sourceStableKey)
        assertEquals("netease", persisted.channelId)
        assertEquals("42", persisted.audioId)
        assertEquals("Downloaded title", persisted.originalName)
        assertEquals("", persisted.matchedLyric)
        assertEquals("original", persisted.originalLyric)
        assertEquals("Custom title", persisted.customName)
    }

    private fun remoteDownload() = DownloadedSong(
        id = 42L, name = "Downloaded title", artist = "Downloaded artist", album = "Downloaded album",
        filePath = "/downloads/song.flac", fileSize = 100L, downloadTime = 200L, durationMs = 300L,
        mediaUri = "content://catalog/song.flac", localFileName = "song.flac", coverPath = "/downloads/cover.jpg",
        coverUrl = "https://example.com/cover.jpg", stableKey = "42|netease|", sourceIdentityAlbum = "netease",
        sourceChannelId = "netease", sourceAudioId = "42"
    )

    private fun localDownload() = remoteDownload().copy(
        id = 1L, stableKey = "1|${LocalSongSupport.LOCAL_ALBUM_IDENTITY}|content://catalog/song.flac",
        sourceIdentityAlbum = LocalSongSupport.LOCAL_ALBUM_IDENTITY, sourceChannelId = "local", sourceAudioId = null
    )

    private fun localEdit() = SongItem(
        id = 1L, name = "Edited title", artist = "Edited artist", album = "Edited album", albumId = 0L,
        durationMs = 900L, coverUrl = null, mediaUri = "content://catalog/song.flac", channelId = "local"
    )
}
