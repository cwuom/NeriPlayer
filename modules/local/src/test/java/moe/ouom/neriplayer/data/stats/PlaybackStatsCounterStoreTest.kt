package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`
import java.io.File

class PlaybackStatsCounterStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy load normalizes once while preserving empty keys and epoch`() {
        counterFile().writeText("""
            {
              "epochStartedAt": -9,
              "trackShardsByIdentity": {
                "track|7": [
                  null,
                  {"deviceId":" ","totalListenMs":99},
                  {"deviceId":"device-a","epochStartedAt":-1,"totalListenMs":-7,"playCount":-2,"firstPlayedAt":-3,"lastPlayedAt":20},
                  {"deviceId":"device-a","epochStartedAt":0,"totalListenMs":25,"playCount":4,"firstPlayedAt":10,"lastPlayedAt":30},
                  {"deviceId":"device-b","epochStartedAt":10,"totalListenMs":9,"playCount":2,"firstPlayedAt":50,"lastPlayedAt":40}
                ],
                "empty": [],
                "null-list": null
              },
              "dailyShardsByBucketKey": {
                "0|track|7": [{"deviceId":"device-a","epochStartedAt":-1,"totalListenMs":-1,"playCount":-2}],
                "empty": [],
                "null-list": null
              }
            }
        """.trimIndent())
        val store = store()

        store.loadLegacy()
        val first = store.snapshot()
        val second = store.snapshot()

