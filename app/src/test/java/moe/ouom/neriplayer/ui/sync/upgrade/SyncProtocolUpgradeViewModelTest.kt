package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncProtocolUpgradeViewModelTest {
    private val viewModels = mutableListOf<SyncProtocolUpgradeViewModel>()

    @After
    fun cleanUp() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `manual sync reopens deferred upgrade without executing either provider`() = runTest {
        var calls = 0
        val model = viewModel(flowOf(false)) { }
        runCurrent()
        repeat(2) {
            model.requestSync { calls++ }
            assertTrue(model.uiState.value.dialogRequested)
            assertFalse(model.uiState.value.canConfirm)
            assertEquals(0, calls)
            assertTrue(model.dismissConfirmation())
        }
        model.requestSync { calls++ }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertEquals(0, calls)
        model.requestSync { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `manual sync waits for unloaded approval and does not bypass a pending save`() = runTest {
        val approvals = MutableSharedFlow<Boolean>()
        val saved = CompletableDeferred<Unit>()
        var calls = 0
        val model = viewModel(approvals) { saved.await() }
        runCurrent()
        model.requestSync { calls++ }
        assertNull(model.uiState.value.approved)
        assertTrue(model.uiState.value.dialogRequested)
        assertEquals(0, calls)
        approvals.emit(false)
        runCurrent()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        model.requestSync { calls++ }
        assertTrue(model.uiState.value.isSaving)
        assertEquals(0, calls)
        saved.complete(Unit)
        runCurrent()
        model.requestSync { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `unloaded state and unchecked devices cannot save confirmation`() = runTest {
        val approvals = MutableSharedFlow<Boolean>()
        var saves = 0
        val model = viewModel(approvals) { saves++ }
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertNull(model.uiState.value.approved)
        assertFalse(model.uiState.value.dialogRequested)
        assertEquals(0, saves)

        approvals.emit(false)
        runCurrent()
        model.openConfirmation()
        assertFalse(model.uiState.value.allDevicesUpdated)
        assertFalse(model.uiState.value.canConfirm)
        model.confirm()
        runCurrent()
        assertEquals(0, saves)
        assertEquals(false, model.uiState.value.approved)
    }

    @Test
    fun `confirmation opens sync only after saving and rejects dismissal and duplicate clicks while saving`() = runTest {
        val saved = CompletableDeferred<Unit>()
        var saves = 0
        val model = viewModel(flowOf(false)) { allDevicesUpdated ->
            assertTrue(allDevicesUpdated)
            saves++
            saved.await()
        }
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        assertTrue(model.uiState.value.canConfirm)
        model.confirm()
        model.confirm()
        assertTrue(model.uiState.value.isSaving)
        assertFalse(model.uiState.value.canConfirm)
        assertFalse(model.dismissConfirmation())
        model.setAllDevicesUpdated(false)
        assertTrue(model.uiState.value.allDevicesUpdated)
        runCurrent()
        assertEquals(1, saves)
        assertEquals(false, model.uiState.value.approved)

        saved.complete(Unit)
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.isSaving)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `saving failure keeps dialog and approval closed and permits explicit retry`() = runTest {
        var saves = 0
        val model = viewModel(flowOf(false)) {
            saves++
            if (saves == 1) throw IOException("write failed")
        }
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        assertTrue(model.uiState.value.dialogRequested)
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.canConfirm)
        assertFalse(model.uiState.value.isSaving)

        model.confirm()
        runCurrent()
        assertEquals(2, saves)
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `save cancellation and deferring never approve or report success`() = runTest {
        var saves = 0
        val model = viewModel(flowOf(false)) {
            saves++
            throw CancellationException("cancelled")
        }
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        assertFalse(model.uiState.value.isSaving)
        assertFalse(model.uiState.value.hasError)
        assertTrue(model.uiState.value.dialogRequested)

        assertTrue(model.dismissConfirmation())
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.allDevicesUpdated)
        model.confirm()
        runCurrent()
        assertEquals(1, saves)
        assertEquals(false, model.uiState.value.approved)
    }

    @Test
    fun `state read failure stays unapproved and is shown as a recoverable error`() = runTest {
        var saves = 0
        val model = viewModel(flow { throw IOException("read failed") }) { saves++ }
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        assertTrue(model.uiState.value.hasError)
        model.openConfirmation()
        assertTrue(model.uiState.value.dialogRequested)
        assertTrue(model.uiState.value.hasError)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(1, saves)
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `persisted approval cannot be opened or saved again`() = runTest {
        var saves = 0
        val model = viewModel(flowOf(true)) { saves++ }
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.canConfirm)
        assertEquals(0, saves)
    }

    private fun TestScope.viewModel(
        approvals: Flow<Boolean>,
        save: suspend (Boolean) -> Unit
    ): SyncProtocolUpgradeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SyncProtocolUpgradeViewModel(approvals, save).also(viewModels::add)
    }
}
