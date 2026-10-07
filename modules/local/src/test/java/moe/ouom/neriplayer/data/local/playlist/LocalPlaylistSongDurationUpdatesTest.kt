package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistSongDurationUpdatesTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `durations are filled only for songs that still lack one`() = runTest {
        val storage = storageWith(
            playlist(1L, "Road trip", song(11L, 0L), song(12L, 0L), song(13L, 180_000L), song(14L, 0L)),
            playlist(2L, "Gym", song(21L, 0L))
        )
        val repository = repository(storage)
        val (missing, stillUnknown, known) = playlist(repository, 1L).songs
        val gym = playlist(repository, 2L)

        repository.applySongDurationUpdates(
            listOf(
                missing to missing.copy(durationMs = 200_000L, name = "ignored title"),
                stillUnknown to stillUnknown.copy(durationMs = 0L),
                known to known.copy(durationMs = 300_000L)
            )
        )

        assertEquals(listOf(200_000L, 0L, 180_000L, 0L), durations(repository))
        assertEquals("song-11", playlist(repository, 1L).songs.first().name)
        assertEquals(gym, playlist(repository, 2L))
        assertEquals(durations(repository), durations(repository(storage)))
    }

    @Test
    fun `duration updates without a usable duration are not persisted`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", song(11L, 0L), song(12L, 90_000L)))
        val repository = repository(storage)
        val (unknown, known) = playlist(repository, 1L).songs
        val commits = storage.commitCount

        repository.applySongDurationUpdates(emptyList())
        repository.applySongDurationUpdates(
            listOf(unknown to unknown.copy(durationMs = -1L), known to known.copy(durationMs = 120_000L))
        )

        assertEquals(commits, storage.commitCount)
        assertEquals(listOf(0L, 90_000L), durations(repository))
    }

    private fun song(id: Long, durationMs: Long) =
        remoteNeteaseSong(id = id, name = "song-$id").copy(durationMs = durationMs)

    private fun storageWith(vararg playlists: LocalPlaylist) =
        RecordingStorage(primary = Gson().toJson(playlists.toList()))

    private fun playlist(id: Long, name: String, vararg songs: SongItem) = LocalPlaylist(
        id = id,
        name = name,
        songs = songs.toMutableList(),
        modifiedAt = 1_000L,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )

    private fun playlist(repository: LocalPlaylistRepository, id: Long) =
        repository.playlists.value.first { it.id == id }

    private fun durations(repository: LocalPlaylistRepository) =
        playlist(repository, 1L).songs.map(SongItem::durationMs)

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "duration_updates.json"),
        storage = storage,
        syncMutationStore = RecordingSyncMutationStore()
    )
}
