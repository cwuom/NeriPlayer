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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
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
    fun `pending legacy data does not prevent probing another sync address`() = runTest {
        var calls = 0
        val model = viewModel(flowOf(false)) { }
        runCurrent()
        model.requestSync { calls++ }
        assertEquals(1, calls)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `legacy detection reopens deferred confirmation and retries after a durable save`() = runTest {
        var retries = 0
        val model = viewModel(flowOf(false)) { }
        runCurrent()
        repeat(2) {
            model.requestUpgrade(challenge) { retries++ }
            assertTrue(model.uiState.value.dialogRequested)
            assertFalse(model.uiState.value.canConfirm)
            assertTrue(model.dismissConfirmation())
        }
        model.requestUpgrade(challenge) { retries++ }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertEquals(1, retries)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `manual sync may probe while state loads but cannot bypass a pending save`() = runTest {
        val approvals = MutableSharedFlow<Boolean>()
        val saved = CompletableDeferred<Unit>()
        var calls = 0
        val model = viewModel(approvals) { saved.await() }
        runCurrent()
        model.requestSync { calls++ }
        assertNull(model.uiState.value.approved)
        assertFalse(model.uiState.value.dialogRequested)
        assertEquals(1, calls)
        approvals.emit(false)
        runCurrent()
        model.requestUpgrade(challenge) { calls++ }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        model.requestSync { calls++ }
        assertTrue(model.uiState.value.isSaving)
        assertEquals(1, calls)
        saved.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
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
        assertFalse(model.uiState.value.dialogRequested)
        model.confirm()
        runCurrent()
        assertEquals(0, saves)
        model.requestUpgrade(challenge) { }
        assertTrue(model.uiState.value.dialogRequested)
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

    @Test
    fun `approval saves the detected address and fingerprint rather than another pending target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        var saved: SyncProtocolUpgradeChallenge? = null
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(other, challenge)), { _, value -> saved = value })
            .also(viewModels::add)
        runCurrent()
        model.requestUpgrade(challenge) { }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(challenge, saved)
        assertEquals(other, model.uiState.value.challenge)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `a delayed old address response cannot open a dialog after configuration changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val loadedTargets = CompletableDeferred<Set<String>>()
        var delayRead = false
        val model = SyncProtocolUpgradeViewModel(flowOf(emptyList()), { _, _ -> }, {
            if (delayRead) loadedTargets.await() else setOf(challenge.targetId)
        }).also(viewModels::add)
        runCurrent()
        delayRead = true
        model.requestUpgrade(challenge) { error("retired address must not retry") }
        runCurrent()
        loadedTargets.complete(setOf("b".repeat(64)))
        runCurrent()
        assertFalse(model.uiState.value.dialogRequested)
        assertNull(model.uiState.value.challenge)
    }

    @Test
    fun `confirmation does not retry a replaced address after saving finishes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var targets = setOf(challenge.targetId)
        val saving = CompletableDeferred<Unit>()
        var retries = 0
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge)), { _, _ -> saving.await() }, { targets })
            .also(viewModels::add)
        runCurrent()
        model.requestUpgrade(challenge) { retries++ }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        targets = setOf("b".repeat(64))
        saving.complete(Unit)
        runCurrent()
        assertEquals(0, retries)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test
    fun `a transient state read failure can recover when configuration is refreshed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val pending = flow<List<SyncProtocolUpgradeChallenge>> {
            if (fail) throw IOException("temporarily unavailable")
            emit(emptyList())
        }
        val model = SyncProtocolUpgradeViewModel(pending, { _, _ -> }).also(viewModels::add)
        runCurrent()
        assertTrue(model.uiState.value.hasError)
        assertNull(model.uiState.value.challenge)
        fail = false
        model.refreshTargets()
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.hasError)
    }

    @Test
    fun `retired addresses do not prompt and configuration refresh discovers active pending data`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var targets = emptySet<String>()
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge)), { _, _ -> }, { targets })
            .also(viewModels::add)
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        model.openConfirmation()
        assertFalse(model.uiState.value.dialogRequested)
        targets = setOf(challenge.targetId)
        model.refreshTargets()
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        model.openConfirmation(challenge.targetId)
        assertTrue(model.uiState.value.dialogRequested)
    }

    private val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))

    @Test
    fun `a changed remote challenge invalidates the checked declaration and original retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = MutableStateFlow(listOf(challenge))
        val model = SyncProtocolUpgradeViewModel(pending, { _, _ -> }).also(viewModels::add)
        var retries = 0
        runCurrent()
        model.requestUpgrade(challenge) { retries++ }
        model.setAllDevicesUpdated(true)
        val changed = challenge.copy(fingerprint = "3".repeat(64))
        pending.value = listOf(changed)
        runCurrent()
        assertEquals(changed, model.uiState.value.challenge)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.allDevicesUpdated)
        model.openConfirmation()
        model.confirm()
        runCurrent()
        assertEquals(0, retries)
        assertFalse(model.uiState.value.canConfirm)
    }

    @Test
    fun `version entry selects its own address and duplicate clicks preserve its declaration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge, other)), { _, _ -> })
            .also(viewModels::add)
        runCurrent()
        model.openConfirmation("c".repeat(64))
        assertFalse(model.uiState.value.dialogRequested)
        model.openConfirmation(other.targetId)
        model.setAllDevicesUpdated(true)
        model.openConfirmation(challenge.targetId)
        assertEquals(other, model.uiState.value.challenge)
        assertTrue(model.uiState.value.allDevicesUpdated)
        assertTrue(model.uiState.value.canConfirm)
    }

    @Test
    fun `parallel legacy detection cannot replace a declaration while its confirmation is saving`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        val pending = MutableStateFlow(listOf(challenge))
        val saving = CompletableDeferred<Unit>()
        var saved: SyncProtocolUpgradeChallenge? = null
        var retries = 0
        val model = SyncProtocolUpgradeViewModel(pending, { _, selected ->
            saved = selected
            saving.await()
        }).also(viewModels::add)
        runCurrent()
        model.requestUpgrade(challenge) { retries++ }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        pending.value = listOf(other)
        runCurrent()
        model.openConfirmation(other.targetId)
        model.requestUpgrade(other) { error("another address must not inherit the retry") }
        assertEquals(challenge, model.uiState.value.challenge)
        assertEquals(challenge, saved)
        assertTrue(model.uiState.value.allDevicesUpdated)
        assertTrue(model.uiState.value.isSaving)
        saving.complete(Unit)
        runCurrent()
        assertEquals(1, retries)
        assertEquals(other, model.uiState.value.challenge)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.allDevicesUpdated)
    }

    @Test
    fun `remote upgrade removes the warning dialog and its checked declaration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = MutableStateFlow(listOf(challenge))
        val model = SyncProtocolUpgradeViewModel(pending, { _, _ -> }).also(viewModels::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        pending.value = emptyList()
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertNull(model.uiState.value.challenge)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.allDevicesUpdated)
        assertFalse(model.uiState.value.canConfirm)
    }

    private fun TestScope.viewModel(
        approvals: Flow<Boolean>,
        save: suspend (Boolean) -> Unit
    ): SyncProtocolUpgradeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SyncProtocolUpgradeViewModel(
            approvals.map { if (it) emptyList() else listOf(challenge) },
            { updated, _ -> save(updated) }
        ).also(viewModels::add)
    }
}
