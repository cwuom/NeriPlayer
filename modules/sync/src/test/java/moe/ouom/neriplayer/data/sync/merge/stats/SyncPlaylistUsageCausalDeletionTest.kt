package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.*
import org.junit.Test

class SyncPlaylistUsageCausalDeletionTest {
    private val a = SyncCausalToken("usage-delete:a", 1)
    private val b = SyncCausalToken("usage-delete:b", 1)
    private val stat = SyncPlaylistUsageStat(playlistKey = "netease:7", id = 7, source = "netease", openCount = 1, trackCount = 2)

    @Test fun concurrentDeletionsRequireEveryObservationAndCannotCombinePartialProofs() {
        val deletions = listOf(SyncPlaylistUsageDeletion(stat.playlistKey, listOf(a), 20), SyncPlaylistUsageDeletion(stat.playlistKey, listOf(b), 10))
        val barriers = SyncPlaylistUsageDeletionPolicy.merge(deletions)
        assertEquals(listOf(a, b), barriers.single().deletionTokens)
        val sawA = stat.copy(observedDeletionTokens = listOf(a), lastOpenedAt = Long.MAX_VALUE, openCount = 100_000)
        val sawB = stat.copy(observedDeletionTokens = listOf(b), lastOpenedAt = Long.MAX_VALUE, openCount = 100_000)
        assertTrue(SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(listOf(sawA), listOf(sawB), barriers).isEmpty())
        val restored = stat.copy(observedDeletionTokens = listOf(b, a, b), lastOpenedAt = 1)
        val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(listOf(restored), listOf(sawA, sawB), barriers).single()
        assertEquals(1, merged.openCount)
        assertEquals(1L, merged.lastOpenedAt)
        assertEquals(listOf(a, b), merged.observedDeletionTokens)
        assertEquals(listOf(merged), SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(listOf(merged), listOf(sawA, sawB), barriers))
        assertEquals(barriers, SyncPlaylistUsageDeletionPolicy.merge(barriers + deletions.asReversed()))
    }

    @Test fun legacyBridgeIsStableAndKeepsExactKeyAndTimestampIdentity() {
        val input = mapOf("netease:7" to Long.MAX_VALUE, "netease:70" to Long.MAX_VALUE, "netease:7:sub" to 2L)
        val first = SyncPlaylistUsageDeletionPolicy.fromLegacy(input)
        assertEquals(first, SyncPlaylistUsageDeletionPolicy.fromLegacy(input.toList().asReversed().toMap()))
        assertEquals(3, first.flatMap { it.deletionTokens }.toSet().size)
        val later = SyncPlaylistUsageDeletionPolicy.fromLegacy(mapOf("netease:7" to 3L))
        assertEquals(2, SyncPlaylistUsageDeletionPolicy.merge(first + later).first { it.playlistKey == "netease:7" }.deletionTokens.size)
    }
}
