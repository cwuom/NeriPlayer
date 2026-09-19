package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.batch.ensureDurableBatchSnapshot
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadBatchWaitingLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun repeatedWaitingSelectionDoesNotLeaveAnOpenBatchAfterFinalizationAndRestart() = runBlocking {
        verifyRepeatedSelection(promoteBeforeRepeat = false)
    }

    @Test
    fun repeatedQueuedSelectionDoesNotLeaveAnOpenBatchAfterFinalizationAndRestart() = runBlocking {
        verifyRepeatedSelection(promoteBeforeRepeat = true)
    }

    @Test
    fun mixedSelectionPersistsOnlyNewMembersAndBothOwnersCompleteAutomatically() = runBlocking {
        withDatabase { database, nextId ->
            val oldSong = fixtureSong(992L)
            val newSong = fixtureSong(993L)
            val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(oldSong), database = database
            )).identity)
            val store = DownloadRecoveryRoomStore(context, database)
            val oldOperation = store.upsertWaitingStorageMutationWithRequests(
                listOf(oldSong), batchIdentity = first
            ).operationIds.single()
            val second = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(oldSong, newSong), database = database
            ))
            val secondIdentity = checkNotNull(second.identity)
            assertEquals(listOf(newSong), second.songs)
            assertEquals(listOf(oldOperation), second.reusedRequests.map { it.operationId })
            assertEquals(1, database.downloadBatchDao().findBatchById(secondIdentity.batchId)?.totalCount)
            val secondMembers = database.downloadBatchDao().listMembers(secondIdentity.batchId)
            assertEquals(listOf(newSong.stableKey()), secondMembers.map { it.stableKey })
            assertEquals(listOf(0), secondMembers.map { it.ordinal })
            val newOperation = store.upsertWaitingStorageMutationWithRequests(
                second.songs, batchIdentity = secondIdentity
            ).operationIds.single()
            assertEquals(listOf(oldOperation, newOperation),
                database.downloadOperationDao().findAllHeadersByOperationIds(listOf(oldOperation, newOperation))
                    .sortedBy { it.queueOrder }.map { it.operationId })
            listOf(oldOperation, newOperation).forEach { operationId ->
                store.promoteWaitingStorageMutations(listOf(operationId))
                assertTrue(DownloadExecutionRoomStore.updateState(context, operationId, "COMMITTING", database = database))
                assertTrue(DownloadExecutionRoomStore.markCoreCommitted(context, operationId, database = database))
                assertTrue(DownloadExecutionRoomStore.updateState(context, operationId, "FINALIZED", database = database))
            }
            assertTrue(database.downloadBatchDao().findOpenBatches().isEmpty())
        }
    }

    @Test
    fun cancelledStoppedExcludedAndUnreadableOwnersDoNotHideNewSelection() = runBlocking {
        listOf("cancelled", "stopped", "excluded", "unreadable").forEach { mode ->
            withDatabase { database, nextId ->
                val song = fixtureSong(994L)
                val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                    context, nextId(), listOf(song), database = database
                )).identity)
                val operationId = DownloadRecoveryRoomStore(context, database)
                    .upsertWaitingStorageMutationWithRequests(listOf(song), batchIdentity = first)
                    .operationIds.single()
                when (mode) {
                    "cancelled" -> database.downloadBatchDao().markCancelled(first.batchId, first.generation, 5_000L)
                    "stopped" -> database.openHelper.writableDatabase.execSQL(
                        "UPDATE download_operation SET stop_requested_by_user = 1 WHERE operation_id = ?",
                        arrayOf(operationId)
                    )
                    "unreadable" -> database.openHelper.writableDatabase.execSQL(
                        "UPDATE download_operation SET source_hint_json = '{}' WHERE operation_id = ?",
                        arrayOf(operationId)
                    )
                }
                val second = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                    context, nextId(), listOf(song), database = database,
                    excludedOperationIds = if (mode == "excluded") setOf(operationId) else emptySet()
                ))
                assertTrue(mode, second.reusedRequests.isEmpty())
                assertEquals(mode, listOf(song), second.songs)
                assertFalse(mode, first == second.identity)
                assertEquals(DownloadBatchMemberTerminal.NONE,
                    database.downloadBatchDao().findMember(checkNotNull(second.identity).batchId, song.stableKey())?.terminalBits)
            }
        }
    }

    @Test
    fun explicitRetryPromotesWaitingIntentWithoutChangingItsOwnerOrNetworkPolicy() = runBlocking {
        withDatabase { database, nextId ->
            val song = fixtureSong(995L)
            val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            )).identity)
            val operationId = DownloadRecoveryRoomStore(context, database)
                .upsertWaitingStorageMutationWithRequests(listOf(song), userInitiated = false, batchIdentity = first)
                .operationIds.single()
            val before = checkNotNull(DownloadExecutionRoomStore.read(context, operationId, database))
            assertFalse(before.userInitiated)
            val promoted = checkNotNull(DownloadExecutionRoomStore.promoteUserInitiatedOperation(
                context, operationId, song.stableKey(), database
            ))
            assertTrue(promoted.userInitiated)
            assertEquals(before.batchId, promoted.batchId)
            assertEquals(before.batchGeneration, promoted.batchGeneration)
            assertEquals(before.requiresWifiNetwork, promoted.requiresWifiNetwork)
            assertEquals(before.artifactLeaseId, promoted.artifactLeaseId)
            assertEquals("WAITING_STORAGE_MUTATION", database.downloadOperationDao().findHeader(operationId)?.state)
        }
    }

    @Test
    fun explicitlyResumedOwnerIsSelectedBeforeANewBatchIsCreated() = runBlocking {
        withDatabase { database, nextId ->
            val song = fixtureSong(996L)
            val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            )).identity)
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertWaitingStorageMutationWithRequests(
                listOf(song), batchIdentity = first
            ).operationIds.single()
            store.promoteWaitingStorageMutations(listOf(operationId))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE download_operation SET stop_requested_by_user = 1, state = 'STOPPED' WHERE operation_id = ?",
                arrayOf(operationId)
            )
            assertEquals(1, DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(
                context, listOf(song.stableKey()), database
            ))
            val prepared = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            ))
            assertNull(prepared.identity)
            assertEquals(listOf(operationId), prepared.reusedRequests.map { it.operationId })
            assertEquals(first.batchId, prepared.reusedRequests.single().batchId)
            assertEquals("RETRYABLE", database.downloadOperationDao().findHeader(operationId)?.state)
            assertEquals(listOf(first.batchId), database.downloadBatchDao().findOpenBatches().map { it.batchId })
        }
    }

    @Test
    fun oldRootOwnerIsRehomedWithoutCreatingAnUnboundBatch() = runBlocking {
        withDatabase { database, nextId ->
            val song = fixtureSong(997L)
            val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            )).identity)
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertWaitingStorageMutationWithRequests(
                listOf(song), batchIdentity = first
            ).operationIds.single()
            store.promoteWaitingStorageMutations(listOf(operationId))
            // 模拟目录切换后尚未被启动恢复 rehome 的持久行，不修改应用目录设置
            val oldRoot = "file:batch-lifecycle-old-${UUID.randomUUID()}"
            database.openHelper.writableDatabase.execSQL(
                "UPDATE download_operation SET library_id = ? WHERE operation_id = ?",
                arrayOf(oldRoot, operationId)
            )
            val second = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            ))
            assertNull(second.identity)
            assertEquals(listOf(operationId), second.reusedRequests.map { it.operationId })
            assertEquals(ManagedDownloadStorage.currentSnapshotCacheKey(context),
                database.downloadOperationDao().findHeader(operationId)?.libraryId)
            assertEquals(first.batchId, database.downloadOperationDao().findHeader(operationId)?.batchId)
            assertTrue(DownloadExecutionRoomStore.updateState(context, operationId, "COMMITTING", database = database))
            assertTrue(DownloadExecutionRoomStore.markCoreCommitted(context, operationId, database = database))
            assertTrue(DownloadExecutionRoomStore.updateState(context, operationId, "FINALIZED", database = database))
            assertTrue(database.downloadBatchDao().findOpenBatches().isEmpty())
        }
    }

    @Test
    fun forceNewCancellationBoundaryCannotBeHiddenByAnExistingOpenOwner() = runBlocking {
        withDatabase { database, nextId ->
            val song = fixtureSong(998L)
            val first = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextId(), listOf(song), database = database
            )).identity)
            val operationId = DownloadRecoveryRoomStore(context, database)
                .upsertWaitingStorageMutationWithRequests(listOf(song), batchIdentity = first)
                .operationIds.single()
            val added = GlobalDownloadManager.cancellationForceNewSongKeys.add(song.stableKey())
            try {
                val second = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                    context, nextId(), listOf(song), database = database
                ))
                assertTrue(second.reusedRequests.isEmpty())
                assertEquals(listOf(song), second.songs)
                assertFalse(first == second.identity)
                assertEquals(first.batchId, database.downloadOperationDao().findHeader(operationId)?.batchId)
                assertEquals(DownloadBatchMemberTerminal.NONE, database.downloadBatchDao()
                    .findMember(checkNotNull(second.identity).batchId, song.stableKey())?.terminalBits)
            } finally {
                if (added) GlobalDownloadManager.cancellationForceNewSongKeys.remove(song.stableKey())
            }
        }
    }

    private fun fixtureSong(id: Long) = SongItem(
        id = id, name = "batch-lifecycle", artist = "fixture", album = "netease",
        albumId = 0L, durationMs = 1_000L, coverUrl = null
    )

    private suspend fun withDatabase(block: suspend (NeriUserDataDatabase, () -> Long) -> Unit) {
        val name = "batch-lifecycle-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        val ids = mutableListOf<Long>()
        try {
            block(database) {
                GlobalDownloadManager.batchDownloadPresentationIdGenerator.incrementAndGet().also(ids::add)
            }
        } finally {
            ids.forEach(GlobalDownloadManager.durableBatchIdentityByPresentationId::remove)
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun verifyRepeatedSelection(promoteBeforeRepeat: Boolean) {
        val databaseName = "batch-lifecycle-${UUID.randomUUID()}.db"
        fun openDatabase() = Room.databaseBuilder(
            context, NeriUserDataDatabase::class.java, databaseName
        ).build()
        var database = openDatabase()
        val presentationIds = mutableListOf<Long>()
        fun nextPresentationId() = GlobalDownloadManager.batchDownloadPresentationIdGenerator
            .incrementAndGet().also(presentationIds::add)
        try {
            val song = SongItem(id = 991L, name = "batch-lifecycle", artist = "fixture", album = "fixture",
                albumId = 0L, durationMs = 1_000L, coverUrl = null)
            val firstIdentity = checkNotNull(checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextPresentationId(), listOf(song), database = database
            )).identity)
            val waiting = DownloadRecoveryRoomStore(context, database)
                .upsertWaitingStorageMutationWithRequests(
                    listOf(song), userInitiated = true, batchIdentity = firstIdentity
                )
            val operationId = waiting.operationIds.single()
            if (promoteBeforeRepeat) {
                assertEquals(1, DownloadRecoveryRoomStore(context, database)
                    .promoteWaitingStorageMutations(listOf(operationId)))
            }
            database.close()
            database = openDatabase()

            val secondSnapshot = checkNotNull(GlobalDownloadManager.ensureDurableBatchSnapshot(
                context, nextPresentationId(), listOf(song), database = database
            ))
            val secondIdentity = secondSnapshot.identity
            assertEquals(listOf(operationId), secondSnapshot.reusedRequests.map { it.operationId })
            assertTrue(secondSnapshot.songs.isEmpty())
            assertEquals(firstIdentity.batchId,
                database.downloadOperationDao().findHeader(operationId)?.batchId)
            DownloadRecoveryRoomStore(context, database)
                .promoteWaitingStorageMutations(listOf(operationId))
            assertTrue(DownloadExecutionRoomStore.updateState(
                context, operationId, "COMMITTING", database = database
            ))
            assertTrue(DownloadExecutionRoomStore.markCoreCommitted(
                context, operationId, database = database
            ))
            assertTrue(DownloadExecutionRoomStore.updateState(
                context, operationId, "FINALIZED", database = database
            ))
            database.close()
            database = openDatabase()
            assertEquals(DownloadBatchMemberTerminal.COMPLETED,
                database.downloadBatchDao().findMember(firstIdentity.batchId, song.stableKey())?.terminalBits)
            assertTrue("重复选择不能遗留未绑定的新批次: $secondIdentity",
                database.downloadBatchDao().findOpenBatches().isEmpty())
            assertNull(secondIdentity)
        } finally {
            presentationIds.forEach(GlobalDownloadManager.durableBatchIdentityByPresentationId::remove)
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
