package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import moe.ouom.neriplayer.data.model.local.LocalMediaDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.security.MessageDigest

class LocalMediaDetailsSongMappingTest {
    @Test
    fun `file backed details keep their path identity and play through the content uri`() {
        val song = LocalMediaSupport.toSongItemImpl(
            details(source = uri("content://media/external/audio/media/5"), filePath = "/music/a.flac")
        )

        assertEquals(stableId("/music/a.flac"), song.id)
        assertEquals(song.id.toString(), song.audioId)
        assertEquals("content://media/external/audio/media/5", song.mediaUri)
        assertEquals("/music/a.flac", song.localFilePath)
        assertEquals("a.flac", song.localFileName)
        assertEquals("local", song.channelId)
        assertEquals("Title", song.originalName)
        assertEquals("Artist", song.originalArtist)
        assertEquals("/covers/a.jpg", song.originalCoverUrl)
        assertEquals("[00:01]lyric", song.matchedLyric)
        assertEquals("[00:01]lyric", song.originalLyric)
        assertEquals("[00:01]translation", song.originalTranslatedLyric)
    }

    @Test
    fun `details without a usable path use the source uri as identity and original tags`() {
        val song = LocalMediaSupport.toSongItemImpl(
            details(
                source = uri("file:///music/b.flac"),
                filePath = "  ",
                originalTitle = "Original Title",
                originalArtist = "Original Artist"
            )
        )

        assertEquals(stableId("file:///music/b.flac"), song.id)
        assertEquals("file:///music/b.flac", song.mediaUri)
        assertEquals("Original Title", song.originalName)
        assertEquals("Original Artist", song.originalArtist)
    }

    @Test
    fun `details without any reference fall back to the empty source`() {
        val song = LocalMediaSupport.toSongItemImpl(details(source = uri(""), filePath = null))

        assertEquals(stableId(""), song.id)
        assertEquals("", song.mediaUri)
    }

    @Test
    fun `albums follow the fallback and managed source rules`() {
        val fallback = details(source = uri("file:///music/c.flac"), filePath = "/music/c.flac", usesFallbackAlbum = true)
        val managed = details(
            source = uri("file:///music/d.flac"),
            filePath = "/music/d.flac",
            album = "Netease-Night Album",
            sourceStableKey = "123|netease|"
        )

        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, LocalMediaSupport.toSongItemImpl(fallback).album)
        assertEquals("Night Album", LocalMediaSupport.toSongItemImpl(managed).album)
        assertEquals("123|netease|", LocalMediaSupport.toSongItemImpl(managed).sourceStableKey)
    }

    private fun stableId(source: String): Long = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
        .toULong(16)
        .toLong()

    private fun uri(value: String): Uri = mock(Uri::class.java).also { uri ->
        doReturn(value).`when`(uri).toString()
    }

    private fun details(
        source: Uri,
        filePath: String?,
        album: String = "Album",
        usesFallbackAlbum: Boolean = false,
        originalTitle: String? = null,
        originalArtist: String? = null,
        sourceStableKey: String? = null
    ) = LocalMediaDetails(
        sourceUri = source,
        displayName = "a.flac",
        title = "Title",
        artist = "Artist",
        album = album,
        usesFallbackAlbum = usesFallbackAlbum,
        albumArtist = null,
        composer = null,
        genre = null,
        year = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 180_000,
        fileExtension = "flac",
        mimeType = "audio/flac",
        audioMimeType = null,
        bitrateKbps = null,
        sampleRateHz = null,
        channelCount = null,
        bitsPerSample = null,
        sizeBytes = null,
        lastModifiedMs = null,
        filePath = filePath,
        coverUri = "/covers/a.jpg",
        coverSource = null,
        lyricContent = "[00:01]lyric",
        lyricPath = null,
        lyricSource = null,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        embeddedCover = false,
        sourceStableKey = sourceStableKey,
        translatedLyricContent = "[00:01]translation"
    )
}
