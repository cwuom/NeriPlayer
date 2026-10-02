package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
class SyncViewModelTargetBindingTest {
    private val viewModels = mutableListOf<ViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)

    @After
    fun cleanUp() {
        gates.forEach { it.complete(Unit) }
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `target completion notifies once after final state for success failure and unfinished upgrade`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                for (result in listOf(
                    Result.success(SyncResult(true, "completed")),
                    Result.failure(IOException("offline")),
                    Result.success(SyncResult(false, "not completed")),
                    Result.failure(IOException("Sync database upgrade did not finish"))
                )) {
                    val complete = gate()
                    var notifications = 0
                    fixture.setTargetOperation {
                        complete.await()
                        result
                    }
                    fixture.performTargetWithFinished(mockContext(), first, {
                        assertFalse(fixture.isSyncing())
                        if (result.getOrNull()?.success == true) assertEquals("completed", fixture.successMessage())
                        else assertNull(fixture.successMessage())
                        notifications++
                    }, { error("ordinary outcome requested an upgrade") })
                    testScheduler.runCurrent()
                    assertTrue(fixture.isSyncing())
                    assertEquals(0, notifications)

                    complete.complete(Unit)
                    testScheduler.runCurrent()

                    assertFalse(fixture.isSyncing())
                    assertEquals(1, notifications)
                }
            }
        }
    }

    @Test
    fun `upgrade challenge is delivered before the target completion notification`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            val events = mutableListOf<String>()
            fixture.setTargetOperation { upgrade(first) }
            fixture.performTargetWithFinished(mockContext(), first, {
                assertFalse(fixture.isSyncing())
                events += "finished"
            }, {
                assertEquals(SyncProtocolUpgradeChallenge(first, "1".repeat(64)), it)
                events += "upgrade"
            })
            testScheduler.runCurrent()

            assertEquals(listOf("upgrade", "finished"), events)
        }
    }

    @Test
    fun `rejected duplicate target request does not notify another request completion`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            val complete = gate()
            var notifications = 0
            var targetCalls = 0
            fixture.setTargetOperation {
                targetCalls++
                complete.await()
                Result.success(SyncResult(false, "not completed"))
            }
            fixture.performTargetWithFinished(mockContext(), first, { notifications++ }, { error("unexpected upgrade") })
            testScheduler.runCurrent()
            fixture.performTargetWithFinished(mockContext(), second, { error("rejected request notified") }, { error("unexpected upgrade") })
            testScheduler.runCurrent()
            assertEquals(0, notifications)
            assertEquals(1, targetCalls)

            complete.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(1, notifications)
        }
    }

    @Test
    fun `cancelling the owned target job notifies completion after clearing progress`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            var notifications = 0
            fixture.setTargetOperation { throw CancellationException("cancelled") }
            fixture.performTargetWithFinished(mockContext(), first, {
                assertFalse(fixture.isSyncing())
                notifications++
            }, { error("cancelled request requested upgrade") })
            testScheduler.runCurrent()

            assertEquals(1, notifications)
            assertNull(fixture.successMessage())
        }
    }

    @Test
    fun `uninitialized and blank target requests never emit a completion notification`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            var notifications = 0
            fixture.performTargetWithFinished(mockContext(), first, { notifications++ }, { error("uninitialized upgrade") })
            fixture.setTargetOperation { error("blank target started") }
            fixture.performTargetWithFinished(mockContext(), "", { notifications++ }, { error("blank target upgrade") })
            testScheduler.runCurrent()

            assertEquals(0, notifications)
            assertFalse(fixture.isSyncing())
        }
    }

    @Test
    fun `queued target request retains its requested identity and operation after configuration changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            val requested = mutableListOf<String>()
            var unboundCalls = 0
            var replacementCalls = 0
            var prompt: SyncProtocolUpgradeChallenge? = null
            fixture.setOperation { unboundCalls++; upgrade(second) }
            fixture.setTargetOperation { target -> requested += target; upgrade(target) }

            fixture.performTarget(mockContext(), first) { prompt = it }
            fixture.setTargetOperation { replacementCalls++; upgrade(second) }
            testScheduler.runCurrent()

            assertEquals(listOf(first), requested)
            assertEquals(0, unboundCalls)
            assertEquals(0, replacementCalls)
            assertEquals(SyncProtocolUpgradeChallenge(first, "1".repeat(64)), prompt)
            assertFalse(fixture.isSyncing())
            assertNull(fixture.errorMessage())
            assertNull(fixture.successMessage())
        }
    }

    @Test
    fun `target and ordinary requests share a single active sync job`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                val complete = gate()
                var targetCalls = 0
                var unboundCalls = 0
                fixture.setOperation { unboundCalls++; upgrade(second) }
                fixture.setTargetOperation { target ->
                    assertEquals(first, target)
                    targetCalls++
                    complete.await()
                    Result.success(SyncResult(true, "completed"))
                }
                val context = mockContext()

                fixture.performTarget(context, first) { error("successful sync requested upgrade") }
                testScheduler.runCurrent()
                fixture.perform(context)
                fixture.performTarget(context, second) { error("duplicate sync requested upgrade") }
                testScheduler.runCurrent()
                assertTrue(fixture.isSyncing())
                assertEquals(1, targetCalls)
                assertEquals(0, unboundCalls)
                complete.complete(Unit)
                testScheduler.runCurrent()
                assertFalse(fixture.isSyncing())
                assertEquals("completed", fixture.successMessage())
            }
        }
    }

    @Test
    fun `clearing configuration cancels target sync and stale completion cannot overwrite a restarted ordinary sync`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                val oldComplete = gate()
                val newComplete = gate()
                var oldJob: Job? = null
                var staleNotifications = 0
                fixture.setTargetOperation { target ->
                    assertEquals(first, target)
                    oldJob = currentCoroutineContext()[Job]
                    withContext(NonCancellable) { oldComplete.await() }
                    Result.success(SyncResult(true, "stale"))
                }
                val context = mockContext()
                fixture.performTargetWithFinished(context, first, { staleNotifications++ }, { error("cancelled target requested upgrade") })
                testScheduler.runCurrent()
                assertTrue(fixture.isSyncing())

                fixture.clear(context)
                assertTrue(requireNotNull(oldJob).isCancelled)
                fixture.setOperation {
                    newComplete.await()
                    Result.success(SyncResult(true, "fresh"))
                }
                fixture.perform(context)
                testScheduler.runCurrent()
                oldComplete.complete(Unit)
                testScheduler.runCurrent()
                assertTrue(fixture.isSyncing())
                assertNull(fixture.successMessage())
                newComplete.complete(Unit)
                testScheduler.runCurrent()
                assertFalse(fixture.isSyncing())
                assertEquals("fresh", fixture.successMessage())
                assertEquals(0, staleNotifications)
            }
        }
    }

    @Test
    fun `clearing a queued target sync prevents any target operation from starting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                var calls = 0
                fixture.setTargetOperation { calls++; Result.success(SyncResult(true, "queued")) }
                val context = mockContext()
                fixture.performTarget(context, first) { error("cancelled target requested upgrade") }
                fixture.clear(context)
                testScheduler.runCurrent()
                assertEquals(0, calls)
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
            }
        }
    }

    @Test
    fun `target request before target initialization never falls back to an unbound sync`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (fixture in fixtures()) {
            var calls = 0
            fixture.setOperation { calls++; upgrade(second) }
            fixture.performTarget(mockContext(), first) { error("uninitialized target requested upgrade") }
            testScheduler.runCurrent()
            assertEquals(0, calls)
            assertFalse(fixture.isSyncing())
        }
    }

    @Test
    fun `an unsuccessful sync payload exposes failure on ordinary and target paths`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (fixture in fixtures()) {
                val incomplete = SyncResult(false, "not completed")
                fixture.setOperation { Result.success(incomplete) }
                fixture.setTargetOperation { Result.success(incomplete) }
                val context = mockContext()

                fixture.perform(context)
                testScheduler.runCurrent()
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
                assertEquals("not completed", fixture.errorMessage())
                fixture.performTarget(context, first) { error("incomplete sync requested upgrade") }
                testScheduler.runCurrent()
                assertFalse(fixture.isSyncing())
                assertNull(fixture.successMessage())
                assertEquals("not completed", fixture.errorMessage())
            }
        }
    }

    private fun gate() = CompletableDeferred<Unit>().also(gates::add)

    private fun upgrade(target: String): Result<SyncResult> = Result.failure(
        SyncProtocolUpgradeRequiredException("upgrade", SyncProtocolUpgradeChallenge(target, "1".repeat(64)))
    )

    private fun mockContext(): Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
    }

    private fun fixtures(): List<Fixture> {
        val github = GitHubSyncViewModel().also(viewModels::add)
        val webdav = WebDavSyncViewModel().also(viewModels::add)
        return listOf(
            Fixture({ github.syncOperation = it }, { github.targetSyncOperation = it },
                { github.performSync(it) }, { context, target, upgrade -> github.performSyncForTarget(context, target, onUpgradeRequired = upgrade) }, github::clearConfiguration,
                { github.uiState.value.isSyncing }, { github.uiState.value.successMessage }, { github.uiState.value.errorMessage },
                { context, target, finished, upgrade -> github.performSyncForTarget(context, target, finished, upgrade) }),
            Fixture({ webdav.syncOperation = it }, { webdav.targetSyncOperation = it },
                { webdav.performSync(it) }, { context, target, upgrade -> webdav.performSyncForTarget(context, target, onUpgradeRequired = upgrade) }, webdav::clearConfiguration,
                { webdav.uiState.value.isSyncing }, { webdav.uiState.value.successMessage }, { webdav.uiState.value.errorMessage },
                { context, target, finished, upgrade -> webdav.performSyncForTarget(context, target, finished, upgrade) })
        )
    }

    private data class Fixture(
        val setOperation: ((suspend () -> Result<SyncResult>)?) -> Unit,
        val setTargetOperation: ((suspend (String) -> Result<SyncResult>)?) -> Unit,
        val perform: (Context) -> Unit,
        val performTarget: (Context, String, (SyncProtocolUpgradeChallenge) -> Unit) -> Unit,
        val clear: (Context) -> Unit,
        val isSyncing: () -> Boolean,
        val successMessage: () -> String?,
        val errorMessage: () -> String?,
        val performTargetWithFinished: (Context, String, () -> Unit, (SyncProtocolUpgradeChallenge) -> Unit) -> Unit
    )
}
