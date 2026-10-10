package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`

class PlaybackStatsCounterDeltaGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `blank identities and empty deltas leave the counters untouched`() {
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.getOrCreateDeviceId()).thenReturn("device-a")
        }.use { tokenStorages ->
            val store = store()
            store.replaceFromRoom(counters("track|7"), 10)
            val before = store.snapshot()

            store.recordLocalDelta(" ", 0, 7, 1, 300, 10)
            store.recordLocalDelta("track|7", 0, 0, 0, 300, 10)
            store.recordLocalDelta("track|7", 0, -5, -1, 300, 10)

            assertSame(before.trackShardsByIdentity, store.snapshot().trackShardsByIdentity)
            assertSame(before.dailyShardsByBucketKey, store.snapshot().dailyShardsByBucketKey)
            assertEquals(0, tokenStorages.constructed().size)
        }
    }

    @Test
    fun `play count only deltas are still recorded`() {
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.getOrCreateDeviceId()).thenReturn("device-a")
        }.use {
            val store = store()
            store.replaceFromRoom(counters("track|7"), 10)

            store.recordLocalDelta("track|7", 0, 0, 1, 300, 10)

            val recorded = listOf(shard().copy(playCount = 2, lastPlayedAt = 300))
            assertEquals(recorded, store.snapshot().trackShards("track|7"))
            assertEquals(recorded, store.snapshot().dailyShards(0, "track|7"))
        }
    }

    private fun store(): PlaybackStatsCounterStore {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return PlaybackStatsCounterStore(context, Gson())
    }

    private fun shard() = SyncPlaybackCounterShard("device-a", 10, 30, 1, 100, 200)

    private fun counters(vararg identities: String) = PlaybackStatsSyncCounterSnapshot(
        trackShardsByIdentity = identities.associateWith { listOf(shard()) },
        dailyShardsByBucketKey = identities.associate { "0|$it" to listOf(shard()) }
    )
}
