package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryChangeGuardGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryChangeGuardOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationErrorPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsDownloadDirectoryGuardOwnerTest {
    @Test
    fun `unblocked change neither releases permission nor shows a message`() {
        val fixture = Fixture()

        assertFalse(fixture.owner.isBlocked())
        assertEquals(listOf("active"), fixture.events)
    }

    @Test
    fun `preparation blocks a second pick but allows its own preflight`() {
        val fixture = Fixture().apply { preparing = true }

        assertTrue(fixture.owner.isBlocked())
        assertFalse(fixture.owner.isBlocked(allowWhilePreparing = true))
        assertEquals(listOf("active"), fixture.events)
    }

    @Test
    fun `shared processing takes priority and releases only a distinct new grant`() {
        val fixture = Fixture().apply {
            processing = ManagedLibraryProcessingState.Running(
                operationId = "op",
                reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                phase = moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase.REBUILDING_INDEX
            )
        }

        assertTrue(fixture.owner.isBlocked("content://new", releaseTargetPermissionOnBlock = true))
        assertEquals(
            listOf("release:content://new", "inline:processing", "show:processing"),
            fixture.events
        )
    }

    @Test
    fun `active downloads show their own message without dropping current grant`() {
        val fixture = Fixture().apply { gateway.activeDownloads = true }

        assertTrue(fixture.owner.isBlocked("content://old", releaseTargetPermissionOnBlock = true))
        assertEquals(listOf("active", "inline:downloads", "show:downloads"), fixture.events)
    }

    @Test
    fun `preparation errors distinguish processing lease from provider detail`() {
        val resources = mock(Resources::class.java)
        `when`(resources.getString(CoreCommonR.string.managed_library_processing_subtitle)).thenReturn("processing")
        `when`(resources.getString(CoreCommonR.string.settings_download_directory_pick_failed, "provider"))
            .thenReturn("failed:provider")
        `when`(resources.getString(CoreCommonR.string.settings_download_directory_pick_failed, "IllegalStateException"))
            .thenReturn("failed:type")
        val messages = mutableListOf<String>()
        val presenter = DownloadDirectoryPreparationErrorPresenter(
            resources, { messages += "inline:$it" }, { messages += "show:$it" }
        )

        presenter.show(ManagedLibraryProcessingBusyException(ManagedLibraryProcessingReason.DIRECTORY_CHANGE))
        presenter.show(IllegalStateException("provider"))
        presenter.show(IllegalStateException(""))

        assertEquals(
            listOf("inline:processing", "show:processing", "inline:failed:provider",
                "show:failed:provider", "inline:failed:type", "show:failed:type"),
            messages
        )
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val gateway = FakeGateway(events)
        var preparing = false
        var migrating = false
        var processing: ManagedLibraryProcessingState = ManagedLibraryProcessingState.Idle
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(CoreCommonR.string.managed_library_processing_subtitle)).thenReturn("processing")
            `when`(getString(CoreCommonR.string.settings_download_directory_change_blocked_active_download))
                .thenReturn("downloads")
        }
        val owner = DownloadDirectoryChangeGuardOwner(
            gateway = gateway,
            resources = resources,
            currentUri = "content://old",
            isPreparing = { preparing },
            isMigrating = { migrating },
            libraryProcessing = { processing },
            onInlineMessageChange = { events += "inline:$it" },
            onShowMessage = { events += "show:$it" }
        )
    }

    private class FakeGateway(private val events: MutableList<String>) :
        DownloadDirectoryChangeGuardGateway {
        var activeDownloads = false

        override fun hasActiveDownloads(): Boolean {
            events += "active"
            return activeDownloads
        }

        override fun releaseTargetGrant(uri: String?) {
            events += "release:$uri"
        }
    }
}
