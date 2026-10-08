package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.naming.ParsedManagedDownloadFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalAudioImportFileNameIdentityTest {
    private val downloads: LocalMediaDownloadAccess = mock(LocalMediaDownloadAccess::class.java)

    @Before
    fun bindDownloads() {
        doReturn(listOf(TEMPLATE)).`when`(downloads).candidateFileNameTemplates(isNull())
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { true })
    }

    @Test
    fun `common managed file names are parsed by where the source sits`() {
        assertEquals(
            ParsedManagedDownloadFileName(source = "NetEase", artist = "Artist", title = "Song - Part 2"),
            parseCommon("NetEase - Artist - Song - Part 2")
        )
        assertEquals(
            ParsedManagedDownloadFileName(title = "Long - Song", artist = "Artist", album = "Album", source = "bilibili"),
            parseCommon("Long - Song - Artist - Album - bilibili")
        )
        assertEquals(
            ParsedManagedDownloadFileName(title = "Song", artist = "Artist", source = "YouTube"),
            parseCommon(" Song - Artist - YouTube ")
        )
        assertEquals(
            ParsedManagedDownloadFileName(title = "Song", artist = "Artist", album = "NeriPlayer-Download"),
            parseCommon("Song - Artist - NeriPlayer-Download")
        )
    }

    @Test
    fun `file names without a managed source or album are not parsed`() {
        assertNull(parseCommon("Song - Artist - Album"))
        assertNull(parseCommon("Song - Artist - NetEase Album - Extra"))
        assertNull(parseCommon("Song - Artist - Album - neriplayer-download"))
        assertNull(parseCommon("Artist - netease"))
        assertNull(parseCommon("Song -  - netease"))
    }

    @Test
    fun `parsed titles replace blank file named or joined titles only`() {
        val parsed = ParsedManagedDownloadFileName(title = "Song", artist = "Artist", album = "Album", source = "netease")

        assertNull(titleFallback("Track", null))
        assertNull(titleFallback("", parsed.copy(title = "content://media/1")))
        assertEquals("Song", titleFallback("  ", parsed))
        assertEquals("Song", titleFallback(" MY   file ", parsed))
        assertEquals("Song", titleFallback("Fallback", parsed))
        assertEquals("Song", titleFallback("artist - song", parsed))
        assertEquals("Song", titleFallback("NetEase - Artist - Song", parsed))
        assertEquals("Song", titleFallback("Album - Song", parsed))
        assertNull(titleFallback("Another Song", parsed))
        assertNull(titleFallback("Artist - Song", ParsedManagedDownloadFileName(title = "Song")))
    }

    @Test
    fun `parsed albums replace blank fallback or local albums only`() {
        val parsed = ParsedManagedDownloadFileName(album = "Parsed Album")

        assertNull(albumFallback("", null))
        assertNull(albumFallback("", ParsedManagedDownloadFileName(album = " ")))
        assertEquals("Parsed Album", albumFallback(null, parsed))
        assertEquals("Parsed Album", albumFallback(" local   FILES ", parsed))
        assertEquals("Parsed Album", albumFallback(LocalSongSupport.LOCAL_ALBUM_IDENTITY, parsed))
        assertNull(albumFallback("Real Album", parsed))
    }

    @Test
    fun `file name identity fills placeholder artist album and file named title`() {
        val song = song(
            name = "Song - Artist - Album - netease",
            artist = "<Unknown>",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            localFileName = "Song - Artist - Album - netease.flac"
        )

        val repaired = LocalAudioImportManager.repairQuickIdentityFromFileName(song)

        assertEquals("Song", repaired.name)
        assertEquals("Artist", repaired.artist)
        assertEquals("Album", repaired.album)
        assertEquals("Artist", repaired.originalArtist)
    }

    @Test
    fun `file name identity keeps readable titles and known artists`() {
        val song = song(
            name = "Real Title",
            artist = "Known",
            album = "unknown album",
            localFilePath = "/music/Song - Artist - youtube.mp3"
        )

        val repaired = LocalAudioImportManager.repairQuickIdentityFromFileName(song)

        assertEquals("Real Title", repaired.name)
        assertEquals("Known", repaired.artist)
        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, repaired.album)
        assertNull(repaired.originalArtist)
    }

    @Test
    fun `file name identity prefers download templates and skips songs without names`() {
        doReturn(ParsedManagedDownloadFileName(title = " ", artist = ""))
            .`when`(downloads).parseBaseName("ignored", TEMPLATE)
        doReturn(ParsedManagedDownloadFileName(title = "Templated", artist = "Template Artist"))
            .`when`(downloads).parseBaseName("templated name", TEMPLATE)
        val unnamed = song(name = "", artist = "unknown", album = "", mediaUri = "content://media/")

        assertSame(unnamed, LocalAudioImportManager.repairQuickIdentityFromFileName(unnamed))
        assertNull(LocalAudioImportManager.parseFileNameMetadata(" .flac"))
        assertNull(LocalAudioImportManager.parseFileNameMetadata("ignored.mp3"))
        val repaired = LocalAudioImportManager.repairQuickIdentityFromFileName(
            song(name = "", artist = "unknown", album = "Album", mediaUri = "content://media/templated name.ogg")
        )
        assertEquals("Templated", repaired.name)
        assertEquals("Template Artist", repaired.artist)
        assertEquals("Album", repaired.album)
    }

    @Test
    fun `identity probes are needed for placeholders blanks and file named titles`() {
        val complete = song(name = "Title", artist = "Artist", album = "Album", localFileName = "track.mp3")

        assertFalse(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(artist = " ")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(artist = "Unknown Artist")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(name = "")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(name = "<unknown>")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(name = " TRACK ")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(album = "")))
        assertTrue(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(album = "unknown album")))
        assertTrue(
            LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY))
        )
        assertFalse(LocalAudioImportManager.needsLocalIdentityMetadataProbe(complete.copy(localFileName = ".mp3")))
    }

    private fun parseCommon(baseName: String) = LocalAudioImportManager.parseCommonManagedDownloadFileName(baseName)

    private fun titleFallback(current: String?, parsed: ParsedManagedDownloadFileName?) =
        LocalAudioImportManager.resolveParsedTitleFallback(
            currentTitle = current,
            fallbackTitle = "Fallback",
            fileTitle = "My File",
            parsed = parsed
        )

    private fun albumFallback(current: String?, parsed: ParsedManagedDownloadFileName?) =
        LocalAudioImportManager.resolveParsedAlbumFallback(
            currentAlbum = current,
            fallbackAlbum = "Local Files",
            parsed = parsed
        )

    private fun song(
        name: String,
        artist: String,
        album: String,
        localFileName: String? = null,
        localFilePath: String? = null,
        mediaUri: String? = null
    ) = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFileName = localFileName,
        localFilePath = localFilePath
    )

    private companion object {
        const val TEMPLATE = "{title}"
    }
}
