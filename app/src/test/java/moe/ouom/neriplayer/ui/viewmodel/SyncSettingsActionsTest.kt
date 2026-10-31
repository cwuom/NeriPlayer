package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.ui.i18n.UiText
import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `GitHub auto sync toggle updates state and cancels or schedules periodic work`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = GitHubSyncViewModel().also { it.ioDispatcher = Dispatchers.Main }
        try {
            mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
                `when`(storage.isPersistent).thenReturn(true)
            }.use {
                withSchedulers { schedulers ->
                    viewModel.toggleAutoSync(context, enabled = false)
                    testScheduler.runCurrent()
                    assertFalse(viewModel.uiState.value.autoSyncEnabled)
                    viewModel.toggleAutoSync(context, enabled = true)
                    testScheduler.runCurrent()
                    assertTrue(viewModel.uiState.value.autoSyncEnabled)

                    assertEquals(listOf("cancel", "schedulePeriodic"), schedulerCalls(schedulers))
                }
            }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `WebDAV auto sync toggle updates state and cancels or schedules periodic work`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = WebDavSyncViewModel().also { it.ioDispatcher = Dispatchers.Main }
        try {
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.isPersistent).thenReturn(true)
            }.use {
                withSchedulers { schedulers ->
                    viewModel.toggleAutoSync(context, enabled = false)
                    testScheduler.runCurrent()
                    assertFalse(viewModel.uiState.value.autoSyncEnabled)
                    viewModel.toggleAutoSync(context, enabled = true)
                    testScheduler.runCurrent()
                    assertTrue(viewModel.uiState.value.autoSyncEnabled)

                    assertEquals(listOf("cancel", "schedulePeriodic"), schedulerCalls(schedulers))
                }
            }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `GitHub repository setup without a saved token reports the missing token`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val creating = GitHubSyncViewModel().also { it.ioDispatcher = Dispatchers.Main }
        val linking = GitHubSyncViewModel().also { it.ioDispatcher = Dispatchers.Main }
        try {
            mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
                `when`(storage.isPersistent).thenReturn(true)
            }.use {
                creating.createRepository(context, "neri-backup")
                linking.useExistingRepository(context, "owner/neri-backup")
                testScheduler.runCurrent()

                assertEquals(UiText.Resource(CoreCommonR.string.github_token_required), creating.uiState.value.errorMessage)
                assertFalse(creating.uiState.value.isCreatingRepo)
                assertFalse(creating.uiState.value.isConfigured)
                assertEquals(UiText.Resource(CoreCommonR.string.github_token_required), linking.uiState.value.errorMessage)
                assertFalse(linking.uiState.value.isCheckingRepo)
                assertEquals("", linking.uiState.value.repoName)
            }
        } finally {
            creating.viewModelScope.cancel()
            linking.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
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
            assertEquals(UiText.Resource(CoreCommonR.string.webdav_required_fields), state.errorMessage)
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
