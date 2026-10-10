package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMetadataUpdate
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistSongMetadataUpdatesTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `metadata updates rewrite matching songs and leave other playlists untouched`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L), playlist(2L, "Gym", 21L))
        val repository = repository(storage)
        val (renamed, unchanged) = playlist(repository, 1L).songs
        val gym = playlist(repository, 2L)

        repository.applySongMetadataUpdates(
            listOf(
                SongMetadataUpdate(renamed, renamed.copy(name = "Night drive", coverUrl = "https://img.example/night.jpg")),
                SongMetadataUpdate(unchanged, unchanged)
            )
        )

        val road = playlist(repository, 1L)
        assertEquals(
            listOf(renamed.copy(name = "Night drive", coverUrl = "https://img.example/night.jpg"), unchanged),
            road.songs
        )
        assertEquals(gym, playlist(repository, 2L))
        assertEquals(listOf("Night drive", "song-12"), playlist(repository(storage), 1L).songs.map(SongItem::name))
    }

    @Test
    fun `metadata updates that change nothing are not persisted`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L))
        val repository = repository(storage)
        val song = playlist(repository, 1L).songs.single()
        val commits = storage.commitCount

        repository.applySongMetadataUpdates(emptyList())
        repository.applySongMetadataUpdates(listOf(SongMetadataUpdate(song, song)))

        assertEquals(commits, storage.commitCount)
        assertEquals(listOf(song), playlist(repository, 1L).songs)
    }

    private fun storageWith(vararg playlists: LocalPlaylist) =
        RecordingStorage(primary = Gson().toJson(playlists.toList()))

    private fun playlist(id: Long, name: String, vararg songIds: Long) = LocalPlaylist(
        id = id,
        name = name,
        songs = songIds.map { songId -> remoteNeteaseSong(id = songId, name = "song-$songId") }.toMutableList(),
        modifiedAt = 1_000L,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )

    private fun playlist(repository: LocalPlaylistRepository, id: Long) =
        repository.playlists.value.first { it.id == id }

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "metadata_updates.json"),
        storage = storage,
        syncMutationStore = RecordingSyncMutationStore()
    )
}
