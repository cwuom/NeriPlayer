package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadDirectoryChangeDecision
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.PendingDownloadDirectoryChange
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationActionPort
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationResult
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.downloadDirectoryChangeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsDownloadDirectoryPreparationOwnerTest {
    @Test
    fun `blocked selection releases the newly granted target without probing`() = runTest {
        val fixture = Fixture().apply { actions.blocked = true }

        val result = fixture.owner.prepare("content://old", "content://new", "New", true)

        assertEquals(DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION, result)
        assertEquals(listOf("guard"), fixture.events)
    }

    @Test
    fun `same directory keeps its grant and publishes the existing selection`() = runTest {
        val fixture = Fixture()

        val result = fixture.owner.prepare("content://same", "content://same", "Same", true)

        assertEquals(DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION, result)
        assertEquals(listOf("guard", "inline:selected"), fixture.events)
    }

    @Test
    fun `equivalent tree applies without dropping the previous permission`() = runTest {
        val fixture = Fixture().apply { gateway.equivalent = true }

        val result = fixture.owner.prepare("content://old", "content://equivalent", "Same", true)

        assertEquals(DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION, result)
        assertEquals(
            listOf("guard", "equivalent", "apply:content://equivalent:false"),
            fixture.events
        )
    }

    @Test
    fun `direct switch releases old permission only when it exists`() = runTest {
        listOf("content://old", null).forEach { previous ->
            val fixture = Fixture()

            fixture.owner.prepare(previous, "content://new", "New", true)

            assertEquals(
                "apply:content://new:${previous != null}",
                fixture.events.last()
            )
        }
    }

    @Test
    fun `reattaching an existing target retains its previous grant`() = runTest {
        val fixture = Fixture().apply {
            gateway.decision = Result.success(ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET)
        }

        fixture.owner.prepare(null, "content://existing", "Existing", true)

        assertEquals("apply:content://existing:false", fixture.events.last())
    }

    @Test
    fun `migration decisions retain both target and conflict warning semantics`() = runTest {
        listOf(
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION to false,
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET to true
        ).forEach { (decision, targetNonEmpty) ->
            val fixture = Fixture().apply { gateway.decision = Result.success(decision) }

            fixture.owner.prepare("content://old", "content://new", "New", true)

            val pending = fixture.actions.pending
            assertEquals("content://old", pending?.previousUri)
            assertEquals("content://new", pending?.targetUri)
            assertEquals("New", pending?.targetSummary)
            assertEquals(targetNonEmpty, pending?.targetNonEmpty)
            assertTrue(pending?.releaseTargetPermissionOnCancel == true)
        }
    }

    @Test
    fun `failed or timed out probes release target grant and explain retry`() = runTest {
        listOf(Result.failure<ManagedDownloadDirectoryChangeDecision>(IllegalStateException("provider")), null)
            .forEach { result ->
                val fixture = Fixture().apply { gateway.decision = result }

                val preparation = fixture.owner.prepare("content://old", "content://new", "New", true)

                assertEquals(DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION, preparation)
                assertEquals(listOf("guard", "equivalent", "probe", "inline:retry", "show:retry"), fixture.events)
                assertNull(fixture.actions.pending)
            }
    }

    @Test
    fun `direction log names default custom and inter custom transitions`() {
        assertEquals("to_default", downloadDirectoryChangeDirection("content://old", null))
        assertEquals("from_default", downloadDirectoryChangeDirection(null, "content://new"))
        assertEquals("between_custom_roots",
            downloadDirectoryChangeDirection("content://old", "content://new")
        )
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val gateway = FakeGateway(events)
        val actions = FakeActions(events)
        private val resources = mock(Resources::class.java).apply {
            `when`(getString(CoreCommonR.string.settings_download_directory_selected)).thenReturn("selected")
            `when`(getString(CoreCommonR.string.settings_download_directory_reset_done)).thenReturn("reset")
            `when`(getString(CoreCommonR.string.managed_library_processing_retry)).thenReturn("retry")
        }
        val owner = DownloadDirectoryPreparationOwner(
            gateway, actions, resources,
            onInlineMessageChange = { events += "inline:$it" },
            onShowMessage = { events += "show:$it" }
        )
    }

    private class FakeGateway(private val events: MutableList<String>) :
        DownloadDirectoryPreparationGateway {
        var equivalent = false
        var decision: Result<ManagedDownloadDirectoryChangeDecision>? =
            Result.success(ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY)

        override fun areEquivalent(firstUri: String?, secondUri: String?): Boolean {
            events += "equivalent"
            return equivalent
        }

        override suspend fun decide(
            previousUri: String?,
            targetUri: String?
        ): Result<ManagedDownloadDirectoryChangeDecision>? {
            events += "probe"
            return decision
        }
    }

    private class FakeActions(private val events: MutableList<String>) :
        DownloadDirectoryPreparationActionPort {
        var blocked = false
        var pending: PendingDownloadDirectoryChange? = null

        override fun isBlocked(): Boolean {
            events += "guard"
            return blocked
        }

        override suspend fun apply(
            targetUri: String?,
            targetSummary: String,
            previousUri: String?,
            shouldReleasePreviousPermission: Boolean
        ) {
            events += "apply:$targetUri:$shouldReleasePreviousPermission"
        }

        override fun showPending(change: PendingDownloadDirectoryChange) {
            pending = change
            events += "pending"
        }
    }
}
