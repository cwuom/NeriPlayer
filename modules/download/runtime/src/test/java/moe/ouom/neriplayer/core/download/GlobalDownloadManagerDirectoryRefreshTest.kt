package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.time.Duration.Companion.milliseconds

class GlobalDownloadManagerDirectoryRefreshTest {
    @Test
    fun `timed out directory caller still completes when its background scan publishes`() = runBlocking {
        val fixture = Fixture()
        val operationId = fixture.beginOperation()
        val caller = fixture.caller(this, operationId)
        val scanWaiter = fixture.scanWaiter()

        try {
            caller.cancelAndJoin()
            assertFalse(scanWaiter.isCancelled)
            assertTrue(synchronized(GlobalDownloadManager) {
                scanWaiter in GlobalDownloadManager.refreshWaiters
            })
            ManagedLibraryProcessingCoordinator.waitingForRetry(fixture.context, operationId)

            scanWaiter.complete(ManagedLibraryRefreshOutcome.Published("tree:new", 0))

            withTimeout(2_000L.milliseconds) {
                ManagedLibraryProcessingCoordinator.state.first {
                    it == ManagedLibraryProcessingState.Idle
                }
            }
            assertEquals(ManagedLibraryProcessingState.Idle, ManagedLibraryProcessingCoordinator.state.value)
            ManagedLibraryProcessingCoordinator.waitingForRetry(fixture.context, operationId)
            assertEquals(ManagedLibraryProcessingState.Idle, ManagedLibraryProcessingCoordinator.state.value)
        } finally {
            fixture.close(operationId, scanWaiter)
        }
    }

    @Test
    fun `background completion persistence failure retains retry without an uncaught exception`() = runBlocking {
        val fixture = Fixture()
        val operationId = fixture.beginOperation()
        val managerJob = requireNotNull(GlobalDownloadManager.scope.coroutineContext[Job])
        val existingJobs = managerJob.children.toSet()
        val caller = fixture.caller(this, operationId)
        val scanWaiter = fixture.scanWaiter()
        val watcher = managerJob.children.single { it !in existingJobs }
        val completion = CompletableDeferred<Throwable?>()
        watcher.invokeOnCompletion { completion.complete(it) }

        try {
            caller.cancelAndJoin()
            ManagedLibraryProcessingCoordinator.waitingForRetry(fixture.context, operationId)
            `when`(fixture.editor.commit()).thenReturn(false)

            scanWaiter.complete(ManagedLibraryRefreshOutcome.Published("tree:new", 0))

            assertNull(withTimeout(2_000L.milliseconds) { completion.await() })
            assertTrue(ManagedLibraryProcessingCoordinator.state.value is ManagedLibraryProcessingState.WaitingForRetry)
        } finally {
            fixture.close(operationId, scanWaiter)
        }
    }

    private class Fixture {
        val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
        private val preferences = mock(SharedPreferences::class.java).apply {
            `when`(edit()).thenReturn(editor)
            `when`(editor.commit()).thenReturn(true)
        }
        val context = mock(Context::class.java).apply {
            `when`(applicationContext).thenReturn(this)
            `when`(getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        }
        private val activeScan = Job()
        private val previousJob = GlobalDownloadManager.refreshJob
        private val previousForceRefresh = GlobalDownloadManager.activeRefreshForceRefresh
        private val previousPendingRefresh = GlobalDownloadManager.pendingRefresh
        private val previousPendingForceRefresh = GlobalDownloadManager.pendingForceRefresh

        init {
            GlobalDownloadManager.refreshJob = activeScan
            GlobalDownloadManager.activeRefreshForceRefresh = true
        }

        suspend fun beginOperation(): String = requireNotNull(
            ManagedLibraryProcessingCoordinator.tryBeginExclusive(
                context, ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                ManagedLibraryProcessingPhase.REBUILDING_INDEX
            )
        )

        fun caller(scope: CoroutineScope, operationId: String): Deferred<ManagedLibraryRefreshOutcome> =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                GlobalDownloadManager.scanLocalFilesAwait(context, true, operationId)
            }

        fun scanWaiter(): CompletableDeferred<ManagedLibraryRefreshOutcome> =
            synchronized(GlobalDownloadManager) { GlobalDownloadManager.refreshWaiters.single() }

        suspend fun close(operationId: String, scanWaiter: CompletableDeferred<ManagedLibraryRefreshOutcome>) {
            scanWaiter.complete(ManagedLibraryRefreshOutcome.Failed("test finished"))
            withTimeout(2_000L.milliseconds) {
                while (synchronized(GlobalDownloadManager) {
                    scanWaiter in GlobalDownloadManager.refreshWaiters
                }) delay(5L.milliseconds)
            }
            `when`(editor.commit()).thenReturn(true)
            ManagedLibraryProcessingCoordinator.complete(context, operationId)
            activeScan.cancelAndJoin()
            GlobalDownloadManager.refreshJob = previousJob
            GlobalDownloadManager.activeRefreshForceRefresh = previousForceRefresh
            GlobalDownloadManager.pendingRefresh = previousPendingRefresh
            GlobalDownloadManager.pendingForceRefresh = previousPendingForceRefresh
        }
    }
}
