package moe.ouom.neriplayer.ui.sync.lyrics

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncLyricOptimizationViewModelTest {
    private val models = mutableListOf<SyncLyricOptimizationViewModel>()

    @After
    fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `the persisted default is no and merely opening the option never writes`() = runTest {
        var writes = 0
        val model = model { writes++ }
        assertNull(model.uiState.value.enabled)
        assertFalse(model.uiState.value.canConfirm)
        runCurrent()

        assertEquals(false, model.uiState.value.enabled)
        model.open()
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.selectedEnabled)
        assertTrue(model.uiState.value.canConfirm)
        assertEquals(0, writes)
    }

    @Test
    fun `only the first provider can claim an open confirmation and cancellation releases ownership`() = runTest {
        var writes = 0
        val model = model { writes++ }
        assertEquals(false, model.open())
        runCurrent()

        assertEquals(true, model.open())
        model.choose(true)
        assertEquals(false, model.open())
        assertTrue(model.uiState.value.selectedEnabled)
        assertTrue(model.dismiss())
        assertEquals(true, model.open())
        assertFalse(model.uiState.value.selectedEnabled)
        assertEquals(0, writes)
    }

    @Test
    fun `two provider tokens share one dialog and a retired provider cannot release the new owner`() = runTest {
        val firstOwner = Any()
        val secondOwner = Any()
        val model = model { }
        runCurrent()

        assertTrue(model.open(firstOwner))
        assertSame(firstOwner, model.uiState.value.dialogOwner)
        assertFalse(model.open(secondOwner))
        model.release(secondOwner)
        assertSame(firstOwner, model.uiState.value.dialogOwner)
        model.choose(true)
        assertTrue(model.dismiss())
        assertNull(model.uiState.value.dialogOwner)
        assertTrue(model.open(secondOwner))
        model.release(firstOwner)
        assertSame(secondOwner, model.uiState.value.dialogOwner)
        assertFalse(model.uiState.value.selectedEnabled)
        model.release(secondOwner)
        assertFalse(model.uiState.value.dialogRequested)
        assertNull(model.uiState.value.dialogOwner)
    }

    @Test
    fun `releasing a provider during a failed save preserves the draft for the next visible provider`() = runTest {
        val saved = CompletableDeferred<Unit>()
        var writes = 0
        val model = model {
            if (++writes == 1) {
                saved.await()
                throw IOException("failed durable save")
            }
        }
        val oldOwner = Any()
        val newOwner = Any()
        runCurrent()
        assertTrue(model.open(oldOwner))
        model.choose(true)
        model.confirm()
        runCurrent()
        model.release(oldOwner)
        assertNull(model.uiState.value.dialogOwner)
        assertTrue(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.selectedEnabled)
        assertFalse(model.open(newOwner))
        assertFalse(model.dismiss())
        assertEquals(false, model.uiState.value.enabled)

        saved.complete(Unit)
        runCurrent()
        assertTrue(model.uiState.value.dialogRequested)
        assertTrue(model.uiState.value.hasError)
        assertEquals(false, model.uiState.value.enabled)
        assertFalse(model.uiState.value.canConfirm)
        assertTrue(model.open(newOwner))
        assertSame(newOwner, model.uiState.value.dialogOwner)
        assertTrue(model.uiState.value.selectedEnabled)
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.canConfirm)
        model.confirm()
        runCurrent()
        assertEquals(2, writes)
        assertEquals(true, model.uiState.value.enabled)
        assertFalse(model.uiState.value.dialogRequested)
        assertNull(model.uiState.value.dialogOwner)
    }

    @Test
    fun `selection is a draft and cancel discards it without changing the persisted option`() = runTest {
        var writes = 0
        val model = model { writes++ }
        runCurrent()
        model.choose(true)
        model.confirm()
        assertEquals(0, writes)

        model.open()
        model.choose(true)
        assertEquals(false, model.uiState.value.enabled)
        assertTrue(model.uiState.value.selectedEnabled)
        assertTrue(model.dismiss())
        model.confirm()
        runCurrent()
        assertEquals(0, writes)
        assertEquals(false, model.uiState.value.enabled)
        model.open()
        assertFalse(model.uiState.value.selectedEnabled)
    }

    @Test
    fun `an explicit yes updates the setting only after a completed durable save`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val model = model { enabled -> writes += enabled; saved.await() }
        runCurrent()
        model.open()
        model.choose(true)
        model.confirm()
        model.confirm()
        model.choose(false)
        assertFalse(model.dismiss())
        model.refresh()
        runCurrent()

        assertEquals(listOf(true), writes)
        assertTrue(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.selectedEnabled)
        assertEquals(false, model.uiState.value.enabled)
        assertFalse(model.uiState.value.canConfirm)
        saved.complete(Unit)
        runCurrent()
        assertEquals(true, model.uiState.value.enabled)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.isSaving)
    }

    @Test
    fun `a failed save keeps the original setting and the confirmation open for retry`() = runTest {
        var writes = 0
        val model = model {
            if (++writes == 1) throw IOException("durable preference write failed")
        }
        runCurrent()
        model.open()
        model.choose(true)
        model.confirm()
        runCurrent()

        assertEquals(false, model.uiState.value.enabled)
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.canConfirm)
        model.confirm()
        runCurrent()
        assertEquals(2, writes)
        assertEquals(true, model.uiState.value.enabled)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `save cancellation never publishes the draft as a completed setting`() = runTest {
        val model = model { throw CancellationException("cancelled save") }
        runCurrent()
        model.open()
        model.choose(true)
        model.confirm()
        runCurrent()

        assertEquals(false, model.uiState.value.enabled)
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.isSaving)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `choosing no after a stored yes disables optimization only on confirmation`() = runTest {
        val writes = mutableListOf<Boolean>()
        val model = model(load = { true }) { writes += it }
        runCurrent()
        model.open()
        assertTrue(model.uiState.value.selectedEnabled)
        model.choose(false)
        assertEquals(true, model.uiState.value.enabled)
        model.confirm()
        runCurrent()

        assertEquals(listOf(false), writes)
        assertEquals(false, model.uiState.value.enabled)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `reopening settings reloads an externally changed preference and read errors can retry`() = runTest {
        var stored = false
        var reads = 0
        var fail = false
        val model = model(load = {
            reads++
            if (fail) throw IOException("read failed")
            stored
        }) { }
        runCurrent()
        assertEquals(false, model.uiState.value.enabled)
        stored = true
        model.refresh()
        runCurrent()
        assertEquals(true, model.uiState.value.enabled)
        assertEquals(2, reads)

        fail = true
        model.refresh()
        runCurrent()
        assertNull(model.uiState.value.enabled)
        assertTrue(model.uiState.value.hasError)
        model.open()
        assertFalse(model.uiState.value.dialogRequested)
        fail = false
        model.refresh()
        runCurrent()
        assertEquals(true, model.uiState.value.enabled)
        assertFalse(model.uiState.value.hasError)
    }

    private fun TestScope.model(
        load: suspend () -> Boolean = { false },
        save: suspend (Boolean) -> Unit
    ): SyncLyricOptimizationViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SyncLyricOptimizationViewModel(load, save).also(models::add)
    }
}
