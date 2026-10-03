package moe.ouom.neriplayer.ui.onboarding

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.ui.sync.upgrade.StartupSyncUpgradePrompt
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeUiState
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction

@OptIn(ExperimentalCoroutinesApi::class)
class StartupBackupRestoreSyncTest {
    private val models = mutableListOf<ViewModel>()
    private val target = "a".repeat(64)
    private val challenge = SyncProtocolUpgradeChallenge(target, "1".repeat(64))

    @After
    fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `onboarding does not compose configuration or start sync before registration completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = CompletableDeferred<Unit>()
        val model = upgradeModel(initialize = { persisted.await() })
        val fixture = BackupCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertFalse(fixture.configurationVisible)
            assertEquals(false, fixture.waitingFailed)
            requestStartupBackupSync(target, model) { _, _, _ -> error("sync started before registration") }

            persisted.complete(Unit)
            fixture.pump()

            assertTrue(fixture.configurationVisible)
            assertNull(fixture.waitingFailed)
            assertNull(fixture.visibleDialog)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `onboarding registration failure exposes retry while configuration remains unavailable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val model = upgradeModel(initialize = { if (++attempts == 1) throw IOException("storage unavailable") })
        val fixture = BackupCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertFalse(fixture.configurationVisible)
            assertEquals(true, fixture.waitingFailed)

            requireNotNull(fixture.retryRegistration).invoke()
            fixture.pump()

