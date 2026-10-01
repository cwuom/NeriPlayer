package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFavoritePlaylistMergePolicyTest {
    @Test
    fun `deletion ties win and a newer restore retains songs from both snapshots`() {
        val deleted = SyncFavoritePlaylist(id = 1, modifiedAt = 10, isDeleted = true, sortOrder = 9, songs = listOf(SyncSong(id = 1)))
        val active = deleted.copy(isDeleted = false, songs = listOf(SyncSong(id = 2)), sortOrder = 0)
        for ((left, right) in listOf(deleted to active, active to deleted, deleted to active.copy(modifiedAt = 5))) {
            val merged = SyncPlaylistDeletionPolicy.mergeFavoritePlaylists(left, right)
            assertTrue(merged.isDeleted)
            assertTrue(merged.songs.isEmpty())
            assertEquals(0, merged.trackCount)
        }
        val restored = SyncPlaylistDeletionPolicy.mergeFavoritePlaylists(deleted, active.copy(modifiedAt = 20))
        assertFalse(restored.isDeleted)
        assertEquals(listOf(1L, 2L), restored.songs.map { it.id })
        assertEquals(9L, restored.sortOrder)
        assertEquals(4L, SyncPlaylistDeletionPolicy.mergeFavoritePlaylists(deleted, active.copy(modifiedAt = 20, sortOrder = 4)).sortOrder)
    }
}
