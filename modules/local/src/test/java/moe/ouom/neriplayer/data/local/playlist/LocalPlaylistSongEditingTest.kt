package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistSongDeleteResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistSongEditingTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `removing songs by id drops only matching songs from the target playlist`() = runTest {
        val syncStore = RecordingSyncMutationStore()
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L, 13L), playlist(2L, "Gym", 12L))
        val repository = repository(storage, syncStore)

        repository.removeSongsFromPlaylistById(1L, listOf(12L, 99L))

        assertEquals(mapOf(1L to listOf(11L, 13L), 2L to listOf(12L)), songIds(repository))
        assertEquals(songIds(repository), songIds(repository(storage, RecordingSyncMutationStore())))
        assertEquals(
            listOf(1L to 12L),
            syncStore.applied.flatMap { it.addedSongDeletions }.map { it.playlistId to it.songId }
        )
    }

    @Test
    fun `removals that match no song leave the stored playlists alone`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L))
        val repository = repository(storage, RecordingSyncMutationStore())
        val commits = storage.commitCount

        repository.removeSongsFromPlaylistById(1L, emptyList())
        repository.removeSongsFromPlaylistById(2L, listOf(11L))
        repository.removeSongsFromPlaylistById(1L, listOf(99L))

        assertEquals(commits, storage.commitCount)
        assertEquals(mapOf(1L to listOf(11L)), songIds(repository))
    }

    @Test
    fun `moving a song reorders only its playlist and restamps the display order`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L, 13L), playlist(2L, "Gym", 21L, 22L))
        val repository = repository(storage, RecordingSyncMutationStore())

        repository.moveSong(1L, fromIndex = 0, toIndex = 2)

        val moved = repository.playlists.value.first { it.id == 1L }
        assertEquals(listOf(12L, 13L, 11L), moved.songs.map(SongItem::id))
        assertEquals(listOf(0L, 1L, 2L).map { moved.modifiedAt - it }, moved.songs.map(SongItem::addedAt))
        assertEquals(listOf(21L, 22L), songIds(repository)[2L])
        assertEquals(songIds(repository), songIds(repository(storage, RecordingSyncMutationStore())))
    }

    @Test
    fun `moves outside the playlist bounds are ignored`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L, 13L))
        val repository = repository(storage, RecordingSyncMutationStore())
        val before = repository.playlists.value
        val commits = storage.commitCount

        listOf(-1 to 0, 3 to 0, 0 to -1, 0 to 3).forEach { (from, to) ->
            repository.moveSong(1L, fromIndex = from, toIndex = to)
        }

        assertEquals(before, repository.playlists.value)
        assertEquals(commits, storage.commitCount)
    }

    @Test
    fun `clearing an empty or unknown playlist reports no deleted songs`() = runTest {
        val storage = storageWith(playlist(1L, "Empty"), playlist(2L, "Gym", 21L))
        val repository = repository(storage, RecordingSyncMutationStore())
        val commits = storage.commitCount

        assertEquals(emptyList<LocalPlaylistSongDeleteResult>(), repository.clearPlaylistSongsWithResult(1L))
        assertEquals(emptyList<LocalPlaylistSongDeleteResult>(), repository.clearPlaylistSongsWithResult(3L))

        assertEquals(commits, storage.commitCount)
        assertEquals(mapOf(1L to emptyList<Long>(), 2L to listOf(21L)), songIds(repository))
    }

    private fun storageWith(vararg playlists: LocalPlaylist) =
        RecordingStorage(primary = Gson().toJson(playlists.toList()))

    private fun playlist(id: Long, name: String, vararg songIds: Long) = LocalPlaylist(
        id = id,
        name = name,
        songs = songIds.map { songId -> remoteNeteaseSong(id = songId, name = "song-$songId") }.toMutableList(),
        modifiedAt = 1_000L
    )

    private fun repository(storage: RecordingStorage, syncStore: RecordingSyncMutationStore) =
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "song_editing.json"),
            storage = storage,
            syncMutationStore = syncStore
        )

    private fun songIds(repository: LocalPlaylistRepository) =
        repository.playlists.value.associate { playlist -> playlist.id to playlist.songs.map(SongItem::id) }
}
