package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `background hydration does not reopen a completed preview`() {
        val quickSong = localSong(
            id = 3L,
            name = "Artist - Ready",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY
        )
        val state = LocalScanPreviewState(
            visible = true,
            isScanning = false,
            songs = listOf(quickSong),
            metadataPendingKeys = setOf(quickSong.stableKey())
        )

        val updated = applyHydratedSongsToScanPreview(
            state = state,
            hydratedSongs = listOf(quickSong.copy(album = "Album")),
            progress = state.scanProgress.copy(processed = 1, total = 1)
        )

        assertFalse(updated.isScanning)
    }

    @Test
    fun `hydration batches update their offset without changing later selection`() {
        val firstSong = localSong(id = 11L, name = "First")
        val secondSong = localSong(id = 12L, name = "Second")
        val state = LocalScanPreviewState(
            visible = true,
            songs = listOf(firstSong, secondSong),
            metadataPendingKeys = setOf(firstSong.stableKey(), secondSong.stableKey()),
            selectedKeys = setOf(firstSong.stableKey(), secondSong.stableKey())
        )
        val hydratedSecond = secondSong.copy(album = "Album")

        val updated = applyHydratedSongsToScanPreview(
            state = state,
            hydratedSongs = listOf(hydratedSecond),
            progress = state.scanProgress,
            startIndex = 1
        )

        assertEquals(listOf(firstSong, hydratedSecond), updated.songs)
        assertEquals(
            setOf(firstSong.stableKey(), hydratedSecond.stableKey()),
            updated.selectedKeys
        )
        assertEquals(setOf(firstSong.stableKey()), updated.metadataPendingKeys)
    }

    @Test
    fun `metadata refresh candidates keep the newest song for each stable key`() {
        val firstSong = localSong(id = 4L, name = "First")
        val replacement = firstSong.copy(name = "First updated")
        val secondSong = localSong(id = 5L, name = "Second")

        val merged = mergeLocalMetadataRefreshCandidates(
            pending = mapOf(firstSong.stableKey() to firstSong),
            incoming = listOf(replacement, secondSong)
        )

        assertEquals(2, merged.size)
        assertEquals(replacement, merged[replacement.stableKey()])
        assertEquals(secondSong, merged[secondSong.stableKey()])
    }

    @Test
    fun `scanned songs are ordered by source time descending`() {
        val oldSong = localSong(id = 6L, name = "old").copy(addedAt = 100L)
        val newestSong = localSong(id = 7L, name = "newest").copy(addedAt = 300L)
        val middleSong = localSong(id = 8L, name = "middle").copy(addedAt = 200L)

        assertEquals(
            listOf(newestSong, middleSong, oldSong),
            sortScannedSongsBySourceTime(listOf(oldSong, newestSong, middleSong))
        )
    }

    @Test
    fun `scanned songs with the same source time keep discovery order`() {
        val firstSong = localSong(id = 9L, name = "first").copy(addedAt = 500L)
        val secondSong = localSong(id = 10L, name = "second").copy(addedAt = 500L)

        assertEquals(
            listOf(firstSong, secondSong),
            sortScannedSongsBySourceTime(listOf(firstSong, secondSong))
        )
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
