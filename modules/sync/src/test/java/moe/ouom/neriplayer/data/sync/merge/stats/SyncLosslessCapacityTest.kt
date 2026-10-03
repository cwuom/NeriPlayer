package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLosslessCapacityTest {
    @Test
    fun `old track statistics survive a larger current library`() {
        val stats = (1L..10_001L).map { id ->
            SyncTrackStat(identityKey = "netease:$id", lastPlayedAt = id, totalListenMs = id)
        }
        val result = SyncPlaybackStatsMergePolicy.finalizeMergedStats(stats, emptyList())
        assertEquals(10_001, result.stats.size)
        assertTrue(result.stats.any { it.identityKey == "netease:1" && it.totalListenMs == 1L })
    }

    @Test
    fun `old playlist buckets survive years of newer listening`() {
        val buckets = (1L..8_001L).map { id ->
            SyncLocalPlaylistPlaybackBucket(playlistId = id, dayStartAt = id * 86_400_000L, playCount = 1)
        }
        val result = SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(emptyList(), buckets)
        assertEquals(8_001, result.buckets.size)
        assertEquals(8_001, result.stats.size)
        assertTrue(result.buckets.any { it.playlistId == 1L })
    }

    @Test
    fun `bulk removals still suppress old offline memberships beyond previous cap`() {
        val deletions = (1L..5_001L).map { id ->
            SyncPlaylistSongDeletion(
                playlistId = 7, songId = id, album = "netease", deletedAt = id,
                removedMembershipTokens = listOf(SyncCausalToken("phone", id))
            )
        }
        val retained = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(deletions, emptyList())
        assertEquals(5_001, retained.size)
        assertTrue(retained.any { it.songId == 1L })
    }
}
