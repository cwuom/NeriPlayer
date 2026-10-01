package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCounterShardPolicyTest {
    @Test
    fun `missing entries and blank device identities do not produce counters`() {
        assertTrue(SyncCounterShardPolicy.normalizeCounterShards(null).isEmpty())
        assertTrue(SyncCounterShardPolicy.normalizeCounterShards(listOf(null, shard(" "))).isEmpty())
    }

    @Test
    fun `negative counters and timestamps are clamped without changing the input`() {
        val input = SyncPlaybackCounterShard("device", -1L, -2L, -3, -4L, -5L)
        assertEquals(listOf(SyncPlaybackCounterShard("device")), SyncCounterShardPolicy.normalizeCounterShards(listOf(input)))
        assertEquals(-2L, input.totalListenMs)
    }

    @Test
    fun `first play is bounded by a known last play and preserved when the last play is unknown`() {
        val cases = listOf(0L to 100L, 200L to 100L, 50L to 50L)
        for ((first, expected) in cases) {
            val result = SyncCounterShardPolicy.normalizeCounterShards(listOf(shard("device", first, 100L))).single()
            assertEquals(expected, result.firstPlayedAt)
        }
        val unknownLast = SyncCounterShardPolicy.normalizeCounterShards(listOf(shard("device", 50L, 0L))).single()
        assertEquals(50L, unknownLast.firstPlayedAt)
        assertEquals(0L, unknownLast.lastPlayedAt)
    }

    @Test
    fun `duplicate snapshots keep counter maxima and the earliest known first play in either order`() {
        val known = shard("device", 10L, 100L).copy(totalListenMs = 80L, playCount = 1)
        val unknown = shard("device").copy(totalListenMs = 20L, playCount = 2)
        val later = shard("device", 20L, 200L).copy(totalListenMs = 40L, playCount = 1)
        val expected = known.copy(playCount = 2, lastPlayedAt = 200L)
        for (inputs in listOf(listOf(known, unknown, later), listOf(unknown, later, known))) {
            assertEquals(listOf(expected), SyncCounterShardPolicy.normalizeCounterShards(inputs))
        }
    }

    @Test
    fun `epochs and devices remain independent with deterministic ordering`() {
        val inputs = listOf(
            shard("z").copy(epochStartedAt = 20L),
            shard("a").copy(epochStartedAt = 30L),
            shard("a").copy(epochStartedAt = 10L)
        )
        val result = SyncCounterShardPolicy.normalizeCounterShards(inputs)
        assertEquals(listOf("a" to 10L, "a" to 30L, "z" to 20L), result.map { it.deviceId to it.epochStartedAt })
        assertEquals(result, SyncCounterShardPolicy.normalizeCounterShards(result))
    }

    private fun shard(device: String, first: Long = 0L, last: Long = 0L) =
        SyncPlaybackCounterShard(deviceId = device, firstPlayedAt = first, lastPlayedAt = last)
}
