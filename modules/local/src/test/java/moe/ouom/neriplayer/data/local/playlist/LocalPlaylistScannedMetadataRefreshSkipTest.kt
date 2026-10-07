package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistScannedMetadataRefreshSkipTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `refreshing only streaming songs reports an empty run without touching playlists`() = runTest {
        val streamed = remoteNeteaseSong(id = 11L, name = "Streamed")
        val storage = storageWith(streamed)
        val repository = repository(storage)
        val before = repository.playlists.value
        val commits = storage.commitCount
        val progress = mutableListOf<Pair<Int, Int>>()

        repository.refreshScannedLocalSongMetadata(
            songs = listOf(streamed),
            includeEmbeddedAssets = true,
            includeLyricContents = true
        ) { processed, total -> progress += processed to total }

        assertEquals(listOf(0 to 0), progress)
        assertEquals(before, repository.playlists.value)
        assertEquals(commits, storage.commitCount)
    }

    @Test
    fun `cover refresh keeps local songs that have no cover to resolve`() = runTest {
        tempFolder.newFile("song-21.mp3")
        val local = localSong(21)
        val storage = storageWith(local)
        val repository = repository(storage)
        val before = repository.playlists.value
        val commits = storage.commitCount
        val progress = mutableListOf<Pair<Int, Int>>()

        repository.refreshScannedLocalSongMetadata(listOf(local)) { processed, total -> progress += processed to total }

        assertEquals(listOf(0 to 1, 1 to 1), progress)
        assertEquals(before, repository.playlists.value)
        assertEquals(listOf(local), repository.playlists.value.single().songs)
        assertEquals(commits, storage.commitCount)
    }

    private fun storageWith(song: SongItem) = RecordingStorage(
        primary = Gson().toJson(
            listOf(
                LocalPlaylist(
                    id = 1L,
                    name = "Mixed",
                    songs = mutableListOf(song),
                    modifiedAt = 1_000L,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )
    )

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "scanned_refresh.json"),
        storage = storage,
        syncMutationStore = RecordingSyncMutationStore()
    )
}
