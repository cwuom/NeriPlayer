package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRecoveryRoomStoreTest {
    @Test
    fun queueAndCancellationRoundTripWithoutRewritingLegacyFiles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        queueFile.delete()
        cancelledFile.delete()

        try {
            val first = song(1L, "first")
            val second = song(2L, "second")
            val store = DownloadRecoveryRoomStore(context, database)

            store.upsertPendingDownloadQueue(listOf(first, second), nowMs = 10L)
            store.upsertPendingDownloadQueue(listOf(first.copy(name = "updated")), nowMs = 20L)
            store.markCancelledDownloadKeys(listOf(second.stableKey()), nowMs = 30L)

            val queued = store.listPendingQueuedDownloads()
            assertEquals(listOf("updated", "second"), queued.map { it.song.name })
            assertEquals(10L, queued.first().queuedAtMs)
            assertEquals("updated", queued.first().song.name)
            assertEquals(setOf(second.stableKey()), store.listCancelledDownloadKeys())
            assertTrue(!queueFile.exists())
            assertTrue(!cancelledFile.exists())

            store.removePendingDownloadQueueEntries(listOf(first.stableKey()))
            store.removeCancelledDownloadKeys(listOf(second.stableKey()))
            assertEquals(listOf("second"), store.listPendingQueuedDownloads().map { it.song.name })
            assertTrue(store.listCancelledDownloadKeys().isEmpty())
        } finally {
            queueFile.delete()
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun importsLegacyFilesBeforePromotingRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        val first = song(3L, "legacy")
        queueFile.writeText(
            ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                entries = listOf(
                    ManagedDownloadStorage.PendingDownloadQueueEntry(
                        stableKey = first.stableKey(),
                        song = first,
                        order = 0,
                        queuedAtMs = 40L
                    )
                ),
                updatedAtMs = 40L
            )
        )
        cancelledFile.writeText(
            ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
                songKeys = setOf(first.stableKey()),
                updatedAtMs = 40L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertEquals(listOf("legacy"), store.listPendingQueuedDownloads().map { it.song.name })
            assertEquals(setOf(first.stableKey()), store.listCancelledDownloadKeys())
            assertEquals(
                DownloadRecoveryRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY
                    )
                    ?.value
            )
            assertEquals(
                DownloadRecoveryRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.CANCELLED_KEYS_CUTOVER_STATE_KEY
                    )
                    ?.value
            )
            assertTrue(queueFile.exists())
            assertTrue(cancelledFile.exists())
        } finally {
            queueFile.delete()
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun runtime_reads_do_not_lazily_import_legacy_queue_files() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val legacySong = song(33L, "legacy-runtime")
        queueFile.writeText(
            ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                entries = listOf(
                    ManagedDownloadStorage.PendingDownloadQueueEntry(
                        stableKey = legacySong.stableKey(),
                        song = legacySong,
                        order = 0,
                        queuedAtMs = 40L
                    )
                ),
                updatedAtMs = 40L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            assertTrue(store.listPendingQueuedDownloads().isEmpty())
            assertTrue(queueFile.exists())
            assertEquals(
                null,
                database.syncMetadataDao()
                    .getMigrationMetadata(DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY)
            )
        } finally {
            queueFile.delete()
            database.close()
        }
    }

    @Test
    fun queueRefreshPreservesUserInitiatedOperationIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        queueFile.delete()
        cancelledFile.delete()

        try {
            val song = song(4L, "user")
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = "user-operation-4",
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            val store = DownloadRecoveryRoomStore(context, database)

            store.upsertPendingDownloadQueue(listOf(song.copy(name = "updated")), nowMs = 50L)

            val restored = DownloadExecutionRoomStore.listByState(
                context = context,
                state = "QUEUED",
                database = database
            ).single()
            assertEquals("user-operation-4", store.listPendingQueuedDownloads().single().operationId)
            assertTrue(restored.request.userInitiated)
        } finally {
            queueFile.delete()
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun queueRefresh_rewritesLegacyOperationPayload_inPlace() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(5L, "legacy-operation")
            val operationId = "legacy-operation-5"
            database.downloadOperationDao().upsert(
                DownloadOperationEntity(
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    libraryId = "test-library",
                    state = "QUEUED",
                    queueOrder = 0,
                    sourceHintJson = "{\"channelId\":\"netease\",\"audioId\":\"5\"}",
                    stagingDirName = operationId,
                    bytesWritten = 0L,
                    totalBytes = null,
                    resumeJson = null,
                    retryCount = 0,
                    nextRetryAtMs = null,
                    lastErrorCode = null,
                    createdAtMs = 10L,
                    updatedAtMs = 10L
                )
            )

            val store = DownloadRecoveryRoomStore(context, database)
            store.upsertPendingDownloadQueue(listOf(song.copy(name = "rewritten")), nowMs = 20L)

            val restored = DownloadExecutionRoomStore.read(
                context = context,
                operationId = operationId,
                database = database
            )
            assertEquals("rewritten", restored?.song?.name)
            assertEquals(
                listOf(operationId),
                store.listPendingQueuedDownloads().map { it.operationId }
            )
            assertEquals(1, database.downloadOperationDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRoundTrip_persistsCanonicalRemoteStableKey() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = SongItem(
                id = 2022649173L,
                name = "愛が灯る",
                artist = "ロクデナシ",
                album = "Netease",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null,
                channelId = "netease",
                audioId = "2022649173"
            )
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertPendingDownloadQueue(listOf(song)).single()
            val entity = database.downloadOperationDao().find(operationId)
                ?: error("operation was not persisted")

            assertEquals(
                song.stableKey(),
                JSONObject(entity.sourceHintJson).optString("sourceStableKey")
            )
            assertEquals(
                song.stableKey(),
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.stableKey()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRefresh_doesNotReuseStoppedDeterministicOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(6L, "stopped-operation")
            val deterministicId = java.util.UUID.nameUUIDFromBytes(
                "pending-download:${song.stableKey()}".toByteArray(Charsets.UTF_8)
            ).toString()
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = deterministicId,
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = deterministicId,
                updatedAtMs = 20L
            )

            val operationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "restarted")),
                    userInitiated = true
                )
                .single()

            assertTrue(operationId != deterministicId)
            assertEquals(
                "restarted",
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.name
            )
            assertEquals(
                true,
                database.downloadOperationDao().find(deterministicId)
                    ?.stopRequestedByUser
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRefresh_reusesRetryableOperationAfterHostPause() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(7L, "paused-operation")
            val operationId = "paused-operation-7"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "RETRYABLE",
                errorCode = "HOST_STOPPED",
                database = database
            )

            val refreshedOperationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "paused-updated")),
                    userInitiated = true
                )
                .single()

            assertEquals(operationId, refreshedOperationId)
            assertEquals(
                "paused-updated",
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.name
            )
            assertEquals(1, database.downloadOperationDao().findAll().size)
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(operationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedLegacyFileDoesNotPromoteRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        queueFile.writeText("{malformed")

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertTrue(store.listPendingQueuedDownloads().isEmpty())
            assertEquals(
                null,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY
                    )
            )
            assertTrue(queueFile.exists())
        } finally {
            queueFile.delete()
            database.close()
        }
    }

    @Test
    fun orphaned_legacy_cancel_marker_does_not_create_synthetic_operation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        cancelledFile.writeText(
            ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
                songKeys = setOf("orphan-stable-key"),
                updatedAtMs = 50L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertTrue(store.listCancelledDownloadKeys().isEmpty())
            assertTrue(database.downloadOperationDao().findAll().isEmpty())
        } finally {
            cancelledFile.delete()
            database.close()
        }
    }

    private fun song(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            albumId = 10L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString()
        )
    }
}
