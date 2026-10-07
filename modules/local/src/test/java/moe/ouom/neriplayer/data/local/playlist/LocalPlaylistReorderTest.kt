package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistReorderTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `listed playlists move to the front and unlisted ones follow in their old order`() = runTest {
        val storage = storageWith(playlist(1L, "A"), playlist(2L, "B"), playlist(3L, "C"), playlist(4L, "D"))
        val repository = repository(storage)

        repository.reorderPlaylists(listOf(3L, 1L, 42L))

        assertEquals(listOf(3L, 1L, 2L, 4L), repository.playlists.value.map(LocalPlaylist::id))
        assertEquals(listOf(3L, 1L, 2L, 4L), repository(storage).playlists.value.map(LocalPlaylist::id))
        val modifiedAt = repository.playlists.value.map(LocalPlaylist::modifiedAt).distinct()
        assertEquals(1, modifiedAt.size)
        assertTrue(modifiedAt.single() > ORIGINAL_MODIFIED_AT)
    }

    @Test
    fun `an unchanged order or a single playlist is not rewritten`() = runTest {
        val pair = storageWith(playlist(1L, "A"), playlist(2L, "B"))
        val single = storageWith(playlist(1L, "A"))
        val pairRepository = repository(pair)
        val singleRepository = repository(single)
        val pairCommits = pair.commitCount
        val singleCommits = single.commitCount

        pairRepository.reorderPlaylists(listOf(1L))
        singleRepository.reorderPlaylists(listOf(1L))

        assertEquals(pairCommits, pair.commitCount)
        assertEquals(singleCommits, single.commitCount)
        assertEquals(listOf(ORIGINAL_MODIFIED_AT, ORIGINAL_MODIFIED_AT), pairRepository.playlists.value.map(LocalPlaylist::modifiedAt))
    }

    private fun storageWith(vararg playlists: LocalPlaylist) =
        RecordingStorage(primary = Gson().toJson(playlists.toList()))

    private fun playlist(id: Long, name: String) =
        LocalPlaylist(id = id, name = name, modifiedAt = ORIGINAL_MODIFIED_AT)

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "playlist_order.json"),
        storage = storage
    )

    private companion object {
        const val ORIGINAL_MODIFIED_AT = 1_000L
    }
}
