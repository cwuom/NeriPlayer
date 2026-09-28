package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryApplyGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryApplyOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsDownloadDirectoryApplyOwnerTest {
    @Test
    fun `busy lease leaves configured directory untouched`() = runTest {
        val fixture = Fixture().apply { gateway.operationId = null }

        val failure = runCatching { fixture.owner.apply("content://new", "New", "content://old", true) }
            .exceptionOrNull()

        assertTrue(failure is ManagedLibraryProcessingBusyException)
        assertEquals(listOf("lease"), fixture.gateway.events)
        assertFalse(fixture.preparing.value)
        assertTrue(fixture.permissionLost.value)
    }

    @Test
    fun `published custom directory commits before releasing the previous grant`() = runTest {
        val fixture = Fixture()

        fixture.owner.apply("content://new", "New", "content://old", true)

        assertEquals(
            listOf("lease", "configure:content://new:New", "callback:content://new:New", "refresh",
                "complete:operation", "release:content://old", "message:selected"),
            fixture.gateway.events
        )
        assertFalse(fixture.preparing.value)
        assertFalse(fixture.permissionLost.value)
    }

    @Test
    fun `published reset clears the custom label and retains permission if not requested`() = runTest {
        val fixture = Fixture()

        fixture.owner.apply(null, "Default", "content://old", false)

        assertEquals(
            listOf("lease", "configure:null:null", "callback:null:null", "refresh",
                "complete:operation", "message:reset"),
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
            val fixture = Fixture().apply { gateway.outcome = outcome }

            fixture.owner.apply("content://new", "New", "content://old", true)

            assertEquals("waiting:operation", fixture.gateway.events[4])
            assertEquals("message:retry", fixture.gateway.events.last())
            assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
        }
    }

    @Test
    fun `refresh failure records retry and rethrows without releasing the old grant`() = runTest {
        val fixture = Fixture().apply { gateway.refreshError = IllegalStateException("read failed") }

        val failure = runCatching {
            fixture.owner.apply("content://new", "New", "content://old", true)
        }.exceptionOrNull()

        assertEquals("read failed", failure?.message)
        assertEquals("waiting:operation", fixture.gateway.events.last())
        assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
    }

    @Test
    fun `cancellation retains retry state and propagates cancellation`() = runTest {
        val fixture = Fixture().apply { gateway.refreshError = CancellationException("cancel") }

        val failure = runCatching {
            fixture.owner.apply("content://new", "New", "content://old", true)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("waiting:operation", fixture.gateway.events.last())
        assertFalse(fixture.gateway.events.any { it.startsWith("release:") })
    }

    private class Fixture {
        val gateway = FakeGateway()
        val preparing = mutableStateOf(true)
        val permissionLost = mutableStateOf(true)
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(R.string.settings_download_directory_selected)).thenReturn("selected")
            `when`(getString(R.string.settings_download_directory_reset_done)).thenReturn("reset")
            `when`(getString(R.string.managed_library_processing_retry)).thenReturn("retry")
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

    private class FakeGateway : DownloadDirectoryApplyGateway {
        val events = mutableListOf<String>()
        var operationId: String? = "operation"
        var outcome: ManagedLibraryRefreshOutcome = ManagedLibraryRefreshOutcome.Published(null, 1)
        var refreshError: Exception? = null

        override suspend fun tryBeginExclusive(): String? {
            events += "lease"
            return operationId
        }

        override fun currentBusyReason(): ManagedLibraryProcessingReason? =
            ManagedLibraryProcessingReason.DIRECTORY_CHANGE

        override fun configure(uri: String?, label: String?) {
            events += "configure:$uri:$label"
        }

        override suspend fun refresh(): ManagedLibraryRefreshOutcome {
            events += "refresh"
            refreshError?.let { throw it }
            return outcome
        }

        override suspend fun complete(operationId: String) {
            events += "complete:$operationId"
        }

        override suspend fun waitingForRetry(operationId: String) {
            events += "waiting:$operationId"
        }

        override fun releasePreviousPermission(uri: String?) {
            events += "release:$uri"
        }
    }
}
