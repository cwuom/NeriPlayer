package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistUsageMetadataTest {
    @Test
    fun `missing presentation and platform identifiers fall back independently`() {
        val known = SyncPlaylistUsageStat(playlistKey = "local:1", source = "local", id = 1, subtype = "type", name = "name",
            coverUrl = "cover", trackCount = 9, fid = 2, mid = 3, browseId = "browse", playlistId = "list", subtitle = "subtitle")
        val empty = SyncPlaylistUsageStat(playlistKey = "local:1")
        assertEquals(known, mergePlaylistUsageMetadata(empty, known))
        assertEquals(known, mergePlaylistUsageMetadata(known, empty))
        assertEquals(empty, mergePlaylistUsageMetadata(empty, empty))
    }

    @Test
    fun `legacy local totals use maximum while shard timestamps supply unknown occurrence time`() {
        val legacy = SyncLocalPlaylistPlaybackStat(playlistId = 1, totalPlayCount = 5)
        assertEquals(5L, SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(listOf(legacy), listOf(legacy.copy(totalPlayCount = 3))).single().totalPlayCount)
        val shard = SyncPlaybackCounterShard("d", playCount = 2, firstPlayedAt = 0, lastPlayedAt = 50)
        val sharded = legacy.copy(totalPlayCount = 2, counterShards = listOf(shard))
        val merged = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(listOf(sharded), listOf(sharded)).single()
        assertEquals(2L, merged.totalPlayCount)
        assertEquals(50L, merged.firstPlayedAt)
        assertEquals(50L, merged.lastPlayedAt)
    }
}
