package moe.ouom.neriplayer.data.sync.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

class SyncWorkSchedulerTest {
    private val manager = mock(WorkManager::class.java)
    private val events = mutableListOf<String>()
    private var allowed = true
    private fun scheduler() = SyncWorkScheduler(
        GitHubSyncWorker::class.java, "sync", "periodic",
        manager = { events += "manager"; manager },
        automaticAllowed = { events += "allowed"; allowed },
        markMutation = { events += "mutation" }
    )

    @Test
    fun `local mutation is recorded even when automatic sync is disabled`() {
        allowed = false
        scheduler().scheduleDelayed(userAction = false, mark = true, delayMs = 5_000, append = false)
        assertEquals(listOf("mutation", "allowed"), events)
        assertTrue(mockingDetails(manager).invocations.isEmpty())
    }

    @Test
    fun `manual request bypasses automatic setting and retains worker input`() {
        allowed = false
        scheduler().scheduleDelayed(userAction = true, mark = false, delayMs = -1, append = false)
        val invocation = mockingDetails(manager).invocations.single()
        assertEquals("sync", invocation.getArgument<String>(0))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, invocation.getArgument<ExistingWorkPolicy>(1))
        val request = invocation.getArgument<OneTimeWorkRequest>(2)
        assertEquals(0L, request.workSpec.initialDelay)
        assertEquals(GitHubSyncWorker::class.java.name, request.workSpec.workerClassName)
        assertTrue(request.tags.contains("sync"))
        assertTrue(request.workSpec.input.getBoolean("trigger_by_user_action", false))
        assertEquals(listOf("manager"), events)
    }

    @Test
    fun `automatic request uses keep and respects explicit append`() {
        scheduler().scheduleDelayed(userAction = false, mark = false, delayMs = 5_000, append = false)
        scheduler().scheduleDelayed(userAction = false, mark = true, delayMs = 60_000, append = true)
        val invocations = mockingDetails(manager).invocations.toList()
        assertEquals(ExistingWorkPolicy.KEEP, invocations[0].getArgument<ExistingWorkPolicy>(1))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, invocations[1].getArgument<ExistingWorkPolicy>(1))
        assertEquals(5_000L, invocations[0].getArgument<OneTimeWorkRequest>(2).workSpec.initialDelay)
        assertEquals(60_000L, invocations[1].getArgument<OneTimeWorkRequest>(2).workSpec.initialDelay)
        assertFalse(invocations[0].getArgument<OneTimeWorkRequest>(2).workSpec.input.getBoolean("trigger_by_user_action", true))
    }

    @Test
    fun `periodic immediate and cancel retain persisted scheduling contract`() {
        val scheduler = scheduler()
        scheduler.schedulePeriodic()
        scheduler.syncNow()
        scheduler.cancel()
        val invocations = mockingDetails(manager).invocations.toList()
        assertEquals("periodic", invocations[0].getArgument<String>(0))
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, invocations[0].getArgument<ExistingPeriodicWorkPolicy>(1))
        val periodic = invocations[0].getArgument<PeriodicWorkRequest>(2)
        assertEquals(3_600_000L, periodic.workSpec.intervalDuration)
        assertEquals(900_000L, periodic.workSpec.flexDuration)
        assertTrue(periodic.tags.contains("periodic"))
        val immediate = invocations[1].getArgument<OneTimeWorkRequest>(0)
        assertTrue(immediate.tags.contains("sync_now"))
        assertTrue(immediate.tags.contains("sync"))
        assertTrue(immediate.workSpec.input.getBoolean("force_sync", false))
        assertEquals(listOf("sync", "periodic"), invocations.drop(2).map { it.getArgument<String>(0) })
    }

    @Test
    fun `outcomes adapt to WorkManager results`() {
        assertEquals(androidx.work.ListenableWorker.Result.success(), SyncWorkerOutcome.SUCCESS.toWorkResult())
        assertEquals(androidx.work.ListenableWorker.Result.retry(), SyncWorkerOutcome.RETRY.toWorkResult())
        assertEquals(androidx.work.ListenableWorker.Result.failure(), SyncWorkerOutcome.FAILURE.toWorkResult())
    }
}
