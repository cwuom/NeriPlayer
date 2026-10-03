package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelCompletionTimeTest {
    private val viewModels = mutableListOf<ViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val target = "a".repeat(64)

    @After
    fun cleanUp() {
        gates.forEach { it.complete(Unit) }
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `background completions update the displayed time and repeated initialization keeps one collector`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            fixture.initialize()
            fixture.awaitInitialState()
            assertEquals(200L, fixture.lastSyncTime())
            fixture.events.subscriptionCount.first { it > 0 }

            fixture.initialize()
            fixture.completedTimeReads.first { it >= 2 }
            testScheduler.runCurrent()
            assertEquals(1, fixture.events.subscriptionCount.value)

            fixture.events.value = 300L
            testScheduler.runCurrent()
            assertEquals(300L, fixture.lastSyncTime())
            assertFalse(fixture.isSyncing())
            assertNull(fixture.successMessage())

            fixture.events.value = 0L
            testScheduler.runCurrent()
            assertEquals(0L, fixture.lastSyncTime())
        }
    }

    @Test
    fun `ordinary and target success read the confirmed completion time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            fixture.initialize()
            fixture.awaitInitialState()
            assertEquals(200L, fixture.lastSyncTime())
            fixture.events.subscriptionCount.first { it > 0 }

            for (targetRequest in listOf(false, true)) {
                val completedAt = if (targetRequest) 700L else 600L
                fixture.setOperation {
                    fixture.savedCompletedTime = completedAt
                    Result.success(SyncResult(true, "completed"))
                }
                fixture.perform(targetRequest)
                testScheduler.runCurrent()

                assertEquals(completedAt, fixture.lastSyncTime())
                assertFalse(fixture.isSyncing())
                assertEquals("completed", fixture.successMessage())
            }
        }
    }

    @Test
    fun `failed sync results do not invent a completion time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            fixture.initialize()
            fixture.awaitInitialState()
            assertEquals(200L, fixture.lastSyncTime())
            fixture.events.subscriptionCount.first { it > 0 }

            for (result in listOf(
                Result.success(SyncResult(false, "not completed")),
                Result.failure(IOException("offline"))
            )) {
                fixture.savedCompletedTime = 600L
                fixture.setOperation { result }
                fixture.perform(false)
                testScheduler.runCurrent()

                assertEquals(200L, fixture.lastSyncTime())
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
            }
        }
    }

    @Test
    fun `a cancelled manual sync result cannot overwrite the cleared UI`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                fixture.initialize()
                fixture.awaitInitialState()
                assertEquals(200L, fixture.lastSyncTime())
                fixture.events.subscriptionCount.first { it > 0 }
                val complete = CompletableDeferred<Unit>().also(gates::add)
                fixture.setOperation {
                    withContext(NonCancellable) { complete.await() }
                    fixture.savedCompletedTime = 600L
                    Result.success(SyncResult(true, "stale"))
                }
                fixture.perform(false)
                testScheduler.runCurrent()

                fixture.clear()
                testScheduler.runCurrent()
                assertEquals(0L, fixture.lastSyncTime())
                complete.complete(Unit)
                testScheduler.runCurrent()

                assertEquals(0L, fixture.lastSyncTime())
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
            }
        }
    }

    private fun fixtures(): List<Fixture> {
        val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }
        val github = GitHubSyncViewModel().also(viewModels::add)
        val githubStorage = mock(SecureTokenStorage::class.java)
        val githubFixture = Fixture({ github.initialize(context) },
            { github.uiState.value.lastSyncTime }, { github.uiState.value.isSyncing },
            { github.uiState.value.successMessage },
            { operation -> github.syncOperation = operation; github.targetSyncOperation = { operation() } },
            { requested -> if (requested) github.performSyncForTarget(context, target) else github.performSync(context) },
            { github.clearConfiguration(context) },
            { github.uiState.first { it.lastSyncTime > 0L } })
        injectStorage(github, githubStorage)
        `when`(githubStorage.getLastSyncTime()).thenReturn(40L)
        `when`(githubStorage.getLastCompletedSyncTime()).thenAnswer {
            githubFixture.completedTimeReads.update { it + 1 }
            githubFixture.savedCompletedTime
        }
        `when`(githubStorage.observeLastCompletedSyncTime()).thenReturn(githubFixture.events)
        doAnswer {
            githubFixture.savedCompletedTime = 0L
            githubFixture.events.value = 0L
            null
        }.`when`(githubStorage).clearAll()

        val webDav = WebDavSyncViewModel().also(viewModels::add)
        val webDavStorage = mock(WebDavStorage::class.java)
        val webDavFixture = Fixture({ webDav.initialize(context) },
            { webDav.uiState.value.lastSyncTime }, { webDav.uiState.value.isSyncing },
            { webDav.uiState.value.successMessage },
            { operation -> webDav.syncOperation = operation; webDav.targetSyncOperation = { operation() } },
            { requested -> if (requested) webDav.performSyncForTarget(context, target) else webDav.performSync(context) },
            { webDav.clearConfiguration(context) },
            { webDav.uiState.first { it.lastSyncTime > 0L } })
        injectStorage(webDav, webDavStorage)
        `when`(webDavStorage.getBasePath()).thenReturn("")
        `when`(webDavStorage.getLastSyncTime()).thenReturn(40L)
        `when`(webDavStorage.getLastCompletedSyncTime()).thenAnswer {
            webDavFixture.completedTimeReads.update { it + 1 }
            webDavFixture.savedCompletedTime
        }
        `when`(webDavStorage.observeLastCompletedSyncTime()).thenReturn(webDavFixture.events)
        doAnswer {
            webDavFixture.savedCompletedTime = 0L
            webDavFixture.events.value = 0L
            null
        }.`when`(webDavStorage).clearAll()
        return listOf(githubFixture, webDavFixture)
    }

    private fun injectStorage(viewModel: ViewModel, storage: Any) {
        viewModel.javaClass.getDeclaredField("storage").apply { isAccessible = true }.set(viewModel, storage)
    }

    private class Fixture(
        val initialize: () -> Unit,
        val lastSyncTime: () -> Long,
        val isSyncing: () -> Boolean,
        val successMessage: () -> String?,
        val setOperation: (suspend () -> Result<SyncResult>) -> Unit,
        val perform: (Boolean) -> Unit,
        val clear: () -> Unit,
        val awaitInitialState: suspend () -> Unit
    ) {
        val events = MutableStateFlow(200L)
        val completedTimeReads = MutableStateFlow(0)
        var savedCompletedTime = 200L
    }
}
