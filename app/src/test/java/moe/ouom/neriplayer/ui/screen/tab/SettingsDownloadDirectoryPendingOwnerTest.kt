package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDownloadDirectoryPendingOwnerTest {
    @Test
    fun `cancel releases only the granted custom target`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.cancel(fixture.change)
        assertNull(fixture.pending.value)
        assertEquals(listOf("release:content://new"), fixture.events)

        val default = fixture.change.copy(targetUri = null)
        fixture.pending.value = default
        fixture.owner.cancel(default)
        assertEquals(1, fixture.events.size)
        assertFalse(shouldReleaseCancelledTargetGrant(default))
        assertFalse(shouldReleaseCancelledTargetGrant(fixture.change.copy(
            releaseTargetPermissionOnCancel = false
        )))
    }

    @Test
    fun `blocked skip closes pending prompt without applying`() = runTest {
        val fixture = Fixture(this).apply { actions.blocked = true }

        fixture.owner.skip(fixture.change)
        advanceUntilIdle()

        assertEquals(listOf("guard"), fixture.events)
        assertNull(fixture.pending.value)
        assertFalse(fixture.preparing.value)
    }

    @Test
    fun `skip applies selected target without enqueuing migration`() = runTest {
        val fixture = Fixture(this)

        fixture.owner.skip(fixture.change)
        assertTrue(fixture.preparing.value)
        advanceUntilIdle()

        assertEquals(listOf("guard", "apply"), fixture.events)
        assertNull(fixture.pending.value)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `skip error reports failure and cancellation retains cancellation semantics`() = runTest {
        val failed = Fixture(this).apply { actions.applyError = IllegalStateException("scan failed") }
        failed.owner.skip(failed.change)
        advanceUntilIdle()
        assertEquals(listOf("guard", "apply", "error:scan failed"), failed.events)
        assertFalse(failed.preparing.value)

        val cancelled = Fixture(this).apply { actions.applyError = CancellationException("cancel") }
        cancelled.owner.skip(cancelled.change)
        advanceUntilIdle()
        assertEquals(listOf("guard", "apply"), cancelled.events)
        assertFalse(cancelled.preparing.value)
    }

    @Test
    fun `confirm starts migration and records the durable work id`() = runTest {
        val fixture = Fixture(this)

        fixture.owner.confirm(fixture.change)
        advanceUntilIdle()

        assertEquals(listOf("guard", "begin", "enqueue", "record:work"), fixture.events)
        assertNull(fixture.pending.value)
    }

    @Test
    fun `blocked confirm does not enqueue and enqueue failure clears loading`() = runTest {
        val blocked = Fixture(this).apply { actions.blocked = true }
        blocked.owner.confirm(blocked.change)
        advanceUntilIdle()
        assertEquals(listOf("guard"), blocked.events)
        assertNull(blocked.pending.value)

        val failed = Fixture(this).apply { gateway.enqueueError = IllegalStateException("provider failed") }
        failed.owner.confirm(failed.change)
        advanceUntilIdle()
        assertEquals(
            listOf("guard", "begin", "enqueue", "fail", "message:failed:provider failed"),
            failed.events
        )
    }

    private class Fixture(scope: CoroutineScope) {
        val events = mutableListOf<String>()
        val gateway = FakeGateway(events)
        val actions = FakeActions(events)
        val change = PendingDownloadDirectoryChange(
            previousUri = "content://old",
            targetUri = "content://new",
            targetSummary = "New",
            releaseTargetPermissionOnCancel = true
        )
        val pending = mutableStateOf<PendingDownloadDirectoryChange?>(change)
        val preparing = mutableStateOf(false)
        val job = mutableStateOf<Job?>(null)
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(R.string.settings_download_directory_pick_failed, "provider failed"))
                .thenReturn("failed:provider failed")
        }
        val owner = DownloadDirectoryPendingOwner(
            gateway, actions, scope, resources, pending, preparing, job,
            onInlineMessageChange = { events += "message:$it" }
        )
    }

    private class FakeGateway(private val events: MutableList<String>) : DownloadDirectoryPendingGateway {
        var enqueueError: Exception? = null

        override fun releaseTargetGrant(uri: String?) {
            events += "release:$uri"
        }

        override suspend fun enqueueMigration(change: PendingDownloadDirectoryChange): String {
            events += "enqueue"
            enqueueError?.let { throw it }
            return "work"
        }
    }

    private class FakeActions(private val events: MutableList<String>) : DownloadDirectoryPendingActionPort {
        var blocked = false
        var applyError: Exception? = null

        override fun isBlocked(change: PendingDownloadDirectoryChange): Boolean {
            events += "guard"
            return blocked
        }

        override suspend fun applyWithoutMigration(change: PendingDownloadDirectoryChange) {
            events += "apply"
            applyError?.let { throw it }
        }

        override fun beginMigration() {
            events += "begin"
        }

        override fun recordActiveWorkId(workId: String) {
            events += "record:$workId"
        }

        override fun failMigration() {
            events += "fail"
        }

        override fun showPreparationError(error: Exception) {
            events += "error:${error.message}"
        }
    }
}
