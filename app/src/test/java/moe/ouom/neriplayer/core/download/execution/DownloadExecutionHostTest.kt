package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
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
    fun `execution backend keeps recovery and API 36 downloads on foreground work`() {
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
        assertEquals(
            DownloadExecutionSchedule.Backend.FOREGROUND_WORK,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.BAKLAVA,
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
        assertTrue(request.tags.contains("download_execution_all"))
    }

    @Test
    fun `missing operation terminates WorkManager without an infinite retry`() {
        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionResult.MissingOperation.toWorkerResult()::class
        )
    }

    @Test
    fun `UIDT fallback work waits briefly before claiming the operation`() {
        val request = ForegroundDownloadWorker.buildFallbackRequest("operation-fallback")

        assertEquals(3_000L, request.workSpec.initialDelay)
        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
        assertTrue(request.tags.contains("download_execution_all"))
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
        val entryPoint = DownloadOperationEntryPoint { _, restoredRequest ->
            forwardedOperationId = restoredRequest.operationId
            DownloadExecutionResult.Accepted
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
    fun `execution retries before entry point when the durable host window is full`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal().apply {
            hostAdmissionAllowed = false
        }
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-host-window",
            song = sampleSong()
        )
        store.save(context, request)
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(DownloadExecutionResult.Retry, host.execute(context, request.operationId))
        assertEquals(0, executions)
        assertEquals(1, journal.hostAdmissionAcquireCount)
        assertEquals(0, journal.hostAdmissionReleaseCount)

        journal.hostAdmissionAllowed = true

        assertEquals(DownloadExecutionResult.Accepted, host.execute(context, request.operationId))
        assertEquals(1, executions)
        assertEquals(2, journal.hostAdmissionAcquireCount)
        assertEquals(1, journal.hostAdmissionReleaseCount)
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
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                release.await()
                DownloadExecutionResult.Accepted
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
    fun `idle handoff admission is released before a worker retries`() {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-idle-handoff",
            song = sampleSong()
        )
        store.save(context, request)
        assertTrue(store.tryAcquireHostAdmission(context, request.operationId, capacity = 1))
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        host.releaseHandoffAdmissionIfIdle(context, request.operationId)

        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `handoff release cannot remove an admission owned by active execution`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-active-handoff",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                finish.await()
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        host.releaseHandoffAdmissionIfIdle(context, request.operationId)

        assertEquals(0, journal.hostAdmissionReleaseCount)
        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `execution persists the operation scoped entry point result`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-scoped-result",
            song = sampleSong(),
            attemptId = 23L
        )
        store.save(context, request)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, restoredRequest ->
                assertEquals(request.operationId, restoredRequest.operationId)
                assertEquals(request.attemptId, restoredRequest.attemptId)
                DownloadExecutionResult.Retry
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Retry,
            host.execute(context, request.operationId)
        )
        assertEquals("RETRYABLE", store.currentState(context, request.operationId))
    }

    @Test
    fun `execution rereads the latest attempt after claiming the operation`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val queued = DownloadExecutionRequest(
            operationId = "operation-reread-attempt",
            song = sampleSong(),
            attemptId = 7L
        )
        val refreshed = queued.copy(attemptId = 19L)
        store.save(context, queued)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == queued.operationId && state == "RUNNING") {
                testJournal.forceRequest(refreshed)
                testJournal.afterStateUpdate = null
            }
        }
        var receivedAttemptId: Long? = null
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                receivedAttemptId = request.attemptId
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, queued.operationId)
        )
        assertEquals(19L, receivedAttemptId)
    }

    @Test
    fun `cancel accepted after claim prevents entry point execution`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-after-claim",
            song = sampleSong()
        )
        store.save(context, request)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == request.operationId && state == "RUNNING") {
                store.requestCancel(context, operationId)
                testJournal.afterStateUpdate = null
            }
        }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Cancelled,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertEquals("CANCEL_REQUESTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `user stop accepted after claim prevents entry point execution`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-stop-after-claim",
            song = sampleSong()
        )
        store.save(context, request)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == request.operationId && state == "RUNNING") {
                store.markStopped(context, operationId)
                testJournal.afterStateUpdate = null
            }
        }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.UserStopped,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertTrue(store.isStopped(context, request.operationId))
    }

    @Test
    fun `temporarily rejected scheduling stays deferred with its durable operation`() {
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

        assertTrue(result is DownloadExecutionSchedule.Deferred)
        assertNotNull(store.read(context, request.operationId))
        host.cancel(context, request.operationId)
    }

    @Test
    fun `active clear fence rejects scheduling and execution before entry point`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext(activeClearFence = true)
        val request = DownloadExecutionRequest(
            operationId = "operation-clear-fence",
            song = sampleSong()
        )
        store.save(context, request)
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertTrue(host.schedule(context, request) is DownloadExecutionSchedule.Rejected)
        assertEquals(
            DownloadExecutionResult.Cancelled,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertEquals("CANCEL_REQUESTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `scheduling refreshes the durable attempt before the worker starts`() {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val queued = DownloadExecutionRequest(
            operationId = "operation-batch-attempt",
            song = sampleSong(),
            attemptId = null
        )
        store.save(context, queued)
        val scheduled = queued.copy(attemptId = 19L)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        host.schedule(context, scheduled)

        assertEquals(19L, store.read(context, queued.operationId)?.attemptId)
    }

    @Test
    fun `host stop handles only resumable operation states`() {
        listOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE",
            "RETRYABLE",
            "STOPPED"
        ).forEach { state ->
            assertTrue("expected resumable state: $state", shouldHandleHostStop(state))
        }
        listOf("CANCEL_REQUESTED", "CANCELLED", "FINALIZED", "COMPLETED", "INVALID")
            .forEach { state ->
                assertFalse("expected terminal state: $state", shouldHandleHostStop(state))
            }
        assertFalse(shouldHandleHostStop(null))
    }

    @Test
    fun `scheduler can restore an interrupted durable operation without reopening terminal work`() {
        listOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE",
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        ).forEach { state ->
            assertTrue("expected schedulable state: $state", canScheduleDownloadOperation(state))
        }
        listOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED", "COMPLETED", "INVALID")
            .forEach { state ->
                assertFalse("expected rejected state: $state", canScheduleDownloadOperation(state))
            }
    }

    @Test
    fun `worker cancellation cannot reschedule an explicitly stopped operation`() {
        assertTrue(
            shouldBlockHostReschedule(
                preventReschedule = false,
                alreadyStoppedByUser = true
            )
        )
        assertTrue(
            shouldBlockHostReschedule(
                preventReschedule = true,
                alreadyStoppedByUser = false
            )
        )
        assertFalse(
            shouldBlockHostReschedule(
                preventReschedule = false,
                alreadyStoppedByUser = false
            )
        )
    }

    @Test
    fun `fresh host resumes interrupted commit and enrichment operations`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )
        val interruptedStates = listOf(
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        )

        interruptedStates.forEachIndexed { index, state ->
            val request = DownloadExecutionRequest(
                operationId = "operation-recover-$index",
                song = sampleSong().copy(id = index.toLong() + 1L)
            )
            store.save(context, request)
            testJournal.forceState(request.operationId, state, updatedAtMs = 1L)

            assertEquals(
                DownloadExecutionResult.Accepted,
                host.execute(context, request.operationId)
            )
            assertEquals("COMPLETED", store.currentState(context, request.operationId))
        }

        assertEquals(interruptedStates.size, executions)
    }

    private val testJournal = InMemoryDownloadExecutionOperationJournal()

    private fun mockContext(activeClearFence: Boolean = false): Context {
        return mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(activeClearFence)
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