        assertEquals(-9L, store.epochStartedAt())
        assertEquals(listOf(
            SyncPlaybackCounterShard("device-a", 0, 25, 4, 10, 30),
            SyncPlaybackCounterShard("device-b", 10, 9, 2, 40, 40)
        ), first.trackShards("track|7"))
        assertEquals(listOf(SyncPlaybackCounterShard(deviceId = "device-a")), first.dailyShards(0, "track|7"))
        assertEquals(setOf("track|7", "empty", "null-list"), first.trackShardsByIdentity.keys)
        assertEquals(setOf("0|track|7", "empty", "null-list"), first.dailyShardsByBucketKey.keys)
        assertTrue(first.trackShardsByIdentity.getValue("null-list").isEmpty())
        assertTrue(first.dailyShardsByBucketKey.getValue("empty").isEmpty())
        assertSame(first.trackShardsByIdentity, second.trackShardsByIdentity)
        assertSame(first.dailyShardsByBucketKey, second.dailyShardsByBucketKey)
        assertSame(first.trackShards("track|7"), second.trackShards("track|7"))
        assertImmutable(first)
    }

    @Test
    fun `Room replacement owns normalized input and keeps snapshots stable`() {
        val inputShards = mutableListOf(shard(), shard().copy(totalListenMs = 50, playCount = 3))
        val trackMap = linkedMapOf("track|7" to inputShards, "empty" to mutableListOf())
        val dailyMap = linkedMapOf("0|track|7" to inputShards, "empty" to mutableListOf())
        val input = PlaybackStatsSyncCounterSnapshot(trackMap, dailyMap)
        val store = store()

        store.replaceFromRoom(input, 10)
        val captured = store.snapshot()
        inputShards.clear()
        trackMap.clear()
        dailyMap.clear()

        assertEquals(listOf(shard().copy(totalListenMs = 50, playCount = 3)), captured.trackShards("track|7"))
        assertEquals(captured.trackShards("track|7"), captured.dailyShards(0, "track|7"))
        assertFalse(captured.trackShardsByIdentity.containsKey("empty"))
        assertFalse(captured.dailyShardsByBucketKey.containsKey("empty"))
        assertNotSame(input.trackShardsByIdentity, captured.trackShardsByIdentity)
        assertNotSame(input.dailyShardsByBucketKey, captured.dailyShardsByBucketKey)
        assertSame(captured.trackShardsByIdentity, store.snapshot().trackShardsByIdentity)
        assertEquals(10L, store.epochStartedAt())
        assertImmutable(captured)
    }

    @Test
    fun `sync replacement copies input lists and retains normalization contract`() {
        val inputShards = mutableListOf(shard(), shard().copy(totalListenMs = 70, playCount = 4))
        val stats = mutableListOf(SyncTrackStat(identityKey = "track|7", counterShards = inputShards))
        val buckets = mutableListOf(SyncPlaybackStatBucket(dayStartAt = 0, identityKey = "track|7", counterShards = inputShards))
        val store = store()

        store.replaceFromSync(stats, buckets, -9)
        val captured = store.snapshot()
        inputShards.clear()
        stats.clear()
        buckets.clear()

        assertEquals(listOf(shard().copy(totalListenMs = 70, playCount = 4)), captured.trackShards("track|7"))
        assertEquals(captured.trackShards("track|7"), captured.dailyShards(0, "track|7"))
        assertEquals(0L, store.epochStartedAt())
        assertSame(captured.dailyShardsByBucketKey, store.snapshot().dailyShardsByBucketKey)
        assertImmutable(captured)
    }

    @Test
    fun `record remove reset and replacement cannot mutate historical snapshots`() {
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.getOrCreateDeviceId()).thenReturn("device-a")
        }.use {
            val store = store()
            val initial = counters("track|7", "track|8")
            store.replaceFromRoom(initial, 10)
            val beforeRecord = store.snapshot()

            store.recordLocalDelta("track|7", 0, 7, 1, 300, 10)
            val recorded = store.snapshot()
            assertEquals(initial, beforeRecord)
            assertEquals(listOf(shard().copy(totalListenMs = 37, playCount = 2, lastPlayedAt = 300)), recorded.trackShards("track|7"))
            assertEquals(recorded.trackShards("track|7"), recorded.dailyShards(0, "track|7"))
            assertSame(beforeRecord.trackShards("track|8"), recorded.trackShards("track|8"))
            assertNotSame(beforeRecord.trackShardsByIdentity, recorded.trackShardsByIdentity)
            assertImmutable(recorded)

            store.removeTracks(setOf("track|7"))
            val removed = store.snapshot()
            assertEquals(setOf("track|8"), removed.trackShardsByIdentity.keys)
            assertEquals(setOf("0|track|8"), removed.dailyShardsByBucketKey.keys)
            assertEquals(setOf("track|7", "track|8"), recorded.trackShardsByIdentity.keys)
            assertImmutable(removed)

            store.reset(-9)
            val reset = store.snapshot()
            assertEquals(PlaybackStatsSyncCounterSnapshot(), reset)
            assertEquals(0L, store.epochStartedAt())
            assertEquals(setOf("track|8"), removed.trackShardsByIdentity.keys)
            assertImmutable(reset)

            store.replaceFromSync(
                listOf(SyncTrackStat(identityKey = "track|9", counterShards = listOf(shard()))),
                emptyList(), 10
            )
            assertEquals(PlaybackStatsSyncCounterSnapshot(), reset)
            assertEquals(initial, beforeRecord)
            assertEquals(setOf("track|9"), store.snapshot().trackShardsByIdentity.keys)
        }
    }

    @Test
    fun `epoch transition starts fresh counters without rewriting captured state`() {
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.getOrCreateDeviceId()).thenReturn("device-a")
        }.use {
            val store = store()
            store.replaceFromRoom(counters("track|7", "track|8"), 10)
            val before = store.snapshot()

            store.recordLocalDelta("track|7", 0, 7, 1, 300, 20)

            assertEquals(setOf("track|7", "track|8"), before.trackShardsByIdentity.keys)
            assertEquals(listOf(shard()), before.trackShards("track|7"))
            assertEquals(20L, store.epochStartedAt())
            assertEquals(setOf("track|7"), store.snapshot().trackShardsByIdentity.keys)
            assertEquals(listOf(SyncPlaybackCounterShard("device-a", 20, 7, 1, 300, 300)), store.snapshot().trackShards("track|7"))
            assertImmutable(store.snapshot())
        }
    }

    @Test
    fun `damaged legacy fields fail without replacing owned state`() {
        val store = store()
        store.replaceFromRoom(counters("track|7"), 10)
        val before = store.snapshot()
        val damaged = listOf(
            "{broken", "null", "{\"trackShardsByIdentity\":null}",
            "{\"dailyShardsByBucketKey\":null}",
            "{\"trackShardsByIdentity\":{\"track|7\":[{\"deviceId\":null}]}}"
        )

        for (text in damaged) {
            counterFile().writeText(text)
            assertTrue(text, runCatching { store.loadLegacy() }.isFailure)
            assertEquals(text, counterFile().readText())
            assertSame(before.trackShardsByIdentity, store.snapshot().trackShardsByIdentity)
            assertSame(before.dailyShardsByBucketKey, store.snapshot().dailyShardsByBucketKey)
            assertEquals(10L, store.epochStartedAt())
        }
    }

    @Test
    fun `missing legacy file and empty object retain valid immutable empty state`() {
        val store = store()
        store.loadLegacy()
        assertEquals(PlaybackStatsSyncCounterSnapshot(), store.snapshot())
        assertImmutable(store.snapshot())

        counterFile().writeText("{}")
        store.loadLegacy()

        assertEquals(0L, store.epochStartedAt())
        assertEquals(PlaybackStatsSyncCounterSnapshot(), store.snapshot())
        assertImmutable(store.snapshot())
    }

    @Test
    fun `owned immutable snapshot keeps legacy projection format readable`() {
        val store = store()
        store.replaceFromRoom(counters("track|7", "track|8"), 10)
        val captured = store.snapshot()

        assertTrue(store.persistLegacyProjection(captured, 10))
        val recovered = store()
        recovered.loadLegacy()

        assertEquals(captured, recovered.snapshot())
        assertEquals(10L, recovered.epochStartedAt())
        assertImmutable(recovered.snapshot())
    }

    private fun store(): PlaybackStatsCounterStore {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return PlaybackStatsCounterStore(context, Gson())
    }

    private fun counterFile(): File = File(temporaryFolder.root, "playback_stats_counters.json")

    private fun shard() = SyncPlaybackCounterShard("device-a", 10, 30, 1, 100, 200)

    private fun counters(vararg identities: String) = PlaybackStatsSyncCounterSnapshot(
        trackShardsByIdentity = identities.associateWith { listOf(shard()) },
        dailyShardsByBucketKey = identities.associate { "0|$it" to listOf(shard()) }
    )

    private fun assertImmutable(snapshot: PlaybackStatsSyncCounterSnapshot) {
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.trackShardsByIdentity as MutableMap<String, List<SyncPlaybackCounterShard>>)["injected"] = listOf(shard())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.dailyShardsByBucketKey as MutableMap<String, List<SyncPlaybackCounterShard>>)["injected"] = listOf(shard())
        }
        for (shards in snapshot.trackShardsByIdentity.values + snapshot.dailyShardsByBucketKey.values) {
            assertThrows(UnsupportedOperationException::class.java) {
                (shards as MutableList<SyncPlaybackCounterShard>).add(shard())
            }
        }
    }
}
