package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.batch.ensureDurableBatchSnapshot
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadBatchAdmissionPerformanceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun eightHundredFiftyFreshSongsRecordDurableStageCost() = runBlocking {
        listOf(false, true).forEach { includeLyrics ->
            withDatabase { db, queries ->
                val songs = (1L..850L).map { id -> song(id, includeLyrics) }
                val presentationId = GlobalDownloadManager.batchDownloadPresentationIdGenerator.incrementAndGet()
                queries.reset()
                val snapshotStartedNs = SystemClock.elapsedRealtimeNanos()
                val batch = try {
                    checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                        context, presentationId, songs, database = db
                    )).identity)
                } finally {
                    GlobalDownloadManager.durableBatchIdentityByPresentationId.remove(presentationId)
                }
                val snapshotNs = SystemClock.elapsedRealtimeNanos() - snapshotStartedNs
                val snapshotMetrics = queries.snapshot()
                assertEquals(0L, snapshotMetrics.getLong("payloadReadQueries"))
                assertEquals(1L, snapshotMetrics.getLong("ownedOperationQueries"))
                val store = DownloadRecoveryRoomStore(context, db)
                val operationIds = mutableListOf<String>()
                var waitingNs = 0L
                var promotionNs = 0L
                var bindingNs = 0L
                var firstPageReadyNs = 0L
                var firstPageProbeQueries = 0L
                var firstPageProbePayloadReadQueries = 0L
                queries.reset()
                val startedNs = SystemClock.elapsedRealtimeNanos()
                songs.chunked(64).forEachIndexed { pageIndex, page ->
                    DownloadExecutionRoomStore.prepareBatchMembersForTransfer(
                        context, batch, page.map(SongItem::stableKey), database = db
                    )
                    val waitingStartedNs = SystemClock.elapsedRealtimeNanos()
                    val waiting = store.upsertWaitingStorageMutationWithRequests(
                        songs = page,
                        nowMs = 1_000L,
                        userInitiated = true,
                        batchIdentity = batch
                    )
                    waitingNs += SystemClock.elapsedRealtimeNanos() - waitingStartedNs
                    val promotionStartedNs = SystemClock.elapsedRealtimeNanos()
                    assertEquals(page.size, store.promoteWaitingStorageMutations(waiting.operationIds))
                    promotionNs += SystemClock.elapsedRealtimeNanos() - promotionStartedNs
                    val bindingStartedNs = SystemClock.elapsedRealtimeNanos()
                    assertEquals(page.size, DownloadExecutionRoomStore.attachBatchIdentity(
                        context, batch, waiting.requestsByOperationId.values, database = db
                    ))
                    bindingNs += SystemClock.elapsedRealtimeNanos() - bindingStartedNs
                    operationIds += waiting.operationIds
                    if (pageIndex == 0) {
                        firstPageReadyNs = SystemClock.elapsedRealtimeNanos() - startedNs
                        val beforeProbe = queries.snapshot()
                        val schedulable = DownloadExecutionRoomReadStore
                            .listSchedulableForPumpPage(
                                context = context,
                                afterCursor = null,
                                limit = 8,
                                database = db,
                                nowMs = 1_000L
                            )
                        val afterProbe = queries.snapshot()
                        firstPageProbeQueries =
                            afterProbe.getLong("queries") - beforeProbe.getLong("queries")
                        firstPageProbePayloadReadQueries =
                            afterProbe.getLong("payloadReadQueries") -
                                beforeProbe.getLong("payloadReadQueries")
                        assertEquals(waiting.operationIds.take(8),
                            schedulable.requests.map { it.operationId })
                        assertTrue(firstPageProbePayloadReadQueries in 1L..16L)
                    }
                }
                val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
                val observedMetrics = queries.snapshot()
                val metrics = JSONObject(observedMetrics.toString())
                    .put("queries", observedMetrics.getLong("queries") - firstPageProbeQueries)
                    .put(
                        "payloadReadQueries",
                        observedMetrics.getLong("payloadReadQueries") -
                            firstPageProbePayloadReadQueries
                    )
                    .put("songs", songs.size)
                    .put("lyrics", includeLyrics)
                    .put("elapsedNs", elapsedNs)
                    .put("waitingNs", waitingNs)
                    .put("promotionNs", promotionNs)
                    .put("bindingNs", bindingNs)
                    .put("firstPageReadyNs", firstPageReadyNs)
                    .put("firstPageProbeQueries", firstPageProbeQueries)
                    .put("firstPageProbePayloadReadQueries", firstPageProbePayloadReadQueries)
                    .put("snapshotNs", snapshotNs)
                    .put("snapshotQueries", snapshotMetrics.getLong("queries"))
                    .put("snapshotPayloadReadQueries", snapshotMetrics.getLong("payloadReadQueries"))
                    .put("snapshotOwnedOperationQueries", snapshotMetrics.getLong("ownedOperationQueries"))
                    .put("snapshotAndStageNs", snapshotNs + elapsedNs)
                    .put("scope", "file-room-waiting-promotion-binding-only")
                Log.i("DownloadAdmissionBenchmark", metrics.toString())
                println("DOWNLOAD_ADMISSION_BENCHMARK=$metrics")
                assertEquals(0L, metrics.getLong("payloadReadQueries"))
                assertEquals(850L, metrics.getLong("payloadWrites"))
                assertTrue(firstPageReadyNs > 0L && firstPageReadyNs < elapsedNs)

                val members = db.downloadBatchDao().listMembers(batch.batchId)
                assertEquals(850, members.size)
                assertEquals(operationIds, members.map { it.operationId })
                assertEquals((0 until 850).toList(), members.map { it.ordinal })
                val headers = DownloadExecutionRoomStore.readOperationHeaders(
                    context, operationIds, database = db
                )
                operationIds.forEachIndexed { index, operationId ->
                    val header = checkNotNull(headers[operationId])
                    assertEquals(index, header.queueOrder)
                    assertEquals(1_000L, header.createdAtMs)
                    assertEquals("QUEUED", header.state)
                    assertEquals(batch.batchId, header.batchId)
                    assertEquals(batch.generation, header.batchGeneration)
                    assertFalse(header.stopRequestedByUser)
                }
                val first = checkNotNull(DownloadExecutionRoomStore.read(context, operationIds.first(), db))
                assertEquals(songs.first().originalLyric, first.song.originalLyric)
            }
        }
    }

    @Test
    fun waitingPageBindsBeforePromotionAndCannotMoveToAnotherBatch() = runBlocking {
        withDatabase { db, _ ->
            val track = song(903)
            val firstBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val store = DownloadRecoveryRoomStore(context, db)
            val first = store.upsertWaitingStorageMutationWithRequests(
                listOf(track), userInitiated = true, batchIdentity = firstBatch)
            val operationId = first.operationIds.single()
            val header = checkNotNull(db.downloadOperationDao().findHeader(operationId))
            assertEquals("WAITING_STORAGE_MUTATION", header.state)
            assertEquals(firstBatch.batchId, header.batchId)
            assertEquals(operationId, db.downloadBatchDao().findMember(firstBatch.batchId, track.stableKey())?.operationId)
            val secondBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val second = store.upsertWaitingStorageMutationWithRequests(
                listOf(track), userInitiated = true, batchIdentity = secondBatch)
            assertEquals(listOf(operationId), second.operationIds)
            assertEquals(0, DownloadExecutionRoomStore.attachBatchIdentity(
                context, secondBatch, second.requestsByOperationId.values, database = db))
            val durable = checkNotNull(DownloadExecutionRoomStore.read(context, operationId, db))
            assertEquals(firstBatch.batchId, durable.batchId)
            assertEquals(header.queueOrder, db.downloadOperationDao().findHeader(operationId)?.queueOrder)
        }
    }

    @Test
    fun cancelledBatchCannotCreateWaitingOperations() = runBlocking {
        withDatabase { db, _ ->
            val track = song(904)
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            assertEquals(1, db.downloadBatchDao().markCancelled(batch.batchId, batch.generation, 2_000L))
            val waiting = DownloadRecoveryRoomStore(context, db).upsertWaitingStorageMutationWithRequests(
                listOf(track), userInitiated = true, batchIdentity = batch,
                forceNewOperationForStableKeys = setOf(track.stableKey()))
            assertTrue(waiting.operationIds.isEmpty())
            assertTrue(db.downloadOperationDao().findAllHeadersByStableKeysAnyLibrary(
                listOf(track.stableKey()), DownloadExecutionRoomStore.ACTIVE_OPERATION_STATES).isEmpty())
        }
    }

    @Test
    fun rejectedMemberBindingLeavesOperationAvailableForAnotherBatch() = runBlocking {
        listOf("missing", "terminal", "other-operation", "attempt").forEach { rejection ->
            withDatabase { db, _ ->
                val track = song(905)
                val batchSongs = if (rejection == "missing") listOf(song(906)) else listOf(track)
                val rejectedBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, batchSongs, database = db)
                when (rejection) {
                    "terminal" -> assertEquals(1, db.downloadBatchDao().markInitialMemberTerminalCAS(
                        rejectedBatch.batchId, track.stableKey(), 1, 1_000, 1_000L))
                    "other-operation" -> {
                        val first = DownloadExecutionRequest("member-first-owner", track)
                        DownloadExecutionRoomStore.upsert(context, first, "QUEUED", database = db)
                        assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                            context, rejectedBatch, listOf(first), database = db))
                    }
                    "attempt" -> db.openHelper.writableDatabase.execSQL(
                        "UPDATE download_batch_member SET attempt_id = ? WHERE batch_id = ? AND stable_key = ?",
                        arrayOf<Any>(19L, rejectedBatch.batchId, track.stableKey()))
                }
                val request = DownloadExecutionRequest("member-rejected-candidate", track, attemptId = 9L)
                DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
                assertEquals(0, DownloadExecutionRoomStore.attachBatchIdentity(
                    context, rejectedBatch, listOf(request), database = db))
                val header = checkNotNull(db.downloadOperationDao().findHeader(request.operationId))
                assertEquals("rejected $rejection binding changed operation header", null, header.batchId)
                assertEquals(null, checkNotNull(DownloadExecutionRoomStore.read(context, request.operationId, db)).batchId)
                val nextBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
                assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                    context, nextBatch, listOf(request), database = db))
            }
        }
    }

    @Test
    fun samePageCannotAttachTwoOperationsToOneMember() = runBlocking {
        withDatabase { db, _ ->
            val track = song(908)
            val requests = listOf("first-same-page", "second-same-page").map { operationId ->
                DownloadExecutionRequest(operationId, track).also {
                    DownloadExecutionRoomStore.upsert(context, it, "QUEUED", database = db)
                }
            }
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(context, batch, requests, database = db))
            assertEquals(requests.first().operationId,
                db.downloadBatchDao().findMember(batch.batchId, track.stableKey())?.operationId)
            assertEquals(null, db.downloadOperationDao().findHeader(requests.last().operationId)?.batchId)
            val nextBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, nextBatch, listOf(requests.last()), database = db))
        }
    }

    @Test
    fun waitingReplayKeepsDurableLyricsAndCustomMetadata() = runBlocking {
        withDatabase { db, _ ->
            val rich = song(907, includeLyrics = true).copy(
                customName = "durable custom title", customArtist = "durable artist",
                customCoverUrl = "https://example.invalid/durable-cover.jpg")
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(rich), database = db)
            val store = DownloadRecoveryRoomStore(context, db)
            val first = store.upsertWaitingStorageMutationWithRequests(
                listOf(rich), userInitiated = true, batchIdentity = batch)
            val beforeReplay = checkNotNull(DownloadExecutionRoomStore.read(
                context, first.operationIds.single(), db)).song
            val replay = store.upsertWaitingStorageMutationWithRequests(
                listOf(song(907)), userInitiated = true, batchIdentity = batch)
            assertEquals(first.operationIds, replay.operationIds)
            val durable = checkNotNull(DownloadExecutionRoomStore.read(context, first.operationIds.single(), db))
            assertEquals(rich.originalLyric, durable.song.originalLyric)
            assertEquals(rich.customName, durable.song.customName)
            assertEquals(rich.customArtist, durable.song.customArtist)
            assertEquals(rich.customCoverUrl, durable.song.customCoverUrl)
            assertEquals(batch.batchId, durable.batchId)
            assertEquals(beforeReplay, durable.song)
            assertEquals(beforeReplay, replay.requestsByOperationId.values.single().song)
        }
    }

    @Test
    fun batchBindingKeepsNewerDurableAttemptAndPayload() = runBlocking {
        withDatabase { db, _ ->
            val original = DownloadExecutionRequest(
                operationId = "batch-binding-stale-attempt",
                song = song(901),
                attemptId = 7L,
                requiresWifiNetwork = true
            )
            val newer = original.copy(
                attemptId = 19L,
                song = original.song.copy(name = "newer durable title"),
                requiresWifiNetwork = false
            )
            DownloadExecutionRoomStore.upsert(context, newer, "QUEUED", database = db)
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(
                context, listOf(original.song), database = db
            )
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, listOf(original), database = db
            ))
            val persisted = checkNotNull(DownloadExecutionRoomStore.read(context, original.operationId, db))
            assertEquals(19L, persisted.attemptId)
            assertEquals("newer durable title", persisted.song.name)
            assertFalse(persisted.requiresWifiNetwork)
            assertEquals(19L, db.downloadBatchDao().findMember(batch.batchId, original.song.stableKey())?.attemptId)
        }
    }

    @Test
    fun sameBatchRebindingDoesNotReadUnchangedSongPayloads() = runBlocking {
        withDatabase { db, queries ->
            val songs = (1L..64L).map { song(it, includeLyrics = true) }
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, songs, database = db)
            val requests = songs.mapIndexed { index, song ->
                DownloadExecutionRequest("already-bound-$index", song, attemptId = 41L)
                    .also { DownloadExecutionRoomStore.upsert(context, it, "QUEUED", database = db) }
            }
            assertEquals(64, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, requests, database = db
            ))
            queries.reset()
            assertEquals(64, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, requests.map { it.copy(attemptId = null) }, database = db
            ))
            val measured = queries.snapshot()
            assertEquals("unchanged batch members must not decode full song payloads: $measured",
                0L, measured.getLong("payloadReadQueries"))
            assertEquals(0L, measured.getLong("payloadWriteChars"))
            assertTrue(db.downloadBatchDao().listMembers(batch.batchId).all { it.attemptId == 41L })
        }
    }

    @Test
    fun oldCancelledBatchCannotCaptureReplacementOrChangeQueueOrder() = runBlocking {
        withDatabase { db, _ ->
            val track = song(902)
            val old = DownloadExecutionRequest("old-cancelled-batch", track, attemptId = 5L)
            DownloadExecutionRoomStore.upsert(context, old, "QUEUED", queueOrder = 9, database = db)
            val oldBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(context, oldBatch, listOf(old), database = db))
            assertTrue(DownloadExecutionRoomStore.requestCancel(context, old.operationId, db))
            val newBatch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val store = DownloadRecoveryRoomStore(context, db)
            val waiting = store.upsertWaitingStorageMutationWithRequests(
                listOf(track), nowMs = 2_000L, userInitiated = true,
                excludedOperationIds = setOf(old.operationId),
                forceNewOperationForStableKeys = setOf(track.stableKey())
            )
            val replacement = waiting.requestsByOperationId.values.single()
            assertFalse(replacement.operationId == old.operationId)
            val order = checkNotNull(db.downloadOperationDao().findHeader(replacement.operationId)).queueOrder
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, newBatch, listOf(replacement), database = db
            ))
            assertEquals(0, DownloadExecutionRoomStore.attachBatchIdentity(
                context, oldBatch, listOf(replacement), database = db
            ))
            assertEquals(1, store.promoteWaitingStorageMutations(waiting.operationIds))
            val after = checkNotNull(db.downloadOperationDao().findHeader(replacement.operationId))
            assertEquals(order, after.queueOrder)
            assertEquals(2_000L, after.createdAtMs)
            assertEquals(newBatch.batchId, after.batchId)
            assertFalse(after.stopRequestedByUser)
            assertEquals(old.operationId, db.downloadBatchDao().findMember(oldBatch.batchId, track.stableKey())?.operationId)
        }
    }

    private fun song(id: Long, includeLyrics: Boolean = false) = SongItem(
        id = id, name = "admission-$id", artist = "artist", album = "netease",
        albumId = 0L, durationMs = 1_000L, coverUrl = null,
        originalLyric = if (includeLyrics) "synthetic lyric line\n".repeat(128) else null
    )

    private suspend fun withDatabase(block: suspend (NeriUserDataDatabase, QueryCounters) -> Unit) {
        val name = "download-admission-${UUID.randomUUID()}"
        val queries = QueryCounters()
        val db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .setQueryCallback(queries, Executor { it.run() })
            .build()
        try {
            db.downloadBatchDao().findMaxGeneration()
            block(db, queries)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private class QueryCounters : RoomDatabase.QueryCallback {
        private val queryCount = AtomicLong()
        private val payloadReadQueries = AtomicLong()
        private val payloadWrites = AtomicLong()
        private val payloadWriteChars = AtomicLong()
        private val ownedOperationQueries = AtomicLong()

        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            queryCount.incrementAndGet()
            if (sqlQuery.contains("SELECT o.operation_id FROM download_operation o", ignoreCase = true)) {
                ownedOperationQueries.incrementAndGet()
            }
            if (sqlQuery.startsWith("SELECT", ignoreCase = true) &&
                sqlQuery.contains("source_hint_json", ignoreCase = true)
            ) {
                payloadReadQueries.incrementAndGet()
            }
            if (!sqlQuery.startsWith("SELECT", ignoreCase = true) &&
                sqlQuery.contains("source_hint_json", ignoreCase = true)
            ) {
                bindArgs.filterIsInstance<String>().filter { it.startsWith("{") && it.contains("\"song\"") }
                    .forEach { payload ->
                        payloadWrites.incrementAndGet()
                        payloadWriteChars.addAndGet(payload.length.toLong())
                    }
            }
        }

        fun reset() {
            queryCount.set(0L)
            payloadReadQueries.set(0L)
            payloadWrites.set(0L)
            payloadWriteChars.set(0L)
            ownedOperationQueries.set(0L)
        }

        fun snapshot(): JSONObject = JSONObject()
            .put("queries", queryCount.get())
            .put("payloadReadQueries", payloadReadQueries.get())
            .put("payloadWrites", payloadWrites.get())
            .put("payloadWriteChars", payloadWriteChars.get())
            .put("ownedOperationQueries", ownedOperationQueries.get())
    }
}
