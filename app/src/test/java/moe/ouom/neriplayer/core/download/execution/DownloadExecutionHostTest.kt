package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Build
import androidx.work.NetworkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.junit.Test

class DownloadExecutionHostTest {
    @Test
    fun `operation ids are restricted to safe file names`() {
        assertEquals("operation-01", normalizeDownloadOperationId(" operation-01 "))
        assertNull(normalizeDownloadOperationId(""))
        assertNull(normalizeDownloadOperationId("../operation"))
        assertNull(normalizeDownloadOperationId("operation/id"))
        assertNull(normalizeDownloadOperationId("operation id"))
    }

    @Test
    fun `operation store round trips request from the injected journal`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore {
            testJournal
        }
        val request = DownloadExecutionRequest(
            operationId = "operation-01",
            song = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 7L,
                durationMs = 1234L,
                coverUrl = "https://example.invalid/cover.jpg",
                sourceStableKey = "netease:42"
            ),
            preserveStaging = true,
            attemptId = 7L
        )

        store.save(context, request)
        val restored = store.read(context, request.operationId)
        assertNotNull(restored)
        val restoredRequest = restored!!
        assertEquals(request.operationId, restoredRequest.operationId)
        assertEquals(request.song.id, restoredRequest.song.id)
        assertEquals(request.song.name, restoredRequest.song.name)
        assertEquals(request.song.sourceStableKey, restoredRequest.song.sourceStableKey)
        assertEquals(request.preserveStaging, restoredRequest.preserveStaging)
        assertEquals(request.attemptId, restoredRequest.attemptId)
    }

    @Test
    fun `operation store finds the durable operation for a song`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-song",
            song = sampleSong()
        )

        store.save(context, request)

        assertEquals(
            request.operationId,
            store.findOperationIdForSong(context, request.song.stableKey())
        )
    }

    @Test
    fun `stopped operation is durable and included in the stopped song index`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-stopped",
            song = sampleSong()
        )
        store.save(context, request)

        store.markStopped(context, request.operationId)

        assertTrue(store.isStopped(context, request.operationId))
        assertTrue(
            request.song.stableKey() in store.stoppedSongKeys(context)
        )
    }

    @Test
    fun `UIDT job id is stable and never uses reserved low range`() {
        val first = UidtDownloadJobService.jobIdFor("operation-01")
        val second = UidtDownloadJobService.jobIdFor("operation-01")
        assertEquals(first, second)
        assertTrue(first >= 100_000)
        assertTrue(first <= UidtDownloadJobService.UIDT_JOB_ID_MAX)
        assertTrue(UidtDownloadJobService.UIDT_JOB_ID_MIN > 99_999)
    }

    @Test
    fun `UIDT collision probes the next free scheduler slot`() {
        val first = UidtDownloadJobService.jobIdFor("op-21583")
        val second = UidtDownloadJobService.jobIdFor("op-33989")
        assertEquals(first, second)
        val selected = UidtDownloadJobService.selectAvailableUidtJobId(
            operationId = "op-33989",
            occupiedJobIds = setOf(first)
        )
        assertNotNull(selected)
        assertTrue(selected != first)
    }

    @Test
    fun `automatic recovery never selects UIDT even on API 34`() {
        assertEquals(
            DownloadExecutionSchedule.Backend.FOREGROUND_WORK,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                userInitiated = false
            )
        )
        assertEquals(
            DownloadExecutionSchedule.Backend.UIDT_JOB,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                userInitiated = true
            )
        )
    }

    @Test
    fun `foreground download work waits for a connected network`() {
        val request = ForegroundDownloadWorker.buildRequest("operation-network")

        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
    }

    @Test
    fun `terminal operation pruning is bounded and honors cutoff`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val old = DownloadExecutionRequest(
            operationId = "operation-old-terminal",
            song = sampleSong().copy(id = 1L)
        )
        val recent = DownloadExecutionRequest(
            operationId = "operation-recent-terminal",
            song = sampleSong().copy(id = 2L)
        )
        store.save(context, old)
        store.save(context, recent)
        testJournal.forceState(old.operationId, "COMPLETED", updatedAtMs = 10L)
        testJournal.forceState(recent.operationId, "CANCELLED", updatedAtMs = 100L)

        assertEquals(1, store.pruneTerminalOperations(context, cutoffMs = 50L, limit = 1))
        assertNull(store.read(context, old.operationId))
        assertNotNull(store.read(context, recent.operationId))
    }

    @Test
    fun `execution forwards the durable operation id to the entry point`() = runTest {
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-forwarded",
            song = sampleSong()
        )
        val store = DownloadExecutionOperationStore { testJournal }
        store.save(context, request)
        var forwardedOperationId: String? = null
        val entryPoint = DownloadOperationEntryPoint { _, operationId, _, _ ->
            forwardedOperationId = operationId
        }
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = entryPoint,
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, request.operationId)
        )
        assertEquals(request.operationId, forwardedOperationId)
    }

    @Test
    fun `execution waits for the shared entry point to finish`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-wait",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _, _, _ ->
                started.complete(Unit)
                release.await()
            },
            sdkInt = 28
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        assertTrue(!execution.isCompleted)
        release.complete(Unit)

        assertEquals(DownloadExecutionResult.Accepted, execution.await())
    }

    @Test
    fun `rejected scheduling keeps the durable operation for retry`() {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-reject",
            song = sampleSong()
        )
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        val result = host.schedule(context, request)

        assertTrue(result is DownloadExecutionSchedule.Rejected)
        assertNotNull(store.read(context, request.operationId))
    }

    private val testJournal = InMemoryDownloadExecutionOperationJournal()

    private fun mockContext(): Context {
        return mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
        }
    }

    private fun sampleSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 1234L,
            coverUrl = null
        )
    }
}
