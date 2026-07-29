package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalScanPreviewExistingSongsPolicyTest {

    @Test
    fun `marks scanned songs with an existing local source`() {
        val existing = localSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "existing.mp3"
        )
        val matchingScanResult = localSong(
            id = 2L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "existing.mp3"
        )
        val newScanResult = localSong(
            id = 3L,
            mediaUri = "content://media/external/audio/media/101",
            fileName = "new.mp3"
        )

        val existingKeys = scannedSongKeysAlreadyInLocalFiles(
            scannedSongs = listOf(matchingScanResult, newScanResult),
            existingLocalFiles = listOf(existing)
        )

        assertEquals(setOf(matchingScanResult.stableKey()), existingKeys)
    }

    @Test
    fun `marks scanned content alias with existing local metadata fallback`() {
        val path = File("/music/Artist - Existing.mp3").absolutePath
        val existing = localSong(
            id = 1L,
            mediaUri = path,
            localFilePath = path,
            fileName = "Artist - Existing.mp3",
            name = "Existing",
            artist = "Artist"
        )
        val matchingScanResult = localSong(
            id = 2L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "Artist - Existing.mp3",
            name = "Existing",
            artist = "Artist"
        )
        val newScanResult = localSong(
            id = 3L,
            mediaUri = "content://media/external/audio/media/101",
            fileName = "Artist - New.mp3",
            name = "New",
            artist = "Artist"
        )

        val existingKeys = scannedSongKeysAlreadyInLocalFiles(
            scannedSongs = listOf(matchingScanResult, newScanResult),
            existingLocalFiles = listOf(existing)
        )

        assertEquals(setOf(matchingScanResult.stableKey()), existingKeys)
    }

    private fun localSong(
        id: Long,
        mediaUri: String,
        fileName: String,
        name: String = fileName.substringBeforeLast('.'),
        artist: String = "Artist",
        localFilePath: String? = null
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFilePath = localFilePath,
            localFileName = fileName
        )
    }
}
