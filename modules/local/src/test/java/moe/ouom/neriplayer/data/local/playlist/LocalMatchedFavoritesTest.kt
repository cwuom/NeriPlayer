package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMatchedFavoritesTest : LocalPlaylistRepositoryTestSupport() {
    @Test
    fun `adding a matched local song preserves the existing remote favorite`() = runTest {
        val repository = repository()
        val remote = remoteNeteaseSong()
        val local = localSong(99).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )
        repository.updatePlaylists(listOf(LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "Favorites",
            songs = mutableListOf(remote)
        )))

        repository.addToFavorites(local)

        assertEquals(listOf(remote), repository.playlists.value.single().songs)
    }

    @Test
    fun `removing matched local song persists remote deletion and can be readded`() = runTest {
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(syncStore)
        val remote = remoteNeteaseSong()
        val local = localSong(99).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )
        repository.updatePlaylists(listOf(LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "Favorites",
            songs = mutableListOf(remote)
        )))

        repository.removeFromFavorites(local)

        assertTrue(repository.playlists.value.single().songs.isEmpty())
        val deletion = syncStore.applied.flatMap(LocalPlaylistSyncMutation::addedSongDeletions).single()
        assertEquals(remote.identity(), deletion.identity())

        repository.addToFavorites(local)

        assertEquals(1, repository.playlists.value.single().songs.size)
        assertEquals(local.identity(), repository.playlists.value.single().songs.single().identity())
        val reloaded = repository(syncStore)
        assertEquals(1, reloaded.playlists.value.single().songs.size)
    }

    @Test
    fun `matched local and remote entries remain separate in ordinary playlists`() = runTest {
        val repository = repository()
        val remote = remoteNeteaseSong()
        val local = localSong(99).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )
        val playlist = repository.createPlaylistWithPreparedSongs("Mix", listOf(remote, local))

        assertEquals(2, playlist.songs.size)

        repository.removeSongsFromPlaylistByIdentity(playlist.id, listOf(remote))

        val remaining = repository.playlists.value.single { it.id == playlist.id }.songs.single()
        assertEquals(local.identity(), remaining.identity())
    }

    @Test
    fun `invalid or other provider matches cannot remove a netease favorite`() = runTest {
        val repository = repository()
        val remote = remoteNeteaseSong()
        val local = localSong(99).copy(matchedSongId = "42")
        repository.updatePlaylists(listOf(LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "Favorites",
            songs = mutableListOf(remote)
        )))

        listOf(
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "0"),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "43"),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, sourceStableKey = "77|bilibili|")
        ).forEach { candidate ->
            repository.removeFromFavorites(candidate)
            assertEquals(listOf(remote), repository.playlists.value.single().songs)
        }
    }

    private fun repository(syncStore: RecordingSyncMutationStore? = null) =
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "matched_favorites.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
}
