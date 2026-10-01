package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomSnapshot
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException

class PlaybackStatsRepositoryRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed Room load cannot replace primary with missing JSON or playback deltas`() = runTest {
        val primary = snapshot()
        var stored = primary
        var readFails = true
        val room = mock(PlaybackStatsRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (readFails) throw IOException("Room read unavailable")
            stored
        }
        doAnswer { invocation ->
            stored = PlaybackStatsRoomSnapshot(
                stats = invocation.getArgument(0),
                dailyStats = invocation.getArgument(1),
                counterSnapshot = invocation.getArgument(2),
                counterEpochStartedAt = invocation.getArgument(3),
                clearedAt = invocation.getArgument(4)
            )
            Unit
        }.`when`(room).importLegacyAndPromote(
            anyList(), anyList(), any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            anyLong(), anyLong(), anyLong()
        )
        val repository = repository(room, backgroundScope)

        assertFalse(repository.awaitInitialized())
        repository.recordListenDeltaNow(song(), 10_000, 0, scheduleSync = false)
        assertEquals(primary, stored)
        assertFalse(File(temporaryFolder.root, "playback_stats.json").exists())
        assertTrue(runCatching { repository.syncCounterSnapshot() }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            repository.applyMergedStats(emptyList(), 0L)
        }.exceptionOrNull() is IOException)
        assertEquals(primary, stored)

        readFails = false
        assertTrue(repository.awaitInitialized())
        assertEquals(primary.stats, repository.statsFlow.value)
        assertEquals(50L, repository.statsClearedAtFlow.value)
        assertEquals(primary, stored)
    }

    @Test
    fun `corrupt legacy counter prevents promotion until repaired`() = runTest {
        var imported = false
        val room = mock(PlaybackStatsRoomStore::class.java)
        doAnswer { imported = true; Unit }.`when`(room).importLegacyAndPromote(
            anyList(), anyList(), any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            anyLong(), anyLong(), anyLong()
        )
        val counterFile = File(temporaryFolder.root, "playback_stats_counters.json")
        counterFile.writeText("{broken")
        val repository = repository(room, backgroundScope)

        assertFalse(repository.awaitInitialized())
        assertFalse(imported)
        assertEquals("{broken", counterFile.readText())

        counterFile.writeText("{}")
        assertTrue(repository.awaitInitialized())
        assertTrue(imported)
        assertTrue(repository.statsFlow.value.isEmpty())
    }

    @Test
    fun `Room primary ignores corrupt legacy statistics and counter JSON`() = runTest {
        val room = mock(PlaybackStatsRoomStore::class.java)
        val primary = snapshot()
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        File(temporaryFolder.root, "playback_stats.json").writeText("{broken")
        File(temporaryFolder.root, "playback_stats_counters.json").writeText("{broken")
        File(temporaryFolder.root, "playback_stats_meta.json").writeText("{broken")

        val repository = repository(room, backgroundScope)

        assertTrue(repository.awaitInitialized())
        assertEquals(primary.stats, repository.statsFlow.value)
        assertEquals(primary.counterSnapshot, repository.syncCounterSnapshot())
    }

    @Test
    fun `constructor starts loading on scope without waiting for Room`() = runTest {
        var readStarted = false
        val room = mock(PlaybackStatsRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer {
            readStarted = true
            snapshot()
        }

        val repository = repository(room, backgroundScope)

        assertFalse(readStarted)
        assertTrue(runCatching { repository.syncCounterSnapshot() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertTrue(readStarted)
        assertEquals(40_000L, repository.statsFlow.value.single().totalListenMs)
    }

    @Test
    fun `corrupt legacy track daily and metadata files cannot be promoted as empty data`() = runTest {
        val files = mapOf(
            "playback_stats.json" to "[]",
            "playback_stats_daily.json" to "[]",
            "playback_stats_meta.json" to "{}"
        )
        for ((name, validText) in files) {
            var imported = false
            val room = mock(PlaybackStatsRoomStore::class.java)
            doAnswer { imported = true; Unit }.`when`(room).importLegacyAndPromote(
                anyList(), anyList(), any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
                anyLong(), anyLong(), anyLong()
            )
            val file = File(temporaryFolder.root, name)
            file.writeText("{broken")
            val repository = repository(room, backgroundScope)

            assertFalse(name, repository.awaitInitialized())
            assertFalse(name, imported)
            assertEquals("{broken", file.readText())
            file.writeText(validText)
            assertTrue(name, repository.awaitInitialized())
            assertTrue(name, imported)
        }
    }

    @Test
    fun `a complete Room snapshot may use JSON fallback after a failed write`() = runTest {
        val room = mock(PlaybackStatsRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(snapshot())
        failRoomWrites(room)
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())

        repository.applyMergedStats(emptyList(), 300L)

        assertFalse(repository.hasPendingWrites())
        assertTrue(repository.statsFlow.value.isEmpty())
        assertEquals("[]", File(temporaryFolder.root, "playback_stats.json").readText())
        assertTrue(File(temporaryFolder.root, "playback_stats_meta.json").readText().contains("300"))
        assertEquals(PlaybackStatsSyncCounterSnapshot(), repository.syncCounterSnapshot())
    }

    @Test
    fun `failed JSON fallback marker keeps writes dirty and rejects merged sync success`() = runTest {
        val room = mock(PlaybackStatsRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(snapshot())
        failRoomWrites(room)
        doAnswer { throw IOException("Room metadata unavailable") }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())

        assertTrue(runCatching {
            repository.applyMergedStats(emptyList(), 300L)
        }.exceptionOrNull() is IOException)
        assertTrue(repository.hasPendingWrites())
        assertTrue(runCatching { repository.syncCounterSnapshot() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `captured persisted snapshot survives a later clear that cannot save its fallback marker`() = runTest {
        val room = mock(PlaybackStatsRoomStore::class.java)
        val primary = snapshot()
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        failRoomWrites(room)
        doAnswer { throw IOException("Room metadata unavailable") }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        val captured = repository.syncSnapshot()

        assertTrue(runCatching {
            repository.applyMergedStats(emptyList(), 300L)
        }.exceptionOrNull() is IOException)

        assertTrue(repository.statsFlow.value.isEmpty())
        assertEquals(300L, repository.statsClearedAtFlow.value)
        assertEquals(primary.stats, captured.stats)
        assertEquals(primary.dailyStats, captured.dailyStats)
        assertEquals(primary.counterSnapshot, captured.counterSnapshot)
        assertEquals(primary.clearedAt, captured.clearedAt)
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `committed JSON snapshot survives later failed or interrupted projection writes`() = runTest {
        for (failure in listOf("playback_stats_daily.json", "playback_stats_counters.json", "partial")) {
            val directory = temporaryFolder.newFolder(failure)
            val primary = completeSnapshot()
            val room = mock(PlaybackStatsRoomStore::class.java)
            `when`(room.readIfRoomPrimary()).thenReturn(primary)
            failRoomWrites(room)
            val liveRepository = repository(room, backgroundScope, directory)
            assertTrue(liveRepository.awaitInitialized())
            liveRepository.applyMergedStats(emptyList(), primary.clearedAt)
            val committed = liveRepository.syncSnapshot()
            val metadata = File(directory, "playback_stats_meta.json")
            val previousCommit = metadata.readText()

            if (failure == "partial") {
                File(directory, "playback_stats.json").writeText("[]")
            } else {
                blockAtomicReplacement(File(directory, failure))
                assertTrue(runCatching {
                    liveRepository.applyMergedStats(emptyList(), primary.stats.single().lastPlayedAt + 1_000)
                }.exceptionOrNull() is IOException)
                assertTrue(liveRepository.hasPendingWrites())
            }
            assertEquals("[]", File(directory, "playback_stats.json").readText())
            assertEquals(previousCommit, metadata.readText())

            var imported: PlaybackStatsRoomSnapshot? = null
            val recoveredRoom = mock(PlaybackStatsRoomStore::class.java)
            captureImports(recoveredRoom) { imported = it }
            val recovered = repository(recoveredRoom, backgroundScope, directory)

            assertTrue(recovered.awaitInitialized())
            assertEquals(committed, recovered.syncSnapshot())
            assertEquals(PlaybackStatsRoomSnapshot(
                committed.stats, committed.dailyStats, committed.counterSnapshot,
                committed.counterEpochStartedAt, committed.clearedAt
            ), imported)
        }
    }

    @Test
    fun `old legacy projections get a complete commit before their first interrupted save`() = runTest {
        val primary = completeSnapshot()
        writeLegacyProjections(temporaryFolder.root, primary)
        val room = mock(PlaybackStatsRoomStore::class.java)
        failRoomPromotion(room)
        val liveRepository = repository(room, backgroundScope)
        assertTrue(liveRepository.awaitInitialized())
        blockAtomicReplacement(File(temporaryFolder.root, "playback_stats_counters.json"))

        assertTrue(runCatching {
            liveRepository.applyMergedStats(emptyList(), primary.stats.single().lastPlayedAt + 1_000)
        }.exceptionOrNull() is IOException)
        assertEquals("[]", File(temporaryFolder.root, "playback_stats.json").readText())
        assertEquals("[]", File(temporaryFolder.root, "playback_stats_daily.json").readText())
        verify(room, never()).markLegacyJsonPrimary(anyLong())

        var imported: PlaybackStatsRoomSnapshot? = null
        val recoveredRoom = mock(PlaybackStatsRoomStore::class.java)
        captureImports(recoveredRoom) { imported = it }
        val recovered = repository(recoveredRoom, backgroundScope)

        assertTrue(recovered.awaitInitialized())
        assertEquals(primary, imported)
        assertEquals(primary.stats, recovered.statsFlow.value)
        assertEquals(primary.dailyStats, recovered.dailyStatsFlow.value)
        assertEquals(primary.counterSnapshot, recovered.syncCounterSnapshot())
        assertEquals(primary.clearedAt, recovered.statsClearedAtFlow.value)
    }

    @Test
    fun `failed first commit leaves old legacy projections untouched`() = runTest {
        val primary = completeSnapshot()
        writeLegacyProjections(temporaryFolder.root, primary)
        val room = mock(PlaybackStatsRoomStore::class.java)
        failRoomPromotion(room)
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        val projections = listOf("playback_stats.json", "playback_stats_daily.json", "playback_stats_counters.json")
            .associateWith { File(temporaryFolder.root, it).readText() }
        blockAtomicReplacement(File(temporaryFolder.root, "playback_stats_meta.json"))

        assertTrue(runCatching {
            repository.applyMergedStats(emptyList(), primary.stats.single().lastPlayedAt + 1_000)
        }.exceptionOrNull() is IOException)

        projections.forEach { (name, text) ->
            assertEquals(text, File(temporaryFolder.root, name).readText())
        }
        verify(room, never()).markLegacyJsonPrimary(anyLong())
        assertTrue(repository.hasPendingWrites())
    }

    private fun blockAtomicReplacement(file: File) {
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "blocker").writeText("prevent replacing a nonempty directory")
    }

    private fun writeLegacyProjections(directory: File, snapshot: PlaybackStatsRoomSnapshot) {
        val gson = Gson()
        File(directory, "playback_stats.json").writeText(gson.toJson(snapshot.stats))
        File(directory, "playback_stats_daily.json").writeText(gson.toJson(snapshot.dailyStats))
        File(directory, "playback_stats_counters.json").writeText(gson.toJson(mapOf(
            "epochStartedAt" to snapshot.counterEpochStartedAt,
            "trackShardsByIdentity" to snapshot.counterSnapshot.trackShardsByIdentity,
            "dailyShardsByBucketKey" to snapshot.counterSnapshot.dailyShardsByBucketKey
        )))
        File(directory, "playback_stats_meta.json").writeText(gson.toJson(mapOf("clearedAt" to snapshot.clearedAt)))
    }

    private suspend fun captureImports(room: PlaybackStatsRoomStore, onImport: (PlaybackStatsRoomSnapshot) -> Unit) {
        doAnswer { invocation ->
            onImport(PlaybackStatsRoomSnapshot(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(4)
            ))
            Unit
        }.`when`(room).importLegacyAndPromote(
            anyList(), anyList(), any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            anyLong(), anyLong(), anyLong()
        )
    }

    private suspend fun failRoomPromotion(room: PlaybackStatsRoomStore) {
        doAnswer { throw IOException("Room promotion unavailable") }.`when`(room).importLegacyAndPromote(
            anyList(), anyList(), any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            anyLong(), anyLong(), anyLong()
        )
    }

    private suspend fun failRoomWrites(room: PlaybackStatsRoomStore) {
        doAnswer { throw IOException("Room write unavailable") }.`when`(room).writeIncremental(
            anyList(), anyList(), anyList(), anyList(),
            any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            any(PlaybackStatsSyncCounterSnapshot::class.java) ?: PlaybackStatsSyncCounterSnapshot(),
            anyLong(), anyLong(), anyLong()
        )
    }

    private suspend fun repository(
        room: PlaybackStatsRoomStore,
        scope: CoroutineScope,
        directory: File = temporaryFolder.root
    ): PlaybackStatsRepository {
        doAnswer { invocation ->
            val writeSnapshot = invocation.getArgument<() -> Boolean>(0)
            writeSnapshot() && runBlocking { room.markLegacyJsonPrimary(); true }
        }.`when`(room).commitLegacyFallback(any<() -> Boolean>() ?: { false })
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(directory)
        return PlaybackStatsRepository(context, room, scope)
    }

    private fun snapshot() = PlaybackStatsRoomSnapshot(
        stats = listOf(TrackStat(
            id = 7,
            name = "Song",
            artist = "Artist",
            album = "netease",
            albumId = 8,
            coverUrl = null,
            durationMs = 180_000,
            totalListenMs = 40_000,
            playCount = 1,
            lastPlayedAt = 200,
            firstPlayedAt = 100,
            mediaUri = null,
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = "track|7"
        )),
        dailyStats = emptyList(),
        counterSnapshot = PlaybackStatsSyncCounterSnapshot(),
        counterEpochStartedAt = 50,
        clearedAt = 50
    )

    private fun completeSnapshot(): PlaybackStatsRoomSnapshot {
        val now = System.currentTimeMillis()
        val track = snapshot().stats.single().copy(lastPlayedAt = now, firstPlayedAt = now - 40_000)
        val dayStartAt = playbackStatsDayStartAt(now)
        val clearedAt = now - 60_000
        val bucket = PlaybackStatBucket(
            dayStartAt = dayStartAt, id = track.id, name = track.name, artist = track.artist,
            album = track.album, albumId = track.albumId, coverUrl = null, durationMs = track.durationMs,
            totalListenMs = track.totalListenMs, playCount = track.playCount,
            lastPlayedAt = track.lastPlayedAt, firstPlayedAt = track.firstPlayedAt,
            mediaUri = null, localFilePath = null, localFileName = null, customName = null,
            customArtist = null, customCoverUrl = null, identityKey = track.identityKey
        )
        val shard = SyncPlaybackCounterShard("device", clearedAt, 40_000, 1, track.firstPlayedAt, track.lastPlayedAt)
        return PlaybackStatsRoomSnapshot(
            stats = listOf(track), dailyStats = listOf(bucket),
            counterSnapshot = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(track.identityKey to listOf(shard)),
                dailyShardsByBucketKey = mapOf(
                    PlaybackStatsSyncCounterSnapshot.dailyCounterKey(dayStartAt, track.identityKey) to listOf(shard)
                )
            ),
            counterEpochStartedAt = clearedAt,
            clearedAt = clearedAt
        )
    }

    private fun song() = SongItem(
        id = 7,
        name = "Song",
        artist = "Artist",
        album = "netease",
        albumId = 8,
        durationMs = 180_000,
        coverUrl = null
    )
}
