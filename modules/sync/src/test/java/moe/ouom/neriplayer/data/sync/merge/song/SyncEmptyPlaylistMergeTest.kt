package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEmptyPlaylistMergeTest {
    @Test
    fun `initial favorites and observed remote membership survive local clear`() {
        val remote = SyncSong(id = 1, syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION)
        fun merge(favorites: Boolean, lastSync: Long, song: SyncSong, changed: Boolean = true, localModified: Long = 20) =
            SyncPlaylistSongMergePolicy.mergeSongs(emptyList(), listOf(song), localModified, 10, changed, true, lastSync, favorites)
        val initial = merge(true, 0, remote)
        assertEquals(listOf(remote), initial.songs)
        assertTrue(initial.isUpdated)
        val cleared = merge(true, 1, remote)
        assertTrue(cleared.songs.isEmpty())
        assertFalse(cleared.isUpdated)
        assertEquals(1, merge(false, 1, remote.copy(syncMembershipTokens = listOf(SyncCausalToken("d", 1)))).songs.size)
        assertEquals(listOf(remote), merge(false, 1, remote, changed = false).songs)
        assertEquals(listOf(remote), merge(false, 1, remote, localModified = 5).songs)
    }
}
