package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsBackupApplyTest {
    @Test fun pagedBackupMergesAllRowsAndLiftsDailyTotalsOnceBeforeCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(1).copy(customName = "my title")
            val local = track(999).copy(localFilePath = "/audio.flac")
            store.importLegacyAndPromote(listOf(original, local), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            val incoming = (1L..520L).map { syncTrack(it) }
            val buckets = incoming.map { bucket(it, 0) } + incoming.map { bucket(it, 86_400_000) }
            repository.applyMergedStats(source(incoming, buckets), 0, respectLocalClear = false)
            val restored = checkNotNull(store.readIfRoomPrimary())
            assertEquals(521, restored.stats.size)
            assertEquals(1040, restored.dailyStats.size)
            assertEquals("my title", store.readTrack(original.identityKey)?.customName)
            assertEquals(4, store.readTrack(original.identityKey)?.playCount)
            assertEquals(local, store.readTrack(local.identityKey))
            assertEquals(revision + 1, store.readPrimaryState()?.revision)
            repository.applyMergedStats(source(incoming, buckets), 0, respectLocalClear = false)
            assertEquals(4, store.readTrack(original.identityKey)?.playCount)
        } finally { database.close() }
    }

    @Test fun failedOrCancelledFinalCursorReadCannotCommitPartialBackupPages() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (failure in listOf(IOException("read failed"), CancellationException("cancelled"))) {
            val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
            try {
                val store = PlaybackStatsRoomStore(database)
                val original = track(1)
                store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
                val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
                assertTrue(repository.awaitInitialized())
                val revision = checkNotNull(store.readPrimaryState()).revision
                val incoming = source((1L..256L).map(::syncTrack), emptyList(), failure)
                assertSame(failure, runCatching { repository.applyMergedStats(incoming, 0, false) }.exceptionOrNull())
                assertEquals(listOf(original), store.readIfRoomPrimary()?.stats)
                assertEquals(revision, store.readPrimaryState()?.revision)
            } finally { database.close() }
        }
    }

    @Test fun concurrentPrimaryWriteRejectsThePreparedBackupWithoutOverwritingIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(1)
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val newer = original.copy(totalListenMs = 99_000)
            val delegate = source(listOf(syncTrack(1)), emptyList())
            val incoming = object : SyncPlaybackSource by delegate {
                override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
                    val cursor = delegate.openTracks()
                    return object : SyncPlaybackCursor<SyncTrackStat> by cursor {
                        override suspend fun nextPage(): List<SyncTrackStat> {
                            val page = cursor.nextPage()
                            if (page.isNotEmpty()) store.replaceAll(listOf(newer), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
                            return page
                        }
                    }
                }
            }
            assertTrue(runCatching { repository.applyMergedStats(incoming, 0, false) }.exceptionOrNull() is IOException)
            assertEquals(newer, store.readTrack(original.identityKey))
        } finally { database.close() }
    }

    private fun track(id: Long) = TrackStat(id, "song", "artist", "netease", 0, null, 180_000, 30_000, 1, 200, 100,
        null, null, null, null, null, null, "track|$id")

    private fun syncTrack(id: Long) = SyncTrackStat("track|$id", "song", "artist", "netease", 60_000, 2, 300, 100, null, 180_000, null, id, 0)

    private fun bucket(track: SyncTrackStat, day: Long) = SyncPlaybackStatBucket(day, track.identityKey, track.name, track.artist,
        track.album, track.totalListenMs, track.playCount, track.lastPlayedAt, track.firstPlayedAt, track.coverUrl,
        track.durationMs, track.mediaUri, track.id, track.albumId)

    private fun source(tracks: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>, failure: Exception? = null) = object : SyncPlaybackSource {
        override fun openTracks() = cursor(tracks, failure)
        override fun openBuckets(order: SyncPlaybackBucketOrder) = cursor(buckets, null)
        override fun close() = Unit
    }

    private fun <T> cursor(rows: List<T>, failure: Exception?) = object : SyncPlaybackCursor<T> {
        private var offset = 0
        override suspend fun nextPage(): List<T> {
            if (offset == rows.size) { failure?.let { throw it }; return emptyList() }
            val end = minOf(offset + 256, rows.size)
            return rows.subList(offset, end).also { offset = end }
        }
        override fun close() = Unit
    }
}
