package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScanPreviewMetadataDuplicatePolicyTest {

    @Test
    fun `marks later scan results with matching normalized metadata`() {
        val first = scannedSong(
            id = 1L,
            name = "A  Song",
            artist = "An Artist",
            album = "Album",
            durationMs = 180_000L
        )
        val duplicate = scannedSong(
            id = 2L,
            name = "a song",
            artist = " an artist ",
            album = " album ",
            durationMs = 180_000L
        )

        assertEquals(
            setOf(duplicate.stableKey()),
            duplicateScannedSongKeysByMetadata(listOf(first, duplicate))
        )
    }

    @Test
    fun `keeps results with distinct or incomplete metadata`() {
        val base = scannedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L
        )
        val differentAlbum = base.copy(id = 2L, mediaUri = "content://media/external/audio/media/2", album = "Live")
        val differentDuration = base.copy(id = 3L, mediaUri = "content://media/external/audio/media/3", durationMs = 181_000L)
        val missingAlbum = base.copy(id = 4L, mediaUri = "content://media/external/audio/media/4", album = "")

        assertEquals(
            emptySet<String>(),
            duplicateScannedSongKeysByMetadata(
                listOf(base, differentAlbum, differentDuration, missingAlbum)
            )
        )
    }

    private fun scannedSong(
        id: Long,
        name: String,
        artist: String,
        album: String,
        durationMs: Long
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = 0L,
            durationMs = durationMs,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/$id",
            channelId = "local",
            audioId = id.toString()
        )
    }
}
