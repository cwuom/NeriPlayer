package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryApplyGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryApplyOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDownloadDirectoryApplyOwnerTest {
    @Test
    fun `busy lease leaves configured directory untouched`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.operationId = null }

        val failure = runCatching { fixture.owner.apply("content://new", "New", "content://old", true) }
            .exceptionOrNull()

        assertTrue(failure is ManagedLibraryProcessingBusyException)
        assertEquals(listOf("lease"), fixture.gateway.events)
        assertFalse(fixture.preparing.value)
        assertTrue(fixture.permissionLost.value)
    }

    @Test
    fun `published custom directory releases the previous grant before completing its lease`() = runTest {
        val fixture = Fixture(backgroundScope)

        fixture.owner.apply("content://new", "New", "content://old", true)

        assertEquals(
            listOf("lease", "configure:content://new:New", "callback:content://new:New", "refresh",
                "release:content://old", "message:selected", "complete:operation"),
            fixture.gateway.events
        )
        assertFalse(fixture.preparing.value)
        assertFalse(fixture.permissionLost.value)
    }

    @Test
    fun `published reset clears the custom label and retains permission if not requested`() = runTest {
        val fixture = Fixture(backgroundScope)

        fixture.owner.apply(null, "Default", "content://old", false)

        assertEquals(
            listOf("lease", "configure:null:null", "callback:null:null", "refresh",
                "message:reset", "complete:operation"),
            fixture.gateway.events
        )
    }

    @Test
    fun `private to public switch stops checking when the directory scan stalls`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshDelayMs = 60_000L }

        val completed = withTimeoutOrNull(31_000L.milliseconds) {
            fixture.owner.apply("content://new", "New", null, false)
            true
        }

        assertEquals(true, completed)
        assertEquals(
            listOf("lease", "configure:content://new:New", "callback:content://new:New", "refresh",
                "waiting:operation", "message:retry"),
            fixture.gateway.events
        )
        assertFalse(fixture.preparing.value)
        assertFalse(fixture.permissionLost.value)
    }

    @Test
    fun `empty public directory completes a private directory switch without releasing a grant`() = runTest {
        val fixture = Fixture(backgroundScope).apply {
            gateway.outcome = ManagedLibraryRefreshOutcome.Published("tree:new", 0)
        }

        fixture.owner.apply("content://new", "New", null, false)

        assertEquals(
            listOf("lease", "configure:content://new:New", "callback:content://new:New", "refresh",
                "message:selected", "complete:operation"),
            fixture.gateway.events
        )
    }

    @Test
    fun `preserved and failed scans keep the previous grant and publish retry`() = runTest {
        listOf(
            ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION
            ),
            ManagedLibraryRefreshOutcome.Failed("read failed")
        ).forEach { outcome ->
            val fixture = Fixture(backgroundScope).apply { gateway.outcome = outcome }

            fixture.owner.apply("content://new", "New", "content://old", true)

            assertEquals("waiting:operation", fixture.gateway.events[4])
            assertEquals("message:retry", fixture.gateway.events.last())
            assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
        }
    }

    @Test
    fun `refresh failure records retry and rethrows without releasing the old grant`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshError = IllegalStateException("read failed") }

        val failure = runCatching {
            fixture.owner.apply("content://new", "New", "content://old", true)
        }.exceptionOrNull()

        assertEquals("read failed", failure?.message)
        assertEquals("waiting:operation", fixture.gateway.events.last())
        assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
    }

    @Test
    fun `cancellation retains retry state and propagates cancellation`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshError = CancellationException("cancel") }

        val failure = runCatching {
            fixture.owner.apply("content://new", "New", "content://old", true)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("waiting:operation", fixture.gateway.events.last())
        assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
    }

    @Test
    fun `timed out custom switch releases the old grant once and replaces retry with success`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshDelayMs = 60_000L }

        fixture.owner.apply("content://new", "New", "content://old", true)
        assertEquals("message:retry", fixture.gateway.events.last())

        advanceTimeBy(30_001L)
        runCurrent()

        assertEquals(listOf("release:content://old", "message:selected", "complete:operation"),
            fixture.gateway.events.takeLast(3))
        assertEquals(1, fixture.gateway.events.count { it == "release:content://old" })
        assertEquals(1, fixture.gateway.events.count { it == "complete:operation" })
    }

    @Test
    fun `timed out private switch completes without releasing a grant`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshDelayMs = 60_000L }

        fixture.owner.apply("content://new", "New", null, false)
        advanceTimeBy(30_001L)
        runCurrent()

        assertEquals(listOf("message:selected", "complete:operation"), fixture.gateway.events.takeLast(2))
        assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
    }

    @Test
    fun `page cancellation leaves the complete success work with the background task`() = runTest {
        val fixture = Fixture(backgroundScope).apply { gateway.refreshDelayMs = 60_000L }
        val caller = launch { fixture.owner.apply("content://new", "New", "content://old", true) }
        runCurrent()

        caller.cancelAndJoin()
        assertTrue(requireNotNull(fixture.gateway.refreshTask).isActive)
        assertEquals("waiting:operation", fixture.gateway.events.last())
        advanceTimeBy(60_001L)
        runCurrent()

        assertEquals(listOf("release:content://old", "message:selected", "complete:operation"),
            fixture.gateway.events.takeLast(3))
    }

    @Test
    fun `success finishing at the deadline cannot be overwritten by the timeout`() = runTest {
        val fixture = Fixture(backgroundScope).apply {
            gateway.refreshDelayMs = 30_000L
            gateway.completeDelayMs = 1_000L
        }

        fixture.owner.apply("content://new", "New", "content://old", true)
        assertEquals(30_000L, testScheduler.currentTime)
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(listOf("release:content://old", "message:selected", "complete:operation"),
            fixture.gateway.events.takeLast(3))
        assertFalse(fixture.gateway.events.any { it.startsWith("waiting:") || it == "message:retry" })
    }

    @Test
    fun `late failed and preserved scans retain the old grant`() = runTest {
        listOf(
            ManagedLibraryRefreshOutcome.Failed("read failed"),
            ManagedLibraryRefreshOutcome.Preserved(ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION)
        ).forEach { outcome ->
            val fixture = Fixture(backgroundScope).apply {
                gateway.refreshDelayMs = 60_000L
                gateway.outcome = outcome
            }

            fixture.owner.apply("content://new", "New", "content://old", true)
            advanceTimeBy(30_001L)
            runCurrent()

            assertEquals("message:retry", fixture.gateway.events.last())
            assertFalse(fixture.gateway.events.any { it.startsWith("release:") || it.startsWith("complete:") })
        }
    }

    @Test
    fun `completion failure restores the retry message`() = runTest {
        val fixture = Fixture(backgroundScope).apply {
            gateway.completeError = IllegalStateException("persist failed")
        }

        val failure = runCatching { fixture.owner.apply("content://new", "New", "content://old", true) }
            .exceptionOrNull()

        assertEquals("persist failed", failure?.message)
        assertEquals(listOf("message:retry", "waiting:operation"), fixture.gateway.events.takeLast(2))
    }

    private class Fixture(scope: CoroutineScope) {
        val gateway = FakeGateway(scope)
        val preparing = mutableStateOf(true)
        val permissionLost = mutableStateOf(true)
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(CoreCommonR.string.settings_download_directory_selected)).thenReturn("selected")
            `when`(getString(CoreCommonR.string.settings_download_directory_reset_done)).thenReturn("reset")
            `when`(getString(CoreCommonR.string.managed_library_processing_retry)).thenReturn("retry")
        }
        val owner = DownloadDirectoryApplyOwner(
            gateway = gateway,
            resources = resources,
            onDirectoryUriChange = { uri, label -> gateway.events += "callback:$uri:$label" },
            onInlineMessageChange = { message -> gateway.events += "message:$message" },
            isPreparingState = preparing,
            permissionLostState = permissionLost
        )
    }

    private class FakeGateway(parentScope: CoroutineScope) : DownloadDirectoryApplyGateway {
        private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val events = mutableListOf<String>()
        var operationId: String? = "operation"
        var outcome: ManagedLibraryRefreshOutcome = ManagedLibraryRefreshOutcome.Published(null, 1)
        var refreshError: Exception? = null
        var refreshDelayMs = 0L
        var completeDelayMs = 0L
        var completeError: Exception? = null
        var refreshTask: Deferred<Unit>? = null

        override suspend fun tryBeginExclusive(): String? {
            events += "lease"
            return operationId
        }

        override fun currentBusyReason(): ManagedLibraryProcessingReason? =
            ManagedLibraryProcessingReason.DIRECTORY_CHANGE

        override fun configure(uri: String?, label: String?) {
            events += "configure:$uri:$label"
        }

        override fun refresh(
            operationId: String,
            onResult: suspend (ManagedLibraryRefreshOutcome) -> Unit
        ): Deferred<Unit> = scope.async {
            events += "refresh"
            delay(refreshDelayMs.milliseconds)
            refreshError?.let { throw it }
            onResult(outcome)
        }.also { refreshTask = it }

        override suspend fun complete(operationId: String) {
            delay(completeDelayMs.milliseconds)
            events += "complete:$operationId"
            completeError?.let { throw it }
        }

        override suspend fun waitingForRetry(operationId: String) {
            events += "waiting:$operationId"
        }

        override fun releasePreviousPermission(uri: String?) {
            events += "release:$uri"
        }
    }
}
