package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScanPreviewHydrationStateTest {

    @Test
    fun `hydration keeps selection and clears only completed metadata`() {
        val quickSong = localSong(
            id = 1L,
            name = "Artist - Song",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY
        )
        val hydratedSong = quickSong.copy(album = "Album")
        val pendingSong = localSong(id = 2L, name = "Pending")
        val state = LocalScanPreviewState(
            visible = true,
            isScanning = true,
            songs = listOf(quickSong, pendingSong),
            metadataPendingKeys = setOf(quickSong.stableKey(), pendingSong.stableKey()),
            selectedKeys = setOf(quickSong.stableKey()),
            existingLocalPlaylistKeys = setOf(quickSong.stableKey()),
            duplicateMetadataKeys = setOf(quickSong.stableKey())
        )

        val updated = applyHydratedSongsToScanPreview(
            state = state,
            hydratedSongs = listOf(hydratedSong, null),
            progress = state.scanProgress.copy(processed = 1, total = 2)
        )

        assertEquals(listOf(hydratedSong, pendingSong), updated.songs)
        assertEquals(setOf(hydratedSong.stableKey()), updated.selectedKeys)
        assertEquals(setOf(hydratedSong.stableKey()), updated.existingLocalPlaylistKeys)
        assertEquals(setOf(hydratedSong.stableKey()), updated.duplicateMetadataKeys)
        assertEquals(setOf(pendingSong.stableKey()), updated.metadataPendingKeys)
        assertEquals(1, updated.scanProgress.processed)
    }

    private fun localSong(id: Long, name: String, album: String = "") = SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = album,
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "content://media/external/audio/media/$id",
        channelId = "local",
        audioId = id.toString()
    )
}
