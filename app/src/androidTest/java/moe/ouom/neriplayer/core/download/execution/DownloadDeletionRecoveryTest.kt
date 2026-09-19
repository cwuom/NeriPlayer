package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.manager.admission.promoteWaitingStorageMutationsForRecovery
import moe.ouom.neriplayer.core.download.manager.catalog.beginDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.endDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.recovery.resetInvalidCoreDownloadForTransfer
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadDeletionRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deletionReleasePromotesOnlySettledSongsAndRetainsQueueAndBatchIdentity() = runBlocking {
        withDatabase { db ->
            val songs = listOf(song(701), song(702))
            val store = DownloadRecoveryRoomStore(context, db)
            val waiting = store.upsertWaitingStorageMutationWithRequests(songs, nowMs = 100L)
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, songs, database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, waiting.requestsByOperationId.values.toList(), database = db
            )
            val before = waiting.operationIds.map { db.downloadOperationDao().find(it)!! }
            val deletingKey = songs.first().stableKey()
            GlobalDownloadManager.beginDownloadedSongDeletion(listOf(deletingKey))
            try {
                assertEquals(1, GlobalDownloadManager.promoteWaitingStorageMutationsForRecovery(context, database = db))
                assertEquals(WAITING_STORAGE_MUTATION_OPERATION_STATE,
                    db.downloadOperationDao().find(before.first().operationId)!!.state)
                assertEquals("QUEUED", db.downloadOperationDao().find(before.last().operationId)!!.state)
                assertEquals(0, GlobalDownloadManager.promoteWaitingStorageMutationsForRecovery(context, database = db))
            } finally {
                GlobalDownloadManager.endDownloadedSongDeletion(listOf(deletingKey))
            }
            assertEquals(1, GlobalDownloadManager.promoteWaitingStorageMutationsForRecovery(context, database = db))
            assertTrue(store.listWaitingStorageMutations().isEmpty())
            before.forEach { old ->
                val promoted = db.downloadOperationDao().find(old.operationId)!!
                assertEquals(old.queueOrder, promoted.queueOrder)
                assertEquals(old.createdAtMs, promoted.createdAtMs)
                val member = db.downloadBatchDao().findMember(batch.batchId, old.stableKey)!!
                assertEquals(old.operationId, member.operationId)
                assertEquals(0, member.terminalBits)
            }
            assertEquals(waiting.operationIds, DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                context, null, 10, db
            ).requests.map { it.operationId })
        }
    }

    @Test
    fun oldCancellationCannotRemoveNewWaitingRequestForSameSong() = runBlocking {
        withDatabase { db ->
            val track = song(703)
            val old = DownloadExecutionRequest(operationId = "deleted-old", song = track)
            DownloadExecutionRoomStore.upsert(context, old, "QUEUED", database = db)
            assertTrue(DownloadExecutionRoomStore.requestCancel(context, old.operationId, db))
            val store = DownloadRecoveryRoomStore(context, db)
            val replacement = store.upsertWaitingStorageMutationWithRequests(
                listOf(track), userInitiated = true, excludedOperationIds = setOf(old.operationId),
                forceNewOperationForStableKeys = setOf(track.stableKey())
            ).operationIds.single()
            DownloadExecutionRoomStore.purgeFullyClearedOperations(context, listOf(old.operationId), db)
            assertEquals(1, GlobalDownloadManager.promoteWaitingStorageMutationsForRecovery(context, database = db))
            assertEquals("QUEUED", db.downloadOperationDao().find(replacement)!!.state)
            assertEquals(listOf(replacement), DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                context, null, 10, db
            ).requests.map { it.operationId })
        }
    }

    @Test
    fun confirmedMissingCoreReopensTransferWithoutLosingOrderOrAcceptingStaleOwner() = runBlocking {
        withDatabase { db ->
            val track = song(704)
            val request = DownloadExecutionRequest(
                operationId = "missing-core", song = track, attemptId = 17L, preserveStaging = true
            )
            DownloadExecutionRoomStore.upsert(
                context, request, "ASSETS_ENRICHING", queueOrder = 7, createdAtMs = 10L, database = db
            )
            val dao = db.downloadOperationDao()
            val header = dao.find(request.operationId)!!
            dao.upsert(header.copy(bytesWritten = 1234L, totalBytes = 1234L, resumeJson = "{}"))
            val artifact = ManagedLibraryItemEntity(
                rootKey = header.libraryId, stableKey = track.stableKey(), artifactId = "artifact",
                state = "CORE_COMMITTED", leaseId = "recovery-owner",
                audioReference = "/confirmed-deleted/track.mp3", audioName = "track.mp3", fileSize = 1234L
            )
            db.managedDownloadArtifactDao().upsert(artifact)
            assertNull(resetInvalidCoreDownloadForTransfer(
                context, track, request.operationId, artifact.audioReference, "stale-owner", "MISSING", db
            ))
            assertNotNull(resetInvalidCoreDownloadForTransfer(
                context, track, request.operationId, artifact.audioReference, artifact.leaseId, "MISSING", db
            ))
            val reopened = dao.find(request.operationId)!!
            assertEquals("RETRYABLE", reopened.state)
            assertEquals(header.queueOrder, reopened.queueOrder)
            assertEquals(header.createdAtMs, reopened.createdAtMs)
            assertEquals(0L, reopened.bytesWritten)
            assertNull(reopened.resumeJson)
            assertNull(reopened.totalBytes)
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.preserveStaging)
            val rebound = db.managedDownloadArtifactDao().find(header.libraryId, track.stableKey())!!
            assertEquals("QUEUED", rebound.state)
            assertEquals(request.artifactLeaseId, rebound.leaseId)
            assertNull(rebound.audioReference)
            assertNull(resetInvalidCoreDownloadForTransfer(
                context, track, request.operationId, artifact.audioReference, artifact.leaseId, "MISSING", db
            ))
        }
    }

    private fun song(id: Long) = SongItem(
        id = id, name = "delete-recovery-$id", artist = "artist", album = "netease",
        albumId = 0L, durationMs = 1000L, coverUrl = null
    )

    private suspend fun withDatabase(block: suspend (NeriUserDataDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
