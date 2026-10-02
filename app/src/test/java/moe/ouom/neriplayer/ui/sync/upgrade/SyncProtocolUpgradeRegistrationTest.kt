package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncProtocolUpgradeRegistrationTest {
    private val models = mutableListOf<SyncProtocolUpgradeViewModel>()

    @After
    fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `configuration stays unavailable until startup registration has durably completed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = CompletableDeferred<Unit>()
        var writes = 0
        val model = model {
            persisted.await()
            writes++
        }
        runCurrent()
        assertFalse(model.uiState.value.startupRegistrationComplete)
        assertEquals(0, writes)

        persisted.complete(Unit)
        runCurrent()

        assertEquals(1, writes)
        assertTrue(model.uiState.value.startupRegistrationComplete)
        assertEquals(true, model.uiState.value.approved)
    }

    @Test
    fun `failed startup registration stays closed and retry completes the same registration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val model = model { if (++attempts == 1) throw IOException("registration failed") }
        runCurrent()
        assertFalse(model.uiState.value.startupRegistrationComplete)
        assertTrue(model.uiState.value.hasError)

        model.refreshTargets()
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(model.uiState.value.startupRegistrationComplete)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `cancelled startup registration does not open configuration before a later successful retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val model = model { if (++attempts == 1) throw CancellationException("registration cancelled") }
        runCurrent()
        assertFalse(model.uiState.value.startupRegistrationComplete)
        assertEquals(null, model.uiState.value.approved)

        model.refreshTargets()
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(model.uiState.value.startupRegistrationComplete)
    }

    @Test
    fun `retry shows registration progress until the original write completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = CompletableDeferred<Unit>()
        var attempts = 0
        val model = model {
            if (++attempts == 1) throw IOException("registration failed")
            persisted.await()
        }
        runCurrent()
        assertTrue(model.uiState.value.hasError)

        model.refreshTargets()
        runCurrent()

        assertEquals(2, attempts)
        assertFalse(model.uiState.value.hasError)
        assertFalse(model.uiState.value.startupRegistrationComplete)
        persisted.complete(Unit)
        runCurrent()
        assertTrue(model.uiState.value.startupRegistrationComplete)
    }

    private fun model(initialize: suspend () -> Unit): SyncProtocolUpgradeViewModel =
        SyncProtocolUpgradeViewModel(
            pendingFlow = flowOf(emptyList()),
            saveConfirmation = { _, _ -> error("registration must not approve remote data") },
            initializeStartupTargets = initialize
        ).also(models::add)
}
