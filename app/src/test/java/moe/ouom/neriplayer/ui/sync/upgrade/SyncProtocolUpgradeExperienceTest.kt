package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncProtocolUpgradeExperienceTest {
    private val models = mutableListOf<SyncProtocolUpgradeViewModel>()
    private val target = "a".repeat(64)
    private val challenge = SyncProtocolUpgradeChallenge(target, "1".repeat(64))

    @After fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `old configured installation can open before any remote challenge and requires a device declaration`() = runTest {
        val startup = MutableStateFlow<Set<String>>(emptySet())
        val model = model(startup = startup, initialize = { startup.value = setOf(target) })
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        model.openConfirmation()
        assertEquals(target, model.uiState.value.startupTargetId)
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.canConfirm)
        model.setAllDevicesUpdated(true)
        assertTrue(model.uiState.value.canConfirm)
    }

    @Test fun `startup confirmation keeps progress until sync completion`() = runTest {
        val calls = mutableListOf<String>()
        val complete = CompletableDeferred<Unit>()
        val startup = MutableStateFlow(setOf(target))
        val model = model(startup, sync = { id, mayApprove ->
            assertEquals(target, id)
            assertTrue(mayApprove)
            calls += "sync"
            startup.value = emptySet()
            complete.await()
            Result.success(SyncResult(true, "completed"))
        })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        model.confirm()
        runCurrent()
        assertEquals(listOf("sync"), calls)
        assertTrue(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.isSyncing)
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.dismissConfirmation())
        complete.complete(Unit)
        runCurrent()
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.isSyncing)
        assertEquals("completed", model.uiState.value.syncResult?.message)
    }

    @Test fun `version setting confirmation performs sync even without an earlier sync button retry`() = runTest {
        val calls = mutableListOf<String>()
        val model = model(pending = listOf(challenge),
            save = { _, value -> assertEquals(challenge, value); calls += "confirm" }, sync = { id, mayApprove ->
                assertEquals(target, id)
                assertFalse(mayApprove)
                calls += "sync"
                Result.success(SyncResult(true, "completed"))
            })
        runCurrent()
        model.openConfirmation(target)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(listOf("confirm", "sync"), calls)
    }

    @Test fun `an explicit manual retry runs once after confirmation without a second direct sync`() = runTest {
        val calls = mutableListOf<String>()
        val model = model(pending = listOf(challenge),
            save = { _, _ -> calls += "confirm" }, sync = { _, _ -> error("duplicate direct sync") })
        runCurrent()
        model.requestUpgrade(challenge) { calls += "retry" }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(listOf("confirm", "retry"), calls)
    }

    @Test fun `V3 upgrade confirms the exact source and hides after V4 observation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v3 = challenge.copy(fromVersion = 3)
        val pending = MutableStateFlow(listOf(v3))
        val startup = MutableStateFlow(setOf(target))
        val saved = mutableListOf<SyncProtocolUpgradeChallenge>()
        val model = SyncProtocolUpgradeViewModel(
            pending, { _, value -> saved += value },
            startupTargetsFlow = startup,
            performImmediateSync = { _, _ ->
                pending.value = emptyList()
                startup.value = emptySet()
                Result.success(SyncResult(true, "completed"))
            }
        ).also(models::add)
        runCurrent()
        model.openConfirmation(target)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(listOf(v3), saved)
        assertEquals(true, model.uiState.value.approved)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test fun `cancel discards the declaration without writing or syncing`() = runTest {
        val model = model(startup = MutableStateFlow(setOf(target)), save = { _, _ -> error("cancel wrote") },
            sync = { _, _ -> error("cancel synced") })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        assertTrue(model.dismissConfirmation())
        model.openConfirmation()
        assertFalse(model.uiState.value.allDevicesUpdated)
    }

    @Test fun `failed confirmation keeps the dialog and cannot sync`() = runTest {
        var syncs = 0
        var saves = 0
        val model = model(pending = listOf(challenge),
            save = { _, _ -> saves++; throw IOException("write failed") },
            sync = { _, _ -> syncs++; Result.success(SyncResult(true, "")) })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(0, syncs)
        assertEquals(1, saves)
        assertTrue(model.uiState.value.dialogRequested)
        assertTrue(model.uiState.value.hasError)
    }

    @Test fun `failed direct sync can retry the already saved exact confirmation without saving it again`() = runTest {
        var saves = 0
        var syncs = 0
        val model = model(pending = listOf(challenge), save = { _, _ -> saves++; check(saves == 1) },
            sync = { _, _ ->
                syncs++
                if (syncs == 1) Result.failure(IOException("offline")) else Result.success(SyncResult(true, "completed"))
            })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.dialogRequested)
        model.confirm()
        runCurrent()
        assertEquals(1, saves)
        assertEquals(2, syncs)
        assertFalse(model.uiState.value.hasError)
        assertFalse(model.uiState.value.dialogRequested)
    }

    @Test fun `retired startup target never approves or starts a request`() = runTest {
        var active = setOf(target)
        val model = model(startup = MutableStateFlow(setOf(target)), targets = { active },
            save = { _, _ -> error("retired target wrote") }, sync = { _, _ -> error("retired target synced") })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        active = emptySet()
        model.confirm()
        runCurrent()
        assertTrue(model.uiState.value.hasError)
    }

    @Test fun `manual sync reopens a deferred startup choice before any network probe`() = runTest {
        val model = model(startup = MutableStateFlow(setOf(target)))
        runCurrent()
        model.openConfirmation()
        model.dismissConfirmation()
        model.requestSync(target) { error("must show upgrade first") }
        assertTrue(model.uiState.value.dialogRequested)
        assertEquals(target, model.uiState.value.targetId)
    }

    @Test fun `a second target cannot replace the retry for an already open dialog`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        var retries = 0
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge, other)), { _, _ -> })
            .also(models::add)
        runCurrent()
        model.requestSync(target) { retries++ }
        model.setAllDevicesUpdated(true)
        model.requestSync(other.targetId) { error("wrong target retry") }
        model.confirm()
        runCurrent()
        assertEquals(1, retries)
    }

    @Test fun `a changed fingerprint during confirmation replaces the request and requires a new declaration`() = runTest {
        val changed = challenge.copy(fingerprint = "2".repeat(64))
        val saved = mutableListOf<SyncProtocolUpgradeChallenge>()
        val model = model(pending = listOf(challenge), save = { _, value ->
            saved += value
            if (value == challenge) throw SyncProtocolUpgradeRequiredException("changed", changed)
        })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(changed, model.uiState.value.challenge)
        assertFalse(model.uiState.value.allDevicesUpdated)
        assertFalse(model.uiState.value.canConfirm)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(listOf(challenge, changed), saved)
    }

    @Test fun `a newly required identical challenge cannot reuse an approval consumed by current observation`() = runTest {
        var saves = 0
        var syncs = 0
        val model = model(pending = listOf(challenge), save = { _, _ -> saves++ }, sync = { _, _ ->
            syncs++
            if (syncs == 1) Result.failure(SyncProtocolUpgradeRequiredException("required again", challenge))
            else Result.success(SyncResult(true, "completed"))
        })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertFalse(model.uiState.value.canConfirm)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(2, saves)
        assertEquals(2, syncs)
    }

    @Test fun `cancelling a failed sync recomputes startup status already cleared by current observation`() = runTest {
        val startup = MutableStateFlow(setOf(target))
        val model = model(startup = startup, sync = { _, _ ->
            startup.value = emptySet()
            runCurrent()
            Result.failure(IOException("failed after observing current data"))
        })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertTrue(model.uiState.value.dialogRequested)
        model.dismissConfirmation()
        assertEquals(true, model.uiState.value.approved)
        assertEquals(null, model.uiState.value.targetId)
    }

    @Test fun `a pending state read failure during sync retains the request for explicit retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = false
        val pending = MutableStateFlow<List<SyncProtocolUpgradeChallenge>>(emptyList())
        val finish = CompletableDeferred<Unit>()
        val model = SyncProtocolUpgradeViewModel(
            pending.map { if (fail) throw IOException("state unavailable") else it }, { _, _ -> },
            startupTargetsFlow = flowOf(setOf(target)), performImmediateSync = { _, _ ->
                finish.await()
                Result.failure(IOException("offline"))
            }
        ).also(models::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        fail = true
        pending.value = listOf(challenge)
        runCurrent()
        assertEquals(target, model.uiState.value.targetId)
        finish.complete(Unit)
        runCurrent()
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.canConfirm)
    }

    @Test fun `unconfigured and current targets can sync while another target still needs upgrading`() = runTest {
        val model = model(startup = MutableStateFlow(setOf(target)))
        runCurrent()
        var calls = 0
        model.requestSync(null) { calls++ }
        model.requestSync("b".repeat(64)) { calls++ }
        assertEquals(2, calls)
        assertFalse(model.uiState.value.dialogRequested)
        assertEquals(false, model.uiState.value.approved)
    }

    @Test fun `a detected manual upgrade opens before retrying its original target`() = runTest {
        var retries = 0
        val model = model(pending = listOf(challenge))
        runCurrent()
        model.requestSync(target) { retries++ }
        assertTrue(model.uiState.value.dialogRequested)
        assertEquals(challenge, model.uiState.value.challenge)
        assertEquals(0, retries)
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(1, retries)
    }

    @Test fun `refresh after a failed manual retry restores the uncompleted upgrade warning`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = MutableStateFlow(listOf(challenge))
        val startup = MutableStateFlow<Set<String>>(emptySet())
        val model = SyncProtocolUpgradeViewModel(pending, { _, _ ->
            pending.value = emptyList()
            startup.value = setOf(target)
        }, startupTargetsFlow = startup).also(models::add)
        runCurrent()
        model.requestUpgrade(challenge) { model.refreshTargets() }
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        assertEquals(target, model.uiState.value.startupTargetId)
        model.openConfirmation(target)
        assertTrue(model.uiState.value.dialogRequested)
        assertFalse(model.uiState.value.allDevicesUpdated)
    }

    @Test fun `an unsuccessful immediate sync payload keeps the upgrade dialog retryable`() = runTest {
        val model = model(startup = MutableStateFlow(setOf(target)), sync = { _, _ ->
            Result.success(SyncResult(false, "not completed"))
        })
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(false, model.uiState.value.approved)
        assertTrue(model.uiState.value.dialogRequested)
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.uiState.value.canConfirm)
        assertEquals(null, model.uiState.value.syncResult)
    }

    @Test fun `unidentified or other target upgrade failures cannot replace the current confirmation`() = runTest {
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        for (reported in listOf(other, null)) {
            val model = model(startup = MutableStateFlow(setOf(target)), sync = { _, _ ->
                Result.failure(SyncProtocolUpgradeRequiredException("upgrade required", reported))
            })
            runCurrent()
            model.openConfirmation()
            model.setAllDevicesUpdated(true)
            model.confirm()
            runCurrent()
            assertEquals(target, model.uiState.value.targetId)
            assertEquals(null, model.uiState.value.challenge)
            assertTrue(model.uiState.value.dialogRequested)
            assertTrue(model.uiState.value.hasError)
            assertTrue(model.uiState.value.canConfirm)
            assertEquals(null, model.uiState.value.syncResult)
        }
    }

    private fun TestScope.model(
        startup: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        pending: List<SyncProtocolUpgradeChallenge> = emptyList(),
        initialize: suspend () -> Unit = {},
        targets: suspend () -> Set<String>? = { setOf(target) },
        save: suspend (Boolean, SyncProtocolUpgradeChallenge) -> Unit = { _, _ -> },
        sync: suspend (String, Boolean) -> Result<SyncResult> = { _, _ -> Result.success(SyncResult(true, "")) }
    ): SyncProtocolUpgradeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SyncProtocolUpgradeViewModel(flowOf(pending), save, targets, startup, initialize, sync)
            .also(models::add)
    }
}
