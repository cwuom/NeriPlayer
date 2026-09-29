package moe.ouom.neriplayer.core.download.catalog.assembly

import moe.ouom.neriplayer.core.download.model.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.catalog.fallbackDownloadedSongId
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedSongAssemblerTest {
    private val file = DownloadedSongFileInfo(
        reference = "/downloads/song.flac", playbackUri = "file:///downloads/song.flac",
        logicalName = "Artist - Song.flac", sizeBytes = 42L, downloadTime = 123L,
        parsedTitle = "Song", parsedArtist = "Artist"
    )
    private val completeMetadata = DownloadedAudioMetadata(
        songId = 7L, name = "Stored title", artist = "Stored artist", album = "Stored album",
        originalName = "Original title", originalArtist = "Original artist", durationMs = 456L,
        stableKey = "7|netease|"
    )
    private val cover = DownloadedSongCoverInfo("/cover.jpg", "cover-url", "custom-url", "original-url")
    private val local = DownloadedSongLocalMetadata(
        title = "Local title", artist = "Local artist", album = "Local album", durationMs = 789L,
        coverUri = "/embedded.jpg", lyricContent = "local lyrics", originalTitle = "Local original title",
        originalArtist = "Local original artist", sourceStableKey = "local-key"
    )

    @Test
    fun `complete metadata preserves every source and override without local IO`() {
        val metadata = completeMetadata.copy(
            matchedLyric = "matched", matchedTranslatedLyric = "translated", matchedRomanizedLyric = "romanized",
            matchedLyricSource = "AMLL", matchedSongId = "matched-id", userLyricOffsetMs = 321L,
            customName = "Custom title", customArtist = "Custom artist", originalLyric = "original",
            originalTranslatedLyric = "original translation", originalRomanizedLyric = "original romanization",
            identityAlbum = "remote-album", mediaUri = "source-uri", channelId = "channel",
            audioId = "audio-id", subAudioId = "sub-id", playlistContextId = "playlist"
        )
        val song = assembler(metadata, readLocal = { throw AssertionError("unexpected local inspection") }).assemble()

        assertEquals(7L, song.id)
        assertEquals("Stored title", song.name)
        assertEquals("Stored artist", song.artist)
        assertEquals("Stored album", song.album)
        assertEquals(file.reference, song.filePath)
        assertEquals(file.playbackUri, song.mediaUri)
        assertEquals(file.logicalName, song.localFileName)
        assertEquals(42L, song.fileSize)
        assertEquals(123L, song.downloadTime)
        assertEquals(456L, song.durationMs)
        assertEquals("7|netease|", song.stableKey)
        assertEquals("/cover.jpg", song.coverPath)
        assertEquals("cover-url", song.coverUrl)
        assertEquals("custom-url", song.customCoverUrl)
        assertEquals("original-url", song.originalCoverUrl)
        assertEquals("matched", song.matchedLyric)
        assertEquals("translated", song.matchedTranslatedLyric)
        assertEquals("romanized", song.matchedRomanizedLyric)
        assertEquals("AMLL", song.matchedLyricSource)
        assertEquals("matched-id", song.matchedSongId)
        assertEquals(321L, song.userLyricOffsetMs)
        assertEquals("Custom title", song.customName)
        assertEquals("Custom artist", song.customArtist)
        assertEquals("Original title", song.originalName)
        assertEquals("Original artist", song.originalArtist)
        assertEquals("original", song.originalLyric)
        assertEquals("original translation", song.originalTranslatedLyric)
        assertEquals("original romanization", song.originalRomanizedLyric)
        assertEquals("remote-album", song.sourceIdentityAlbum)
        assertEquals("source-uri", song.sourceMediaUri)
        assertEquals("channel", song.sourceChannelId)
        assertEquals("audio-id", song.sourceAudioId)
        assertEquals("sub-id", song.sourceSubAudioId)
        assertEquals("playlist", song.sourcePlaylistContextId)
    }

    @Test
    fun `missing metadata uses local tags exactly once across all fallback fields`() {
        var reads = 0
        val song = assembler(null, cover.copy(reference = null), loadLyrics = true) {
            reads += 1
            local
        }.assemble()

        assertEquals(1, reads)
        assertEquals(fallbackDownloadedSongId(file.reference), song.id)
        assertEquals("Local title", song.name)
        assertEquals("Local artist", song.artist)
        assertEquals("Local album", song.album)
        assertEquals(789L, song.durationMs)
        assertEquals("/embedded.jpg", song.coverPath)
        assertEquals("local lyrics", song.matchedLyric)
        assertEquals("Local original title", song.originalName)
        assertEquals("Local original artist", song.originalArtist)
        assertEquals("local-key", song.stableKey)
    }

    @Test
    fun `fast hydration never reads local tags even when all metadata is missing`() {
        val song = assembler(
            null, cover.copy(reference = null), loadLyrics = true, allowSlow = false,
            readLocal = { throw AssertionError("fast hydration inspected audio") }
        ).assemble()

        assertEquals("Song", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Local files", song.album)
        assertEquals(0L, song.durationMs)
        assertNull(song.coverPath)
        assertNull(song.matchedLyric)
        assertNull(song.stableKey)
    }

    @Test
    fun `failed local inspection is cached and falls back to filename`() {
        var reads = 0
        val song = assembler(null, cover.copy(reference = null), loadLyrics = true) {
            reads += 1
            null
        }.assemble()

        assertEquals(1, reads)
        assertEquals("Song", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Local files", song.album)
        assertEquals(0L, song.durationMs)
    }

    @Test
    fun `blank metadata fields fall back without treating an existing sidecar as absent`() {
        val song = assembler(DownloadedAudioMetadata(name = " ", artist = "", album = " ")) { local }.assemble()

        assertEquals("Local title", song.name)
        assertEquals("Local artist", song.artist)
        assertEquals("Local files", song.album)
        assertEquals(789L, song.durationMs)
    }

    @Test
    fun `album fallback retains metadata identity including an explicit empty identity`() {
        listOf(null to "Local files", LocalSongSupport.LOCAL_ALBUM_IDENTITY to "Local files",
            "Remote album" to "Remote album", "" to "").forEach { (identity, expected) ->
            val song = assembler(DownloadedAudioMetadata(identityAlbum = identity)) { local }.assemble()
            assertEquals(expected, song.album)
        }
    }

    @Test
    fun `empty local tags use filename and localized default album`() {
        val song = assembler(null) { DownloadedSongLocalMetadata(title = " ", artist = " ", album = " ") }.assemble()
        assertEquals("Song", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Local files", song.album)
    }

    @Test
    fun `complete metadata can inspect missing lyrics without replacing metadata identity`() {
        var reads = 0
        val song = assembler(completeMetadata, loadLyrics = true) { reads += 1; local }.assemble()
        assertEquals(1, reads)
        assertEquals("local lyrics", song.matchedLyric)
        assertEquals(7L, song.id)
        assertEquals("Stored title", song.name)
        assertEquals(456L, song.durationMs)
    }

    @Test
    fun `explicit blank lyric suppresses missing lyric inspection`() {
        val song = assembler(
            completeMetadata.copy(matchedLyric = ""), loadLyrics = true,
            readLocal = { throw AssertionError("blank override must prevent local inspection") }
        ).assemble()
        assertEquals("", song.matchedLyric)
    }

    @Test
    fun `remote source metadata recovers its stable key`() {
        val song = assembler(completeMetadata.copy(stableKey = null, channelId = "netease", audioId = "123")) {
            throw AssertionError("complete metadata must not inspect local source")
        }.assemble()
        assertEquals("123|netease|", song.stableKey)
    }

    private fun assembler(
        metadata: DownloadedAudioMetadata?,
        covers: DownloadedSongCoverInfo = cover,
        loadLyrics: Boolean = false,
        allowSlow: Boolean = true,
        readLocal: () -> DownloadedSongLocalMetadata? = { null }
    ) = DownloadedSongAssembler(
        metadata, file, covers, DownloadedSongLyricContent(), loadLyrics, allowSlow,
        defaultAlbum = { "Local files" }, readLocalMetadata = readLocal
    )
}
