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
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDownloadDirectoryResetOwnerTest {
    @Test
    fun `provider failure type keeps timeout distinct from transport errors`() {
        assertEquals("timeout", downloadDirectoryProviderFailureType(null))
        assertEquals("IllegalStateException", downloadDirectoryProviderFailureType(IllegalStateException()))
    }

    @Test
    fun `blocked reset leaves the current directory untouched`() = runTest {
        val fixture = Fixture(this).apply { actions.blocked = true }

        fixture.owner.onResetRequested()
        advanceUntilIdle()

        assertEquals(listOf("guard"), fixture.events)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `readable source runs migration preflight before resetting`() = runTest {
        val fixture = Fixture(this)

        fixture.owner.onResetRequested()
        assertTrue(fixture.preparing.value)
        advanceUntilIdle()

        assertEquals(listOf("guard", "availability", "prepare:Default"), fixture.events)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `unavailable source resets directly and relinquishes its old grant`() = runTest {
        val fixture = Fixture(this).apply { gateway.result = DownloadDirectoryAvailability.Unavailable }

        fixture.owner.onResetRequested()
        advanceUntilIdle()

        assertEquals(
            listOf("guard", "availability", "apply:Default:content://old"),
            fixture.events
        )
    }

    @Test
    fun `provider failure keeps the current directory and reports retry`() = runTest {
        val fixture = Fixture(this).apply {
            gateway.result = DownloadDirectoryAvailability.ProviderFailure(
                ManagedDownloadRootProviderException("configured-root", IllegalStateException("provider"))
            )
        }

        fixture.owner.onResetRequested()
        advanceUntilIdle()

        assertEquals(
            listOf("guard", "availability", "inline:retry", "show:retry"),
            fixture.events
        )
        assertFalse(fixture.preparing.value)
    }

    @Test
    fun `probe exception is reported while cancellation is propagated`() = runTest {
        val failed = Fixture(this).apply { gateway.error = IllegalStateException("read failed") }
        failed.owner.onResetRequested()
        advanceUntilIdle()
        assertEquals("error:read failed", failed.events.last())
        assertFalse(failed.preparing.value)

        val cancelled = Fixture(this).apply { gateway.error = CancellationException("cancel") }
        cancelled.owner.onResetRequested()
        advanceUntilIdle()
        assertEquals(listOf("guard", "availability"), cancelled.events)
        assertFalse(cancelled.preparing.value)
        assertNull(cancelled.job.value)
    }

    private class Fixture(scope: CoroutineScope) {
        val events = mutableListOf<String>()
        val gateway = FakeGateway(events)
        val actions = FakeActions(events)
        val preparing = mutableStateOf(false)
        val job = mutableStateOf<Job?>(null)
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(R.string.managed_library_processing_retry)).thenReturn("retry")
        }
        val owner = DownloadDirectoryResetOwner(
            gateway = gateway,
            actions = actions,
            scope = scope,
            resources = resources,
            currentUri = "content://old",
            defaultSummary = "Default",
            isPreparingState = preparing,
            preparationJobState = job,
            onInlineMessageChange = { events += "inline:$it" },
            onShowMessage = { events += "show:$it" },
            onError = { events += "error:${it.message}" }
        )
    }

    private class FakeGateway(private val events: MutableList<String>) : DownloadDirectoryResetGateway {
        var result: DownloadDirectoryAvailability = DownloadDirectoryAvailability.Available
        var error: Exception? = null

        override suspend fun availability(currentUri: String?): DownloadDirectoryAvailability {
            events += "availability"
            error?.let { throw it }
            return result
        }
    }

    private class FakeActions(private val events: MutableList<String>) : DownloadDirectoryResetActionPort {
        var blocked = false

        override fun isBlocked(): Boolean {
            events += "guard"
            return blocked
        }

        override suspend fun prepareDefault(targetSummary: String) {
            events += "prepare:$targetSummary"
        }

        override suspend fun applyDefault(targetSummary: String, previousUri: String?) {
            events += "apply:$targetSummary:$previousUri"
        }
    }
}
