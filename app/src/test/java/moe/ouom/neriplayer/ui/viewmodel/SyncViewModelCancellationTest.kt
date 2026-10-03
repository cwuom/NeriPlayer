package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelCancellationTest {
    private val viewModels = mutableListOf<ViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun cleanUp() {
        gates.forEach { it.complete(Unit) }
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `legacy remote requests confirmation without publishing a sync failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))
        for (fixture in fixtures()) {
            var requested: SyncProtocolUpgradeChallenge? = null
            fixture.setOperation { Result.failure(SyncProtocolUpgradeRequiredException("upgrade", challenge)) }
            fixture.performWithPrompt(mockContext()) { requested = it }
            testScheduler.runCurrent()
            assertEquals(challenge, requested)
            assertFalse(fixture.isSyncing())
            assertNull(fixture.errorMessage())
            assertNull(fixture.successMessage())
        }
    }

    @Test
    fun `GitHub legacy remote without a confirmation handler reports a visible failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = startWithoutConfirmationHandler(fixtures().first())
        testScheduler.runCurrent()
        assertEquals("Sync failed: upgrade required", fixture.errorMessage())
        assertFalse(fixture.isSyncing())
        assertNull(fixture.successMessage())
    }

    @Test
    fun `WebDAV legacy remote without a confirmation handler reports a visible failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = startWithoutConfirmationHandler(fixtures().last())
        testScheduler.runCurrent()
        assertEquals("Sync failed: upgrade required", fixture.errorMessage())
        assertFalse(fixture.isSyncing())
        assertNull(fixture.successMessage())
    }

    private fun startWithoutConfirmationHandler(fixture: Fixture): Fixture {
        val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))
        fixture.setOperation { Result.failure(SyncProtocolUpgradeRequiredException("upgrade required", challenge)) }
        val context = mockContext()
        `when`(context.getString(CoreCommonR.string.github_sync_failed, "upgrade required"))
            .thenReturn("Sync failed: upgrade required")
        `when`(context.getString(CoreCommonR.string.webdav_sync_failed, "upgrade required"))
            .thenReturn("Sync failed: upgrade required")
        fixture.perform(context)
        return fixture
    }

    @Test
    fun `clearing configuration cancels direct sync and old completion cannot overwrite a restarted sync`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockContext()
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                val oldGate = gate()
                val newGate = gate()
                var calls = 0
                var oldJob: Job? = null
                fixture.setOperation {
                    calls++
                    oldJob = currentCoroutineContext()[Job]
                    withContext(NonCancellable) { oldGate.await() }
                    Result.success(SyncResult(success = true, message = "stale"))
                }
                fixture.perform(context)
                testScheduler.runCurrent()
                assertTrue(fixture.isSyncing())

                fixture.clear(context)
                assertTrue(requireNotNull(oldJob).isCancelled)
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
                fixture.setOperation {
                    calls++
                    newGate.await()
                    Result.success(SyncResult(success = true, message = "fresh"))
                }
                fixture.perform(context)
                testScheduler.runCurrent()
                oldGate.complete(Unit)
                testScheduler.runCurrent()
                assertTrue(fixture.isSyncing())
                assertNull(fixture.successMessage())

                fixture.perform(context)
                testScheduler.runCurrent()
                assertEquals(2, calls)
                newGate.complete(Unit)
                testScheduler.runCurrent()
                assertFalse(fixture.isSyncing())
                assertEquals("fresh", fixture.successMessage())
                fixture.perform(context)
                testScheduler.runCurrent()
                assertEquals(3, calls)
                assertFalse(fixture.isSyncing())
            }
        }
    }

    @Test
    fun `clearing a queued direct sync prevents the operation from starting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockContext()
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                var calls = 0
                fixture.setOperation {
                    calls++
                    Result.success(SyncResult(success = true, message = "queued"))
                }
                fixture.perform(context)
                fixture.clear(context)
                testScheduler.runCurrent()
                assertEquals(0, calls)
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
            }
        }
    }

    @Test
    fun `request before initialization does not leave the view model syncing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            fixture.perform(mockContext())
            testScheduler.runCurrent()
            assertFalse(fixture.isSyncing())
        }
    }

    private fun gate() = CompletableDeferred<Unit>().also(gates::add)

    private fun mockContext(): Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
    }

    private fun fixtures(): List<Fixture> {
        val github = GitHubSyncViewModel().also(viewModels::add)
        val webdav = WebDavSyncViewModel().also(viewModels::add)
        return listOf(
            Fixture({ github.syncOperation = it }, { github.performSync(it) }, github::performSync, github::clearConfiguration,
                { github.uiState.value.isSyncing }, { github.uiState.value.successMessage }, { github.uiState.value.errorMessage }),
            Fixture({ webdav.syncOperation = it }, { webdav.performSync(it) }, webdav::performSync, webdav::clearConfiguration,
                { webdav.uiState.value.isSyncing }, { webdav.uiState.value.successMessage }, { webdav.uiState.value.errorMessage })
        )
    }

    private data class Fixture(
        val setOperation: (suspend () -> Result<SyncResult>) -> Unit,
        val perform: (Context) -> Unit,
        val performWithPrompt: (Context, (SyncProtocolUpgradeChallenge) -> Unit) -> Unit,
        val clear: (Context) -> Unit,
        val isSyncing: () -> Boolean,
        val successMessage: () -> String?,
        val errorMessage: () -> String?
    )
}
