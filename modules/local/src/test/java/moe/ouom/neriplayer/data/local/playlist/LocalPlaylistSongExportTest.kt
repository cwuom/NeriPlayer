package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistSongExportTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `exported songs go ahead of the target's songs in their source order`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L, 13L), playlist(2L, "Gym", 21L), playlist(3L, "Chill", 31L))
        val repository = repository(storage)
        val source = playlist(repository, 1L).songs
        val chill = playlist(repository, 3L)

        repository.exportSongsToPlaylistByIdentity(1L, 2L, listOf(source[2], source[0]))

        assertEquals(mapOf(1L to listOf(11L, 12L, 13L), 2L to listOf(11L, 13L, 21L), 3L to listOf(31L)), songIds(repository))
        assertEquals(chill, playlist(repository, 3L))
        assertEquals(songIds(repository), songIds(repository(storage)))
    }

    @Test
    fun `exports with nothing new for the target leave every playlist as stored`() = runTest {
        val storage = storageWith(
            playlist(1L, "Road trip", 11L, 12L),
            playlist(2L, "Gym", 11L),
            playlist(LocalFilesPlaylist.SYSTEM_ID, "Local Files")
        )
        val repository = repository(storage)
        val source = playlist(repository, 1L).songs
        val before = songIds(repository)
        val commits = storage.commitCount

        repository.exportSongsToPlaylistByIdentity(1L, 2L, listOf(remoteNeteaseSong(id = 99L)))
        repository.exportSongsToPlaylistByIdentity(1L, LocalFilesPlaylist.SYSTEM_ID, source)
        repository.exportSongsToPlaylistByIdentity(1L, 2L, listOf(source[0]))

        assertEquals(commits, storage.commitCount)
        assertEquals(before, songIds(repository))
    }

    @Test
    fun `re-adding scanned copies of songs already present refreshes them in place`() = runTest {
        val storage = storageWith(playlist(1L, "Road trip", 11L, 12L))
        val repository = repository(storage)
        val (first, second) = playlist(repository, 1L).songs

        val result = repository.addSongsToPlaylistWithResult(
            playlistId = 1L,
            songs = listOf(second.copy(name = "Remastered")),
            hydrateLocalMetadata = false,
            preserveScannedSourceAddedAt = true
        )

        assertEquals(emptyList<SongItem>(), result.addedSongs)
        assertEquals(listOf(first, second.copy(name = "Remastered")), playlist(repository, 1L).songs)
        assertEquals(listOf("song-11", "Remastered"), playlist(repository(storage), 1L).songs.map(SongItem::name))
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

    private fun songIds(repository: LocalPlaylistRepository) =
        repository.playlists.value.associate { playlist -> playlist.id to playlist.songs.map(SongItem::id) }

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "song_export.json"),
        storage = storage,
        syncMutationStore = RecordingSyncMutationStore()
    )
}
