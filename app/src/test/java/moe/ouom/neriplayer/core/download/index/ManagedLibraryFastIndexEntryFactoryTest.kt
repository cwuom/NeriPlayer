package moe.ouom.neriplayer.core.download.index

import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedLibraryFastIndexEntryFactoryTest {
    @Test
    fun `completed download maps every recovery field into a full entry`() {
        val song = SongItem(
            id = 41L,
            name = "title",
            artist = "artist",
            album = "album",
            albumId = 7L,
            durationMs = 1234L,
            coverUrl = null,
            mediaUri = "https://example.test/source",
            channelId = "channel",
            audioId = "audio-id",
            subAudioId = "sub-audio-id",
            playlistContextId = "playlist",
            sourceStableKey = "900|remote|https://example.test/identity"
        )
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "track.flac",
            reference = "content://provider/audio",
            mediaUri = "content://provider/audio",
            localFilePath = null,
            sizeBytes = 99L,
            lastModifiedMs = 4567L
        )

        val entry = ManagedLibraryFastIndexEntryFactory.fromCompletedDownload(
            libraryId = "library-id",
            song = song,
            audio = audio,
            state = "FINALIZED",
            coverPath = "content://provider/cover",
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            updatedAtMs = 9999L
        )

        assertEquals("900|remote|https://example.test/identity", entry.stableKey)
        assertEquals(
            "managed:library-id:900|remote|https://example.test/identity",
            entry.artifactId
        )
        assertEquals("track.flac", entry.audioName)
        assertEquals("track.flac.npmeta.json", entry.metadataName)
        assertEquals("content://provider/audio", entry.audioReference)
        assertEquals("FINALIZED", entry.state)
        assertEquals(DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED, entry.metadataEmbeddingState)
        assertEquals(4567L, entry.downloadTimeMs)
        assertEquals(9999L, entry.updatedAtMs)
        assertEquals(41L, entry.songId)
        assertEquals("title", entry.title)
        assertEquals("artist", entry.artist)
        assertEquals("album", entry.album)
        assertEquals("https://example.test/identity", entry.mediaUri)
        assertEquals("channel", entry.channelId)
        assertEquals("audio-id", entry.audioId)
        assertEquals("sub-audio-id", entry.subAudioId)
        assertEquals("playlist", entry.playlistContextId)
        assertEquals(1234L, entry.durationMs)
        assertEquals("content://provider/cover", entry.coverPath)
        assertEquals(4567L, entry.logicalCreatedAtMs)
        assertEquals("MTIME", entry.createdAtSource)
        assertEquals("INFERRED", entry.createdAtConfidence)
    }

    @Test
    fun `unknown timestamps and duration stay absent`() {
        val song = SongItem(
            id = 0L,
            name = "title",
            artist = "artist",
            album = "album",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            sourceStableKey = "901|remote|"
        )
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "track.m4a",
            reference = "/downloads/track.m4a",
            mediaUri = "file:///downloads/track.m4a",
            localFilePath = "/downloads/track.m4a",
            sizeBytes = 0L,
            lastModifiedMs = 0L
        )

        val entry = ManagedLibraryFastIndexEntryFactory.fromCompletedDownload(
            libraryId = "library-id",
            song = song,
            audio = audio,
            state = "CORE_COMMITTED",
            updatedAtMs = 1L
        )

        assertNull(entry.songId)
        assertNull(entry.downloadTimeMs)
        assertNull(entry.durationMs)
        assertNull(entry.coverPath)
        assertNull(entry.logicalCreatedAtMs)
        assertNull(entry.createdAtSource)
        assertNull(entry.createdAtConfidence)
    }
}
