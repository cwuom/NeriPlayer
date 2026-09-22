package moe.ouom.neriplayer.ui.screen

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadProgressPendingSourcesTest {
    @Test
    fun cancellationReceiptDoesNotCountAsPendingWhileCleanupIsOutstanding() = runBlocking {
        withFixture { context, database ->
            val request = request("cancelled")
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = database)
            assertEquals(setOf(request.song.stableKey()), readDurablePendingDownloadSongKeys(context, database))
            DownloadExecutionRoomStore.requestCancelOperations(context, listOf(request.operationId), database)

            assertEquals(emptySet<String>(), readDurablePendingDownloadSongKeys(context, database))
            assertEquals("CANCEL_REQUESTED", database.downloadOperationDao().findHeader(request.operationId)?.state)
        }
    }

    @Test
    fun cancelledStagingDoesNotCountWhileItsFilesAwaitCleanup() = runBlocking {
        withFixture { context, database ->
            val request = request("cancelled-staging")
            DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED", database = database)
            val working = createStaging(context, request)
            DownloadExecutionRoomStore.requestCancelOperations(context, listOf(request.operationId), database)

            assertEquals(emptySet<String>(), readDurablePendingDownloadSongKeys(context, database))
            assertTrue(working.isFile)
            assertTrue(requireNotNull(database.downloadOperationDao().findHeader(request.operationId)).stopRequestedByUser)
        }
    }

    @Test
    fun replacementOperationRemainsPendingWhenOlderStagingIsCancelled() = runBlocking {
        withFixture { context, database ->
            val cancelled = request("old-request")
            DownloadExecutionRoomStore.upsert(context, cancelled, "CORE_COMMITTED", database = database)
            createStaging(context, cancelled)
            DownloadExecutionRoomStore.requestCancelOperations(context, listOf(cancelled.operationId), database)
            val replacement = cancelled.copy(operationId = "new-request", attemptId = 2L)
            DownloadExecutionRoomStore.upsert(context, replacement, "QUEUED", database = database)

            assertEquals(setOf(cancelled.song.stableKey()), readDurablePendingDownloadSongKeys(context, database))
            assertEquals("QUEUED", database.downloadOperationDao().findHeader(replacement.operationId)?.state)
        }
    }

    @Test
    fun validLegacyStagingRemainsPendingWithoutARoomOperation() = runBlocking {
        withFixture { context, database ->
            val request = request("legacy")
            createStaging(context, request, operationId = null)

            assertEquals(setOf(request.song.stableKey()), readDurablePendingDownloadSongKeys(context, database))
        }
    }

    private fun request(operationId: String) = DownloadExecutionRequest(
        operationId = operationId,
        song = SongItem(
            id = 8_224L, name = "pending sources", artist = "artist",
            album = "Netease", albumId = 1L, durationMs = 1_000L, coverUrl = null
        ),
        attemptId = 1L
    )

    private fun createStaging(
        context: Context,
        request: DownloadExecutionRequest,
        operationId: String? = request.operationId
    ): File {
        val working = ManagedDownloadStorage.createWorkingFile(
            context, request.song.stableKey(), "fixture.mp3", operationId
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(ManagedDownloadStorage.saveWorkingResumeMetadata(working, request.song, operationId))
        assertEquals(1, ManagedDownloadStorage.listPendingResumableDownloads(context).size)
        return working
    }

    private suspend fun withFixture(block: suspend (Context, NeriUserDataDatabase) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "pending-sources-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            block(context, database)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
