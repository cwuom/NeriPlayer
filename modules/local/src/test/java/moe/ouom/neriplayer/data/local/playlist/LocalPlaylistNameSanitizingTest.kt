package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistNameSanitizingTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `blank names fall back to the default name and repeated names are numbered`() = runTest {
        val repository = repository()

        repository.createPlaylist("   ")
        repository.createPlaylist("")
        repository.createPlaylist("playlist")

        assertEquals(listOf("Playlist", "Playlist_2", "playlist_3"), names(repository))
    }

    @Test
    fun `reserved system names and truncated duplicates are suffixed within the length limit`() = runTest {
        val repository = repository()

        repository.createPlaylist("我喜欢的音乐")
        repository.createPlaylist("  Night Drive Mix ")
        repository.createPlaylist("NIGHT DRIVE again")

        assertEquals(listOf("我喜欢的音乐_2", "Night Driv", "NIGHT DR_2"), names(repository))
        assertEquals(names(repository), names(repository()))
    }

    @Test
    fun `renaming ignores the playlist's own name but not the names of the others`() = runTest {
        val repository = repository()
        repository.createPlaylist("Road")
        repository.createPlaylist("Gym")
        val (road, gym) = repository.playlists.value

        repository.renamePlaylist(road.id, "ROAD")
        repository.renamePlaylist(gym.id, "road")

        assertEquals(listOf("ROAD", "road_2"), names(repository))
    }

    private fun repository() = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "name_sanitizing.json")
    )

    private fun names(repository: LocalPlaylistRepository) = repository.playlists.value.map(LocalPlaylist::name)
}
