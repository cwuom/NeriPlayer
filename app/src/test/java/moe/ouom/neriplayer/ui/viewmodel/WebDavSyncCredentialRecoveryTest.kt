package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.host.createWebDavSyncClient
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSyncCredentialRecoveryTest {
    private val viewModels = mutableListOf<WebDavSyncViewModel>()
    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization storage failure is reported and a later initialization can recover`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).isConfigured()
        val model = viewModel(storage)

        model.initialize(context)
        testScheduler.runCurrent()
        assertNotNull(model.uiState.value.errorMessage)
        doReturn(true).`when`(storage).isConfigured()
        model.initialize(context)
        testScheduler.runCurrent()

        assertTrue(model.uiState.value.isConfigured)
        assertEquals("https://dav.example", model.uiState.value.serverUrl)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `configuration before initialization saves through persistent storage before reporting success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = viewModel()
        var saved: List<String>? = null
        mockConstruction(WebDavStorage::class.java) { storage, _ ->
            `when`(storage.isPersistent).thenReturn(true)
            doAnswer { saved = it.arguments.map { value -> value as String }; null }.`when`(storage)
                .saveConfiguration("https://dav.example", "user", "password", "music")
        }.use {
            successfulValidation {
                model.validateAndSaveConfiguration(context, "https://dav.example", "user", "password", "music")
                testScheduler.runCurrent()
            }
        }

        assertEquals(listOf("https://dav.example", "user", "password", "music"), saved)
        assertTrue(model.uiState.value.isConfigured)
        assertFalse(model.uiState.value.isValidating)
        assertNotNull(model.uiState.value.successMessage)
    }

    @Test
    fun `failed configuration persistence retains the previous UI state and stops validation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val model = viewModel(storage)
        model.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw IOException("local write failed") }.`when`(storage)
            .saveConfiguration("https://dav.example", "user", "password", "replacement")

        successfulValidation {
            model.validateAndSaveConfiguration(context, "https://dav.example", "user", "password", "replacement")
            testScheduler.runCurrent()
        }

        assertFalse(model.uiState.value.isValidating)
        assertEquals("original", model.uiState.value.basePath)
        assertNotNull(model.uiState.value.errorMessage)
        assertNull(model.uiState.value.successMessage)
    }

    @Test
    fun `volatile configuration storage cannot report a successful save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        `when`(storage.isPersistent).thenReturn(false)
        val model = viewModel(storage)

        successfulValidation {
            model.validateAndSaveConfiguration(context, "https://dav.example", "user", "password", "music")
            testScheduler.runCurrent()
        }

        assertFalse(model.uiState.value.isConfigured)
        assertFalse(model.uiState.value.isValidating)
        assertNull(model.uiState.value.successMessage)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `completion observation failure preserves the loaded target and reports secure storage failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        `when`(storage.observeLastCompletedSyncTime()).thenReturn(flow { throw SyncStorageUnavailableException() })
        val model = viewModel(storage)

        model.initialize(context)
        testScheduler.runCurrent()

        assertTrue(model.uiState.value.isConfigured)
        assertEquals("original", model.uiState.value.basePath)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `uninitialized settings mutations require an available persistent store`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = viewModel()
        mockConstruction(WebDavStorage::class.java) { storage, _ ->
            `when`(storage.isPersistent).thenReturn(false)
        }.use {
            mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
                model.toggleAutoSync(context, false)
                testScheduler.runCurrent()

                assertTrue(model.uiState.value.autoSyncEnabled)
                assertNotNull(model.uiState.value.errorMessage)
                model.clearConfiguration(context)
                testScheduler.runCurrent()
                assertNotNull(model.uiState.value.errorMessage)
            }
        }
    }

    @Test
    fun `toggle and clear failures retain the displayed configuration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val model = viewModel(storage)
        model.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).setAutoSyncEnabled(false)
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).clearAll()

        model.toggleAutoSync(context, false)
        testScheduler.runCurrent()
        assertTrue(model.uiState.value.autoSyncEnabled)
        model.clearConfiguration(context)
        testScheduler.runCurrent()

        assertTrue(model.uiState.value.isConfigured)
        assertEquals("original", model.uiState.value.basePath)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `sync acknowledgement read failure stops busy state without reporting success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val model = viewModel(storage)
        model.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).getLastCompletedSyncTime()
        model.syncOperation = { Result.success(SyncResult(true, "synced")) }

        model.performSync(context)
        testScheduler.runCurrent()

        assertFalse(model.uiState.value.isSyncing)
        assertNull(model.uiState.value.successMessage)
        assertEquals(123L, model.uiState.value.lastSyncTime)
        assertNotNull(model.uiState.value.errorMessage)
    }

    private fun successfulValidation(block: () -> Unit) {
        val api = mock(WebDavApiClient::class.java)
        `when`(api.validateConnection("https://dav.example", "music")).thenReturn(Result.success(Unit))
        `when`(api.validateConnection("https://dav.example", "replacement")).thenReturn(Result.success(Unit))
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<WebDavApiClient> { createWebDavSyncClient(context, "user", "password") }.thenReturn(api)
            block()
        }
    }

    private fun viewModel(storage: WebDavStorage? = null): WebDavSyncViewModel = WebDavSyncViewModel().also { model ->
        viewModels += model
        model.ioDispatcher = Dispatchers.Main
        if (storage != null) WebDavSyncViewModel::class.java.getDeclaredField("storage")
            .apply { isAccessible = true }.set(model, storage)
    }

    private fun storage(): WebDavStorage = mock(WebDavStorage::class.java).also {
        `when`(it.isPersistent).thenReturn(true)
        `when`(it.isConfigured()).thenReturn(true)
        `when`(it.getServerUrl()).thenReturn("https://dav.example")
        `when`(it.getBasePath()).thenReturn("original")
        `when`(it.getUsername()).thenReturn("user")
        `when`(it.isAutoSyncEnabled()).thenReturn(true)
        `when`(it.getLastCompletedSyncTime()).thenReturn(123L)
        `when`(it.observeLastCompletedSyncTime()).thenReturn(MutableStateFlow(123L))
    }
}
