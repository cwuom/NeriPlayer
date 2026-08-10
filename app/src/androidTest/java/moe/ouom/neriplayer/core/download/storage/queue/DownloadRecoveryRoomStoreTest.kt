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
            assertEquals(listOf("first", "second"), queued.map { it.song.name })
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
