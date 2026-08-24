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
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
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
