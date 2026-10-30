package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.host.createWebDavSyncClient
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSyncConfigurationOrderingTest {
    private val models = mutableListOf<WebDavSyncViewModel>()
    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a queued clear cannot remove replacement credentials or reset their displayed state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = fixture()
        withClient {
            fixture.model.clearConfiguration(context)
            testScheduler.runCurrent()
            assertEquals(1, fixture.io.pending)
            fixture.configureReplacement()
            testScheduler.runCurrent()

            repeat(3) {
                fixture.io.runLast()
                testScheduler.runCurrent()
            }
            drain(fixture.io)

            fixture.assertReplacement()
        }
    }

    @Test
    fun `an in flight clear must finish before replacement credentials are saved`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = fixture()
        fixture.beforeClearPublication = {
            fixture.configureReplacement()
            drain(fixture.io)
        }
        withClient {
            fixture.model.clearConfiguration(context)
            testScheduler.runCurrent()
            fixture.io.runFirst()
            drain(fixture.io)

            fixture.assertReplacement()
        }
    }

    private fun fixture(): Fixture {
        val io = ControlledIoDispatcher()
        val storage = mock(WebDavStorage::class.java)
        `when`(storage.isPersistent).thenReturn(true)
        val model = WebDavSyncViewModel().also {
            models += it
            it.ioDispatcher = io
            WebDavSyncViewModel::class.java.getDeclaredField("storage").apply { isAccessible = true }.set(it, storage)
        }
        val fixture = Fixture(io, model)
        doAnswer {
            fixture.stored = it.arguments.map { value -> value as String }
            null
        }.`when`(storage).saveConfiguration("https://dav.example", "replacement-user", "replacement-password", "replacement")
        doAnswer {
            fixture.beforeClearPublication?.invoke()
            fixture.stored = null
            null
        }.`when`(storage).clearAll()
        return fixture
    }

    private fun withClient(block: () -> Unit) {
        val api = mock(WebDavApiClient::class.java)
        `when`(api.validateConnection("https://dav.example", "replacement")).thenReturn(Result.success(Unit))
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<WebDavApiClient> {
                createWebDavSyncClient(context, "replacement-user", "replacement-password")
            }.thenReturn(api)
            mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use { block() }
        }
    }

    private fun TestScope.drain(io: ControlledIoDispatcher) {
        repeat(20) {
            testScheduler.runCurrent()
            if (io.pending == 0) return
            io.runFirst()
        }
        error("WebDAV configuration operations did not settle")
    }

    private inner class Fixture(val io: ControlledIoDispatcher, val model: WebDavSyncViewModel) {
        var stored: List<String>? = listOf("https://old.example", "old-user", "old-password", "old")
        var beforeClearPublication: (() -> Unit)? = null

        fun configureReplacement() = model.validateAndSaveConfiguration(
            context, "https://dav.example", "replacement-user", "replacement-password", "replacement"
        )

        fun assertReplacement() {
            assertEquals(listOf("https://dav.example", "replacement-user", "replacement-password", "replacement"), stored)
            assertTrue(model.uiState.value.isConfigured)
            assertEquals("replacement", model.uiState.value.basePath)
            assertEquals("replacement-user", model.uiState.value.username)
            assertFalse(model.uiState.value.isValidating)
            assertNotNull(model.uiState.value.successMessage)
        }
    }

    private class ControlledIoDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        val pending: Int get() = work.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runFirst() = work.removeFirst().run()
        fun runLast() = work.removeLast().run()
    }
}
