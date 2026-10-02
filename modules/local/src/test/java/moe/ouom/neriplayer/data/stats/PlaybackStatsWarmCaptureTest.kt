package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsCaptureStamp
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsDeltaRows
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString

class PlaybackStatsWarmCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun resetCaptureBarrier() {
        PlaybackStatsCaptureBarrier.install {}
    }

    @Test fun runtimeCaptureBarrierDrainsBeforeAllThreeSnapshotEntrypoints() = runTest {
        for (destination in CaptureDestination.entries) {
            val fixture = open(backgroundScope)
            var calls = 0
            try {
                PlaybackStatsCaptureBarrier.install { context ->
                    assertSame(fixture.context, context)
                    calls++
                    fixture.recordSpoolEvent()
                    fixture.repository.flushPendingWrites()
                }
                val listenedMs = withContext(Dispatchers.IO) {
                    withTimeout(10_000) { capture(fixture, destination) }
                }
                assertEquals(destination.name, 3_000L, listenedMs)
                assertEquals(1, calls)
                assertTrue(fixture.pending.isEmpty())
            } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
        }
    }

    @Test fun runtimeCaptureBarrierInvalidatesAWarmSnapshotBeforeItsStampIsChecked() = runTest {
        val fixture = open(backgroundScope)
        var calls = 0
        try {
            fixture.borrow().playback.use { assertEquals(1_000L, read(it).single().totalListenMs) }
            PlaybackStatsCaptureBarrier.install {
                if (++calls == 1) fixture.recordSpoolEvent()
            }
            fixture.borrow().playback.use { assertEquals(3_000L, read(it).single().totalListenMs) }
            assertEquals(2, fixture.captures)
            fixture.borrow().playback.use { assertEquals(3_000L, read(it).single().totalListenMs) }
            assertEquals(2, calls)
            assertEquals(2, fixture.captures)
        } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
    }

    @Test fun failedRuntimeCaptureBarrierRejectsEverySnapshotBeforeFreezingAndAllowsRetry() = runTest {
        for (destination in CaptureDestination.entries) {
            val fixture = open(backgroundScope)
            val failure = IOException("runtime spool unavailable")
            try {
                PlaybackStatsCaptureBarrier.install { throw failure }
                assertPropagatedSame(failure, runCatching { capture(fixture, destination) }.exceptionOrNull())
                assertEquals(0, fixture.captures)
                assertTrue(fixture.sessions().isEmpty())
                PlaybackStatsCaptureBarrier.install {}
                assertEquals(1_000L, capture(fixture, destination))
            } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
        }
    }

    @Test fun cancellationWhileAwaitingRuntimeCaptureBarrierNeverFreezesOrKeepsACacheOwner() = runTest {
        val fixture = open(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        PlaybackStatsCaptureBarrier.install { entered.complete(Unit); release.await() }
        val capture = async(Dispatchers.IO) { fixture.borrow() }
        try {
            withContext(Dispatchers.IO) { withTimeout(10_000) { entered.await() } }
            assertEquals(0, fixture.captures)
            withContext(Dispatchers.IO) { withTimeout(10_000) { capture.cancelAndJoin() } }
            assertTrue(fixture.sessions().isEmpty())
            PlaybackStatsCaptureBarrier.install {}
            fixture.borrow().playback.close()
            assertEquals(1, fixture.captures)
        } finally {
            capture.cancelAndJoin()
            PlaybackStatsCaptureBarrier.install {}
            fixture.close()
        }
    }

    @Test fun failedManualRestoreBarrierStopsBeforeFreezingTheExistingStatistics() = runTest {
        val fixture = open(backgroundScope)
        val failure = IOException("runtime spool cannot drain before restore")
        try {
            PlaybackStatsCaptureBarrier.install { throw failure }
            assertPropagatedSame(failure, runCatching {
                fixture.repository.applyMergedStats(emptyList(), 0, respectLocalClear = false)
            }.exceptionOrNull())
            assertEquals(0, fixture.captures)
            assertEquals(50L, fixture.stamp.state.clearedAt)
        } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
    }

    @Test fun forceRestoreGuardIsEnteredBeforeAnyRoomFreeze() = runTest {
        val fixture = open(backgroundScope)
        val failure = IOException("runtime restore guard cannot suspend sampling")
        try {
            PlaybackStatsCaptureBarrier.install({}, { context, _ ->
                assertSame(fixture.context, context)
                throw failure
            })
            assertPropagatedSame(failure, runCatching {
                fixture.repository.applyMergedStats(emptyList(), 0, respectLocalClear = false)
            }.exceptionOrNull())
            assertEquals(0, fixture.captures)
            assertEquals(50L, fixture.stamp.state.clearedAt)
        } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
    }

    @Test fun delayedOldClearNotificationReadsTheCommittedRestoreFence() = runTest {
        val releaseOldNotification = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val changes = flow {
            releaseOldNotification.await()
            emit(50L)
            delivered.complete(Unit)
        }
        val fixture = open(backgroundScope, changes)
        try {
            fixture.configureEmptyBackupCommit(0)
            fixture.repository.applyMergedStats(emptyList(), 0, respectLocalClear = false)
            assertEquals(0L, fixture.repository.statsClearedAtFlow.value)
            assertEquals(0L, fixture.stamp.state.clearedAt)
            releaseOldNotification.complete(Unit)
            withTimeout(10_000) { delivered.await() }
            assertEquals(0L, fixture.repository.statsClearedAtFlow.value)
        } finally { releaseOldNotification.complete(Unit); fixture.close() }
    }

    @Test fun unavailablePrimaryDuringClearObservationKeepsThePublishedRestoreFence() = runTest {
        for (failure in listOf(null, IOException("primary state read failed"))) {
            val releaseOldNotification = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            val changes = flow {
                releaseOldNotification.await()
                try { emit(50L) } finally { delivered.complete(Unit) }
            }
            val fixture = open(backgroundScope, changes)
            try {
                fixture.configureEmptyBackupCommit(0)
                fixture.repository.applyMergedStats(emptyList(), 0, respectLocalClear = false)
                doAnswer {
                    failure?.let { throw it }
                    null
                }.`when`(fixture.room).readPrimaryState()
                releaseOldNotification.complete(Unit)
                withTimeout(10_000) { delivered.await() }
                assertEquals(0L, fixture.repository.statsClearedAtFlow.value)
            } finally { releaseOldNotification.complete(Unit); fixture.close() }
        }
    }

    @Test fun unchangedCaptureAndOpenCursorSurviveSessionAndCacheOwnerClose() = runTest {
        val fixture = open(backgroundScope)
        try {
            val first = fixture.borrow()
            val second = fixture.borrow()
            val cursor = first.playback.openTracks()
            try {
                first.playback.close()
                assertEquals(1, fixture.captures)
                fixture.repository.releaseSyncCaptureCache()
                FileSyncPlaybackDatasetStore(fixture.directory)
                assertEquals("song", read(second.playback).single().name)
                second.playback.close()
                assertEquals(1, fixture.sessions().size)
                assertEquals("song", cursor.nextPage().single().name)
                assertTrue(cursor.nextPage().isEmpty())
            } finally { cursor.close(); second.playback.close(); first.playback.close() }
            assertTrue(fixture.sessions().isEmpty())
        } finally { fixture.close() }
    }

    @Test fun revisionClearEpochSequenceDatabaseInstanceAndProjectionIndependentlyInvalidate() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            val mutations: List<() -> Unit> = listOf(
                { fixture.stamp = fixture.stamp.copy(state = fixture.stamp.state.copy(revision = 2)) },
                { fixture.stamp = fixture.stamp.copy(state = fixture.stamp.state.copy(clearedAt = 60)) },
                { fixture.stamp = fixture.stamp.copy(state = fixture.stamp.state.copy(counterEpochStartedAt = 70)) },
                { fixture.stamp = fixture.stamp.copy(journalSequence = 1) },
                { fixture.stamp = fixture.stamp.copy(databaseInstance = "new-database") },
                { fixture.projection = fixture.projection.copy(localeTags = "zh-TW") },
                { fixture.projection = fixture.projection.copy(localAlbumNames = setOf("netease")) },
                { fixture.projection = fixture.projection.copy(policyVersion = 2) }
            )
            for ((index, mutate) in mutations.withIndex()) {
                mutate()
                fixture.borrow().playback.use { source ->
                    val rows = read(source)
                    assertEquals(if (index >= 6) 0 else 1, rows.size)
                }
                assertEquals(index + 2, fixture.captures)
                fixture.borrow().playback.close()
                assertEquals(index + 2, fixture.captures)
            }
        } finally { fixture.close() }
    }

    @Test fun failedJournalDrainEvictsCacheAndDoesNotDeleteAnExistingBorrow() = runTest {
        val fixture = open(backgroundScope)
        try {
            val existing = fixture.borrow()
            try {
                fixture.pendingFailure = IOException("journal unavailable")
                assertTrue(runCatching { fixture.borrow() }.exceptionOrNull() is IOException)
                assertEquals("song", read(existing.playback).single().name)
                fixture.pendingFailure = null
                fixture.borrow().playback.close()
                assertEquals(2, fixture.captures)
            } finally { existing.playback.close() }
        } finally { fixture.close() }
    }

    @Test fun failedStampConfirmationCannotBorrowPreviouslyValidatedFiles() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            val failure = IOException("primary confirmation unavailable")
            fixture.stampFailure = failure
            assertPropagatedSame(failure, runCatching { fixture.borrow() }.exceptionOrNull())
            assertTrue(fixture.sessions().isEmpty())
            fixture.stampFailure = null
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun pendingJournalIsDrainedBeforeDecidingToReuseTheCapture() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            fixture.pending.add(PlaybackStatsPendingDeltaEntity("event", 1, Gson().toJson(fixture.track), 2_000, 0, 300, 50, "device"))
            fixture.borrow().playback.use { assertEquals(3_000L, read(it).single().totalListenMs) }
            assertTrue(fixture.pending.isEmpty())
            assertEquals(2, fixture.captures)
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun corruptWarmFileRecapturesAndTheReplacementCanBeReused() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            val file = File(fixture.sessions().single(), "tracks-ordered.bin")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            fixture.borrow().playback.use { assertEquals("song", read(it).single().name) }
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
            assertEquals(1, fixture.sessions().size)
        } finally { fixture.close() }
    }

    @Test fun sameLengthCorruptionOfNonemptyIdentityDayIndexRecapturesTheRoomSnapshot() = runTest {
        val fixture = open(backgroundScope)
        try {
            val bucket = PlaybackStatBucket(100, 7, "song", "artist", "netease", 0, null, 180_000,
                1_000, 1, 200, 100, null, null, null, null, null, null, "track|7")
            fixture.configureBucket(bucket)
            val expected = fixture.borrow().playback.use { source ->
                source.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY).use { cursor ->
                    cursor.nextPage().also { page ->
                        assertEquals("track|7", page.single().identityKey)
                        assertEquals(100L, page.single().dayStartAt)
                        assertTrue(cursor.nextPage().isEmpty())
                    }
                }
            }
            val session = fixture.sessions().single()
            val tracks = File(session, "tracks-ordered.bin")
            val dayIndex = File(session, "buckets-ordered.bin")
            val originalTracks = tracks.readBytes()
            val originalDayIndex = dayIndex.readBytes()
            val identityIndex = session.listFiles().orEmpty().single {
                it.name.startsWith("identity-buckets-") && it.extension == "bin"
            }
            val bytes = identityIndex.readBytes()
            assertTrue(bytes.isNotEmpty())
            val originalLength = identityIndex.length()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            identityIndex.writeBytes(bytes)
            assertEquals(originalLength, identityIndex.length())
            assertArrayEquals(originalTracks, tracks.readBytes())
            assertArrayEquals(originalDayIndex, dayIndex.readBytes())

            fixture.borrow().playback.use { source ->
                assertEquals("song", read(source).single().name)
                source.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY).use { cursor ->
                    assertEquals(expected, cursor.nextPage())
                    assertTrue(cursor.nextPage().isEmpty())
                }
            }
            assertEquals(2, fixture.captures)
            assertFalse(session.exists())
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
            assertEquals(1, fixture.sessions().size)
        } finally { fixture.close() }
    }

    @Test fun failedExportDoesNotCachePartialFilesAndCanRecover() = runTest {
        val fixture = open(backgroundScope)
        try {
            val failure = IOException("export failed")
            fixture.exportFailure = failure
            assertPropagatedSame(failure, runCatching { fixture.borrow() }.exceptionOrNull())
            assertTrue(fixture.sessions().isEmpty())
            fixture.exportFailure = null
            fixture.borrow().playback.use { assertEquals("song", read(it).single().name) }
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun cancelledExportPropagatesAndTheNextCaptureCanRecover() = runTest {
        val fixture = open(backgroundScope)
        try {
            val failure = CancellationException("export cancelled")
            fixture.exportFailure = failure
            assertPropagatedSame(failure, runCatching { fixture.borrow() }.exceptionOrNull())
            assertTrue(fixture.sessions().isEmpty())
            fixture.exportFailure = null
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun changedStampAfterValidationRejectsTheWarmBorrowAndCapturesCurrentRows() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            fixture.afterStampRead = { reads ->
                if (reads == 4) {
                    fixture.stamp = fixture.stamp.copy(state = fixture.stamp.state.copy(revision = 2))
                    fixture.track = fixture.track.copy(name = "new song")
                }
            }
            fixture.borrow().playback.use { assertEquals("new song", read(it).single().name) }
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun failedConfirmationAfterRetainingTheWarmBorrowReleasesBothNewOwners() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.borrow().playback.close()
            val failure = IOException("confirmation failed after validation")
            fixture.afterStampRead = { reads -> if (reads == 4) throw failure }
            assertPropagatedSame(failure, runCatching { fixture.borrow() }.exceptionOrNull())
            assertTrue(fixture.sessions().isEmpty())
            fixture.afterStampRead = null
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun failedColdConfirmationClosesSealedOwnerAndItsBorrowBeforeRetry() = runTest {
        val fixture = open(backgroundScope)
        try {
            val failure = IOException("confirmation failed after sealing")
            fixture.afterStampRead = { reads -> if (reads == 2) throw failure }
            assertPropagatedSame(failure, runCatching { fixture.borrow() }.exceptionOrNull())
            assertTrue(fixture.sessions().isEmpty())
            fixture.afterStampRead = null
            fixture.borrow().playback.close()
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun concurrentWriteDuringColdCaptureReturnsSnapshotWithoutCachingIt() = runTest {
        val fixture = open(backgroundScope)
        try {
            fixture.afterExport = {
                fixture.stamp = fixture.stamp.copy(state = fixture.stamp.state.copy(revision = 2))
                fixture.track = fixture.track.copy(name = "new song")
                fixture.afterExport = null
            }
            val first = fixture.borrow()
            assertEquals(1L, first.state.revision)
            first.playback.use { assertEquals("song", read(it).single().name) }
            assertTrue(fixture.sessions().isEmpty())
            fixture.borrow().playback.use { assertEquals("new song", read(it).single().name) }
            assertEquals(2, fixture.captures)
        } finally { fixture.close() }
    }

    @Test fun promptCancellationAfterIoReturnReleasesAnUndeliveredBorrow() = runTest {
        val fixture = open(backgroundScope)
        val dispatcher = PausedReturnDispatcher()
        val task = async(dispatcher) { fixture.borrow() }
        try {
            val continuation = withContext(Dispatchers.IO) { withTimeout(10_000) { dispatcher.returned.await() } }
            assertEquals(1, fixture.captures)
            task.cancel()
            continuation.run()
            task.join()
            assertTrue(task.isCancelled)
            fixture.repository.releaseSyncCaptureCache()
            assertTrue(fixture.sessions().isEmpty())
        } finally { task.cancel(); fixture.close() }
    }

    private suspend fun open(scope: CoroutineScope, clearChanges: Flow<Long> = flowOf(50L)): Fixture {
        val fixture = Fixture(scope, temporary.newFolder(), clearChanges)
        fixture.configure()
        assertTrue(fixture.repository.awaitInitialized())
        return fixture
    }

    private class Fixture(scope: CoroutineScope, val directory: File, private val clearChanges: Flow<Long>) {
        val room = mock(PlaybackStatsRoomStore::class.java)
        private val database = mock(NeriUserDataDatabase::class.java)
        private val dao = mock(PlaybackStatsSnapshotDao::class.java)
        val context = mock(Context::class.java)
        val store = FileSyncPlaybackDatasetStore(directory)
        var stamp = PlaybackStatsCaptureStamp(PlaybackStatsRoomState(1, 50, 50), 0, "database")
        var projection = PlaybackStatsCaptureProjection("en", emptySet())
        var track = TrackStat(7, "song", "artist", "netease", 0, null, 180_000, 1_000, 1, 200, 100,
            null, null, null, null, null, null, "track|7")
        val pending = mutableListOf<PlaybackStatsPendingDeltaEntity>()
        var captures = 0
        var pendingFailure: Throwable? = null
        var stampFailure: Throwable? = null
        var exportFailure: Throwable? = null
        var afterStampRead: ((Int) -> Unit)? = null
        var afterExport: (() -> Unit)? = null
        private var stampReads = 0
        val repository = PlaybackStatsRepository(context, room, scope) { "device" }

        suspend fun configure() {
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.filesDir).thenReturn(directory)
            `when`(room.database).thenReturn(database)
            `when`(database.playbackStatsSnapshotDao()).thenReturn(dao)
            `when`(room.readPrimaryState()).thenAnswer { stamp.state }
            `when`(room.clearedAtFlow).thenReturn(clearChanges)
            doAnswer { pendingFailure?.let { throw it }; pending.toList() }.`when`(room).pendingDeltas()
            doAnswer {
                pending.clear()
                track = track.copy(totalListenMs = 3_000)
                stamp = stamp.copy(state = stamp.state.copy(revision = stamp.state.revision + 1), journalSequence = 1)
                Unit
            }.`when`(room).applyDelta(
                any(PlaybackStatsPendingDeltaEntity::class.java) ?: PlaybackStatsPendingDeltaEntity("matcher", 1, "{}", 0, 0, 0, 0, "device"),
                anyString() ?: "track|7", any<(PlaybackStatsDeltaRows) -> PlaybackStatsDeltaRows>() ?: { it }
            )
            doAnswer {
                stampReads++
                stampFailure?.let { throw it }
                afterStampRead?.invoke(stampReads)
                stamp
            }.`when`(room).readConfirmedCaptureStamp()
            doAnswer {
                captures++
                PlaybackStatsSnapshotEntity("frozen", stamp.state.revision, stamp.state.clearedAt,
                    stamp.state.counterEpochStartedAt, 100, true, "process")
            }.`when`(room).freezeSnapshot()
            doAnswer {
                listOf(PlaybackStatsSnapshotTrackEntity("frozen", track.toEntity().toSnapshotData())).also { afterExport?.invoke() }
            }.`when`(dao).trackPage("frozen", null, SYNC_PLAYBACK_PAGE_RECORDS)
            doAnswer {
                exportFailure?.let { throw it }
                emptyList<PlaybackStatsSnapshotTrackEntity>()
            }.`when`(dao).trackPage("frozen", "track|7", SYNC_PLAYBACK_PAGE_RECORDS)
            `when`(dao.trackCounters("frozen", listOf("track|7"))).thenReturn(emptyList())
            `when`(dao.bucketPage("frozen", null, null, SYNC_PLAYBACK_PAGE_RECORDS)).thenReturn(emptyList())
        }

        suspend fun borrow() = repository.borrowSyncCapture(store) { projection }

        suspend fun configureEmptyBackupCommit(clearedAt: Long) {
            `when`(dao.getSnapshot("frozen")).thenReturn(PlaybackStatsSnapshotEntity(
                "frozen", stamp.state.revision, stamp.state.clearedAt, stamp.state.counterEpochStartedAt,
                100, true, "process"
            ))
            `when`(dao.bucketIdentityPage("frozen", null, null, SYNC_PLAYBACK_PAGE_RECORDS)).thenReturn(emptyList())
            doAnswer {
                stamp = stamp.copy(state = stamp.state.copy(revision = stamp.state.revision + 1, clearedAt = clearedAt))
                true
            }.`when`(room).commitFrozenSnapshot("frozen", stamp.state.revision)
        }

        suspend fun recordSpoolEvent() {
            val song = SongItem(7, "song", "artist", "netease", 0, 180_000, null, sourceStableKey = "track|7")
            val trackJson = Gson().toJson(song.toStatisticsMetadata())
            doAnswer {
                pending.add(PlaybackStatsPendingDeltaEntity("spool", 1, trackJson, 2_000, 0, 300, 50, "device"))
                true
            }.`when`(room).enqueueDelta("spool", trackJson, 2_000, 0, 300, "device", 50, true)
            repository.recordListenDeltaNow(song, 2_000, 0, scheduleSync = false,
                eventId = "spool", playedAt = 300, observedClearedAt = 50)
        }

        suspend fun configureBucket(bucket: PlaybackStatBucket) {
            `when`(dao.bucketPage("frozen", null, null, SYNC_PLAYBACK_PAGE_RECORDS))
                .thenReturn(listOf(PlaybackStatsSnapshotBucketEntity("frozen", bucket.toEntity().toSnapshotData())))
            `when`(dao.bucketPage("frozen", bucket.dayStartAt, bucket.identityKey, SYNC_PLAYBACK_PAGE_RECORDS)).thenReturn(emptyList())
            `when`(dao.dailyCounters("frozen", bucket.dayStartAt, listOf(bucket.identityKey))).thenReturn(emptyList())
        }

        fun sessions() = directory.listFiles().orEmpty().filter { it.name.startsWith("dataset-") }
        suspend fun close() = repository.releaseSyncCaptureCache()
    }

    private class PausedReturnDispatcher : CoroutineDispatcher() {
        private val dispatches = AtomicInteger()
        val returned = CompletableDeferred<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (dispatches.incrementAndGet() == 1) Dispatchers.IO.dispatch(context, block)
            else check(returned.complete(block)) { "Unexpected additional caller dispatch" }
        }
    }

    private suspend fun read(source: SyncPlaybackSource) = source.openTracks().use { cursor ->
        buildList { while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; addAll(page) } }
    }

    private enum class CaptureDestination { DIRECT, CACHED, BACKUP }

    private suspend fun capture(fixture: Fixture, destination: CaptureDestination): Long = when (destination) {
        CaptureDestination.DIRECT -> fixture.store.newOrderedSink().use { sink ->
            fixture.repository.captureSyncSnapshot(sink)
            sink.seal().use { read(it).single().totalListenMs }
        }
        CaptureDestination.CACHED -> fixture.borrow().playback.use { read(it).single().totalListenMs }
        CaptureDestination.BACKUP -> {
            val output = StringWriter()
            JsonWriter(output).use { writer ->
                writer.beginObject()
                fixture.repository.writeBackupStatistics(writer)
                writer.endObject()
            }
            Gson().fromJson(output.toString(), JsonObject::class.java).getAsJsonArray("playbackStats")
                .single().asJsonObject.get("totalListenMs").asLong
        }
    }

    private fun assertPropagatedSame(expected: Throwable, actual: Throwable?) {
        var recovered = actual
        val visited = IdentityHashMap<Throwable, Boolean>()
        while (recovered !== expected && recovered != null && visited.put(recovered, true) == null) {
            assertEquals(expected.javaClass, recovered.javaClass)
            assertEquals(expected.message, recovered.message)
            assertTrue("Only coroutine stack recovery copies may wrap the original failure",
                recovered.stackTrace.any { it.className.startsWith("_COROUTINE.") })
            recovered = recovered.cause
        }
        assertSame(expected, recovered)
    }
}