            assertEquals(2, attempts)
            assertTrue(fixture.configurationVisible)
            assertNull(fixture.waitingFailed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an existing configured installation still confirms before an onboarding network request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = upgradeModel(startup = flowOf(setOf(target)))
        val fixture = BackupCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertTrue(fixture.configurationVisible)
            assertEquals(target, fixture.visibleDialog?.startupTargetId)
            assertFalse(requireNotNull(fixture.visibleDialog).canConfirm)

            requireNotNull(fixture.deferConfirmation).invoke()
            fixture.pump()
            requestStartupBackupSync(target, model) { _, _, _ -> error("old target was probed before confirmation") }
            fixture.pump()

            assertEquals(target, fixture.visibleDialog?.startupTargetId)
            assertFalse(requireNotNull(fixture.visibleDialog).allDevicesUpdated)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `both onboarding providers reopen deferred confirmation and sync the original address once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (provider in providers()) {
                val pending = MutableStateFlow(emptyList<SyncProtocolUpgradeChallenge>())
                val startup = MutableStateFlow(emptySet<String>())
                val saved = mutableListOf<SyncProtocolUpgradeChallenge>()
                val requested = mutableListOf<String>()
                val completed = CompletableDeferred<Unit>()
                val model = upgradeModel(pending = pending, startup = startup, save = { updated, value ->
                    assertTrue(updated)
                    saved += value
                    pending.value = emptyList()
                    startup.value = setOf(target)
                })
                provider.setOperation { id ->
                    requested += id
                    if (requested.size == 1) {
                        pending.value = listOf(challenge)
                        Result.failure(SyncProtocolUpgradeRequiredException("upgrade required", challenge))
                    } else {
                        completed.await()
                        startup.value = emptySet()
                        Result.success(SyncResult(true, "restored"))
                    }
                }
                val fixture = BackupCompositionFixture(this, model, provider.syncing)
                try {
                    fixture.start()
                    fixture.pump()
                    requestStartupBackupSync(target, model, provider.perform)
                    fixture.pump()
                    assertEquals(challenge, fixture.visibleDialog?.challenge)
                    assertFalse(requireNotNull(fixture.visibleDialog).canConfirm)
                    assertNull(provider.errorMessage())

                    model.setAllDevicesUpdated(true)
                    requireNotNull(fixture.deferConfirmation).invoke()
                    fixture.pump()
                    assertNull(fixture.visibleDialog)
                    assertTrue(saved.isEmpty())
                    requestStartupBackupSync(target, model, provider.perform)
                    fixture.pump()
                    assertFalse(requireNotNull(fixture.visibleDialog).allDevicesUpdated)
                    model.setAllDevicesUpdated(true)
                    model.confirm()
                    fixture.pump()

                    assertEquals(listOf(challenge), saved)
                    assertEquals(listOf(target, target), requested)
                    assertNull(fixture.visibleDialog)
                    assertTrue(fixture.configurationVisible)

                    completed.complete(Unit)
                    fixture.pump()
                    assertEquals("restored", provider.successMessage())
                    assertEquals(true, model.uiState.value.approved)
                    assertNull(fixture.visibleDialog)
                    assertTrue(fixture.configurationVisible)
                } finally {
                    completed.complete(Unit)
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `failed onboarding confirmation keeps the dialog and retries without another remote request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            for (provider in providers()) {
                val pending = MutableStateFlow(emptyList<SyncProtocolUpgradeChallenge>())
                var saves = 0
                var requests = 0
                val model = upgradeModel(pending = pending, save = { _, _ ->
                    if (++saves == 1) throw IOException("confirmation failed")
                    pending.value = emptyList()
                })
                provider.setOperation {
                    if (++requests == 1) {
                        pending.value = listOf(challenge)
                        Result.failure(SyncProtocolUpgradeRequiredException("upgrade required", challenge))
                    } else Result.success(SyncResult(true, "restored"))
                }
                val fixture = BackupCompositionFixture(this, model, provider.syncing)
                try {
                    fixture.start()
                    fixture.pump()
                    requestStartupBackupSync(target, model, provider.perform)
                    fixture.pump()
                    model.setAllDevicesUpdated(true)
                    model.confirm()
                    fixture.pump()
                    assertTrue(requireNotNull(fixture.visibleDialog).hasError)
                    assertEquals(1, requests)
                    assertTrue(fixture.configurationVisible)

                    model.confirm()
                    fixture.pump()

                    assertEquals(2, saves)
                    assertEquals(2, requests)
                    assertEquals("restored", provider.successMessage())
                    assertNull(fixture.visibleDialog)
                    assertTrue(fixture.configurationVisible)
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `changing the configured address while confirming never approves or retries the retired target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (provider in providers()) {
            val pending = MutableStateFlow(emptyList<SyncProtocolUpgradeChallenge>())
            var active = setOf(target)
            var requests = 0
            val model = upgradeModel(pending = pending, active = { active }, save = { _, _ -> error("retired target approved") })
            provider.setOperation {
                requests++
                pending.value = listOf(challenge)
                Result.failure(SyncProtocolUpgradeRequiredException("upgrade required", challenge))
            }
            val fixture = BackupCompositionFixture(this, model, provider.syncing)
            try {
                fixture.start()
                fixture.pump()
                requestStartupBackupSync(target, model, provider.perform)
                fixture.pump()
                assertNotNull(fixture.visibleDialog)
                active = setOf("b".repeat(64))
                model.setAllDevicesUpdated(true)
                model.confirm()
                fixture.pump()

                assertEquals(1, requests)
                assertTrue(requireNotNull(fixture.visibleDialog).hasError)
                assertNull(provider.successMessage())
            } finally {
                fixture.close()
            }
        }
    }

    private fun upgradeModel(
        pending: Flow<List<SyncProtocolUpgradeChallenge>> = flowOf(emptyList()),
        startup: Flow<Set<String>> = flowOf(emptySet()),
        active: suspend () -> Set<String> = { setOf(target) },
        initialize: suspend () -> Unit = {},
        save: suspend (Boolean, SyncProtocolUpgradeChallenge) -> Unit = { _, _ -> error("unexpected approval") }
    ): SyncProtocolUpgradeViewModel = SyncProtocolUpgradeViewModel(
        pending, save, loadActiveTargets = active, startupTargetsFlow = startup,
        initializeStartupTargets = initialize,
        performImmediateSync = { _, _ -> error("duplicate immediate sync") }
    ).also(models::add)

    private fun providers(): List<Provider> {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val github = GitHubSyncViewModel().also(models::add)
        val webdav = WebDavSyncViewModel().also(models::add)
        return listOf(
            Provider(
                { github.targetSyncOperation = it },
                { id, finished, upgrade -> github.performSyncForTarget(context, id, finished, upgrade) },
                github.uiState.map { it.isSyncing },
                { github.uiState.value.successMessage }, { github.uiState.value.errorMessage }
            ),
            Provider(
                { webdav.targetSyncOperation = it },
                { id, finished, upgrade -> webdav.performSyncForTarget(context, id, finished, upgrade) },
                webdav.uiState.map { it.isSyncing },
                { webdav.uiState.value.successMessage }, { webdav.uiState.value.errorMessage }
            )
        )
    }

    private data class Provider(
        val setOperation: (suspend (String) -> Result<SyncResult>) -> Unit,
        val perform: (String, () -> Unit, (SyncProtocolUpgradeChallenge) -> Unit) -> Unit,
        val syncing: Flow<Boolean>,
        val successMessage: () -> String?,
        val errorMessage: () -> String?
    )

    private class BackupCompositionFixture(
        private val scope: TestScope,
        private val model: SyncProtocolUpgradeViewModel,
        private val syncing: Flow<Boolean> = flowOf(false)
    ) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        var configurationVisible = false
        var waitingFailed: Boolean? = null
        var retryRegistration: (() -> Unit)? = null
        var visibleDialog: SyncProtocolUpgradeUiState? = null
        var deferConfirmation: (() -> Unit)? = null

        fun start() {
            composition.setContent {
                val state by model.uiState.collectAsState()
                val busy by syncing.collectAsState(false)
                StartupBackupRestoreSyncGate(state, model::refreshTargets, waitingContent = { failed, retry ->
                    SideEffect { waitingFailed = failed; retryRegistration = retry }
                    DisposableEffect(Unit) { onDispose { waitingFailed = null; retryRegistration = null } }
                }) {
                    SideEffect { configurationVisible = true }
                    DisposableEffect(Unit) { onDispose { configurationVisible = false } }
                }
                StartupSyncUpgradePrompt(
                    canShowDialog = state.startupRegistrationComplete && !busy,
                    viewModel = model,
                    isResumed = true,
                    resultContent = { _, _ -> }
                ) { value, defer ->
                    SideEffect { visibleDialog = value; deferConfirmation = defer }
                    DisposableEffect(Unit) { onDispose { visibleDialog = null; deferConfirmation = null } }
                }
            }
        }

        fun pump() {
            repeat(4) {
                scope.runCurrent()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                frameClock.sendFrame(frameTime++)
            }
            scope.runCurrent()
        }

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
