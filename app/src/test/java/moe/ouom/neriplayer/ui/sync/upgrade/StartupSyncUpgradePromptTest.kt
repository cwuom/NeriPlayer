package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupSyncUpgradePromptTest {
    private val viewModels = mutableListOf<SyncProtocolUpgradeViewModel>()

    @After
    fun cleanUp() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `startup prompt waits for registration even when an upgrade request is already queued`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = CompletableDeferred<Unit>()
        val model = SyncProtocolUpgradeViewModel(
            flowOf(listOf(challenge)), { _, _ -> error("registration must finish before confirmation") },
            initializeStartupTargets = { persisted.await() }
        ).also(viewModels::add)
        val fixture = PromptCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            model.requestUpgrade(challenge) { error("upgrade must not retry before confirmation") }
            fixture.pump()
            assertTrue(model.uiState.value.dialogRequested)
            assertFalse(model.uiState.value.startupRegistrationComplete)
            assertNull(fixture.visibleState)

            persisted.complete(Unit)
            fixture.pump()

            assertTrue(model.uiState.value.startupRegistrationComplete)
            assertEquals(challenge, fixture.visibleState?.challenge)
            assertFalse(requireNotNull(fixture.visibleState).canConfirm)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an old configured installation prompts before remote format is downloaded`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = SyncProtocolUpgradeViewModel(flowOf(emptyList()), { _, _ -> },
            startupTargetsFlow = flowOf(setOf(challenge.targetId))).also(viewModels::add)
        val fixture = PromptCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertEquals(challenge.targetId, fixture.visibleState?.startupTargetId)
            assertNull(fixture.visibleState?.challenge)
            assertFalse(requireNotNull(fixture.visibleState).allDevicesUpdated)
            assertFalse(requireNotNull(fixture.visibleState).canConfirm)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production startup waits for loaded approval resumed lifecycle and other startup dialogs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val approvals = MutableSharedFlow<Boolean>()
        val model = SyncProtocolUpgradeViewModel(approvals.map { if (it) emptyList() else listOf(challenge) }, { _, _ -> }).also(viewModels::add)
        val fixture = PromptCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertNull(fixture.visibleState)

            fixture.canShowDialog = false
            approvals.emit(false)
            fixture.pump()
            assertFalse(model.uiState.value.dialogRequested)
            assertNull(fixture.visibleState)
            fixture.canShowDialog = true
            fixture.isResumed = false
            fixture.pump()
            assertNull(fixture.visibleState)

            fixture.isResumed = true
            fixture.pump()
            assertNotNull(fixture.visibleState)
            assertFalse(requireNotNull(fixture.visibleState).canConfirm)
            fixture.canShowDialog = false
            fixture.pump()
            assertNull(fixture.visibleState)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production defer survives recreation settings can reopen and a new startup prompts again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var saves = 0
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge)), { _, _ -> saves++ }).also(viewModels::add)
        val first = PromptCompositionFixture(this, model)
        val savedState = try {
            first.start()
            first.pump()
            assertNotNull(first.visibleState)
            requireNotNull(first.onDefer).invoke()
            first.pump()
            assertNull(first.visibleState)
            assertEquals(false, model.uiState.value.approved)
            assertEquals(0, saves)
            first.saveState()
        } finally {
            first.close()
        }

        val recreated = PromptCompositionFixture(this, model, savedState)
        try {
            recreated.start()
            recreated.pump()
            assertNull(recreated.visibleState)
            var syncCalls = 0
            model.requestSync {
                syncCalls++
                model.requestUpgrade(challenge) { syncCalls++ }
            }
            recreated.pump()
            assertNotNull(recreated.visibleState)
            assertEquals(1, syncCalls)
            requireNotNull(recreated.onDefer).invoke()
            recreated.pump()
            assertNull(recreated.visibleState)
        } finally {
            recreated.close()
        }

        val nextStartup = PromptCompositionFixture(this, model)
        try {
            nextStartup.start()
            nextStartup.pump()
            assertNotNull(nextStartup.visibleState)
        } finally {
            nextStartup.close()
        }
    }

    @Test
    fun `production entry never prompts a previously confirmed device`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = SyncProtocolUpgradeViewModel(flowOf(emptyList()), { _, _ -> error("must not save") }).also(viewModels::add)
        val fixture = PromptCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertEquals(true, model.uiState.value.approved)
            assertNull(fixture.visibleState)
            assertFalse(model.uiState.value.dialogRequested)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production dialog gates confirmation on checkbox keeps failures visible and never approves cancellation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var saves = 0
        val model = SyncProtocolUpgradeViewModel(flowOf(listOf(challenge)), { _, _ ->
            when (++saves) {
                1 -> throw IOException("save failed")
                2 -> throw CancellationException("cancelled")
                else -> Unit
            }
        }).also(viewModels::add)
        val fixture = PromptCompositionFixture(this, model)
        try {
            fixture.start()
            fixture.pump()
            assertFalse(requireNotNull(fixture.visibleState).canConfirm)
            model.setAllDevicesUpdated(true)
            fixture.pump()
            assertTrue(requireNotNull(fixture.visibleState).canConfirm)
            model.confirm()
            fixture.pump()
            assertTrue(requireNotNull(fixture.visibleState).hasError)
            assertEquals(false, model.uiState.value.approved)

            model.confirm()
            fixture.pump()
            assertNotNull(fixture.visibleState)
            assertFalse(requireNotNull(fixture.visibleState).hasError)
            assertEquals(false, model.uiState.value.approved)

            model.confirm()
            fixture.pump()
            assertEquals(3, saves)
            assertEquals(true, model.uiState.value.approved)
            assertNull(fixture.visibleState)
        } finally {
            fixture.close()
        }
    }

    private val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))

    private class PromptCompositionFixture(
        private val scope: TestScope,
        private val model: SyncProtocolUpgradeViewModel,
        restoredState: Map<String, List<Any?>>? = null
    ) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private val saveableRegistry = SaveableStateRegistry(restoredState) { true }
        private var frameTime = 0L
        var canShowDialog by mutableStateOf(true)
        var isResumed by mutableStateOf(true)
        var visibleState: SyncProtocolUpgradeUiState? = null
        var onDefer: (() -> Unit)? = null

        fun start() {
            composition.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides saveableRegistry) {
                    StartupSyncUpgradePrompt(
                        canShowDialog = canShowDialog,
                        viewModel = model,
                        isResumed = isResumed
                    ) { state, defer ->
                        SideEffect {
                            visibleState = state
                            onDefer = defer
                        }
                        DisposableEffect(Unit) {
                            onDispose {
                                visibleState = null
                                onDefer = null
                            }
                        }
                    }
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

        fun saveState(): Map<String, List<Any?>> = saveableRegistry.performSave()

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
