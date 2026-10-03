package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDeletionCapacityPolicyTest {
    private fun deletion(id: Long, causal: Boolean) = SyncPlaylistSongDeletion(
        playlistId = 1, songId = id, album = "netease", deletedAt = id,
        removedMembershipTokens = if (causal) listOf(SyncCausalToken("d", id)) else emptyList()
    )

    @Test
    fun `merging retains every unresolved legacy and causal deletion`() {
        for ((causal, legacy) in listOf(0 to 9, 9 to 0, 1 to 9, 9 to 1, 9 to 9)) {
            val candidates = (1L..causal.toLong()).map { deletion(it, true) } +
                (1L..legacy.toLong()).map { deletion(it + 20, false) }
            val selected = SyncPlaylistDeletionPolicy.mergeDeletions(candidates, emptyList())
            assertEquals(causal + legacy, selected.size)
            if (causal > 0) assertTrue(selected.any { it.removedMembershipTokens.isNotEmpty() })
            if (legacy > 0) assertTrue(selected.any { it.removedMembershipTokens.isEmpty() })
            assertEquals(selected, SyncPlaylistDeletionPolicy.mergeDeletions(selected, emptyList()))
        }
    }

    @Test
    fun `only a genuinely readded membership resolves legacy deletion`() {
        val legacy = deletion(1, false)
        val causal = deletion(1, true)
        val active = SyncSong(id = 1, album = "netease", addedAt = 20, syncMembershipTokens = listOf(SyncCausalToken("new", 1)))
        val playlist = SyncPlaylist(id = 1, songs = listOf(active))
        assertEquals(listOf(causal), SyncPlaylistDeletionPolicy.pruneResolvedDeletions(listOf(legacy, causal), listOf(playlist)))
        for (songs in listOf(emptyList(), listOf(active.copy(addedAt = 0)), listOf(active.copy(syncMembershipTokens = emptyList())))) {
            assertEquals(listOf(legacy), SyncPlaylistDeletionPolicy.pruneResolvedDeletions(listOf(legacy), listOf(playlist.copy(songs = songs))))
        }
        assertEquals(listOf(legacy), SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(listOf(legacy), 1, emptyList()))
    }
}
