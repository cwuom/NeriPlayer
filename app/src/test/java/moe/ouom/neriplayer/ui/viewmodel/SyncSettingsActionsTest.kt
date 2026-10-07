package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails

class SyncSettingsActionsTest {

    private val context: Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getString(CoreCommonR.string.github_token_required)).thenReturn("Token required")
        `when`(context.getString(CoreCommonR.string.webdav_required_fields)).thenReturn("Fill all fields")
    }

    @Test
    fun `GitHub auto sync toggle updates state and cancels or schedules periodic work`() {
        withSchedulers { schedulers ->
            val viewModel = GitHubSyncViewModel()

            viewModel.toggleAutoSync(context, enabled = false)
            assertFalse(viewModel.uiState.value.autoSyncEnabled)
            viewModel.toggleAutoSync(context, enabled = true)
            assertTrue(viewModel.uiState.value.autoSyncEnabled)

            assertEquals(listOf("cancel", "schedulePeriodic"), schedulerCalls(schedulers))
        }
    }

    @Test
    fun `WebDAV auto sync toggle updates state and cancels or schedules periodic work`() {
        withSchedulers { schedulers ->
            val viewModel = WebDavSyncViewModel()

            viewModel.toggleAutoSync(context, enabled = false)
            assertFalse(viewModel.uiState.value.autoSyncEnabled)
            viewModel.toggleAutoSync(context, enabled = true)
            assertTrue(viewModel.uiState.value.autoSyncEnabled)

            assertEquals(listOf("cancel", "schedulePeriodic"), schedulerCalls(schedulers))
        }
    }

    @Test
    fun `GitHub repository setup without a saved token reports the missing token`() {
        val creating = GitHubSyncViewModel()
        creating.createRepository(context, "neri-backup")
        assertEquals("Token required", creating.uiState.value.errorMessage)
        assertFalse(creating.uiState.value.isCreatingRepo)
        assertFalse(creating.uiState.value.isConfigured)

        val linking = GitHubSyncViewModel()
        linking.useExistingRepository(context, "owner/neri-backup")
        assertEquals("Token required", linking.uiState.value.errorMessage)
        assertFalse(linking.uiState.value.isCheckingRepo)
        assertEquals("", linking.uiState.value.repoName)
    }

    @Test
    fun `WebDAV configuration with a blank required field is rejected before validation`() {
        val invalidInputs = listOf(
            Triple("   ", "user", "secret"),
            Triple("https://dav.example.invalid", "  ", "secret"),
            Triple("https://dav.example.invalid", "user", "   ")
        )

        for ((serverUrl, username, password) in invalidInputs) {
            val viewModel = WebDavSyncViewModel()
            viewModel.validateAndSaveConfiguration(context, serverUrl, username, password, basePath = "/neri")

            val state = viewModel.uiState.value
            assertEquals("Fill all fields", state.errorMessage)
            assertFalse(state.isValidating)
            assertFalse(state.isConfigured)
        }
    }

    private fun withSchedulers(block: (MockedConstruction<*>) -> Unit) {
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use(block)
    }

    private fun schedulerCalls(schedulers: MockedConstruction<*>): List<String> =
        schedulers.constructed().flatMap { scheduler ->
            mockingDetails(scheduler).invocations.map { it.method.name }
        }
}
