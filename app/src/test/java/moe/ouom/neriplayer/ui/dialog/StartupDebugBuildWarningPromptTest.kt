package moe.ouom.neriplayer.ui.dialog

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StartupDebugBuildWarningPromptTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `debug warning waits for resumed lifecycle and other startup dialogs`() = runTest {
        withRepository { repository ->
            val fixture = PromptFixture(this, repository)
            try {
                fixture.canShowDialog = false
                fixture.start()
                fixture.pump()
                assertTrue(fixture.pending)
                assertNull(fixture.confirm)

                fixture.canShowDialog = true
                fixture.isResumed = false
                fixture.pump()
                assertNull(fixture.confirm)

                fixture.isResumed = true
                fixture.pump()
                assertNotNull(fixture.confirm)
                assertFalse(repository.isAcknowledged())

                fixture.isResumed = false
                fixture.pump()
                assertNull(fixture.confirm)
                assertTrue(fixture.pending)
                fixture.isResumed = true
                fixture.canShowDialog = false
                fixture.pump()
                assertNull(fixture.confirm)
                assertFalse(repository.isAcknowledged())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `confirmed warning does not return after activity composition is recreated`() = runTest {
        withRepository { repository ->
            val first = PromptFixture(this, repository)
            try {
                first.start()
                first.pump()
                requireNotNull(first.confirm).invoke()
                first.pump()
                assertFalse(first.pending)
                assertNull(first.confirm)
                assertTrue(repository.isAcknowledged())
            } finally {
                first.close()
            }

            val recreated = PromptFixture(this, repository)
            try {
                recreated.start()
                recreated.pump()
                assertFalse(recreated.pending)
                assertNull(recreated.confirm)
            } finally {
                recreated.close()
            }
        }
    }

    @Test
    fun `leaving an unconfirmed warning does not consume the reminder`() = runTest {
        withRepository { repository ->
            val first = PromptFixture(this, repository)
            try {
                first.start()
                first.pump()
                assertNotNull(first.confirm)
            } finally {
                first.close()
            }

            val recreated = PromptFixture(this, repository)
            try {
                recreated.start()
                recreated.pump()
                assertTrue(recreated.pending)
                assertNotNull(recreated.confirm)
            } finally {
                recreated.close()
            }
        }
    }

    @Test
    fun `release build never displays or consumes a debug warning`() = runTest {
        withRepository { repository ->
            val fixture = PromptFixture(this, repository, isDebugBuild = false)
            try {
                fixture.start()
                fixture.pump()
                assertFalse(fixture.pending)
                assertNull(fixture.confirm)
                assertFalse(repository.isAcknowledged())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `loading confirmation blocks later startup prompts without showing the warning early`() = runTest {
        val readReady = CompletableDeferred<Unit>()
        withRepository(beforeRead = readReady) { repository ->
            val fixture = PromptFixture(this, repository)
            try {
                fixture.start()
                fixture.pump()
                assertTrue(fixture.pending)
                assertNull(fixture.confirm)

                readReady.complete(Unit)
                fixture.pump()
                assertTrue(fixture.pending)
                assertNotNull(fixture.confirm)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `failed confirmation keeps the warning visible until a successful retry`() = runTest {
        var failWrite = true
        withRepository(beforeWrite = { if (failWrite) throw IOException("write failed") }) { repository ->
            val fixture = PromptFixture(this, repository)
            try {
                fixture.start()
                fixture.pump()
                requireNotNull(fixture.confirm).invoke()
                fixture.pump()
                assertTrue(fixture.pending)
                assertTrue(fixture.saveFailed)
                assertFalse(fixture.saving)
                assertNotNull(fixture.confirm)
                assertFalse(repository.isAcknowledged())

                failWrite = false
                requireNotNull(fixture.confirm).invoke()
                fixture.pump()
                assertFalse(fixture.pending)
                assertNull(fixture.confirm)
                assertTrue(repository.isAcknowledged())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `cancelled confirmation does not consume the warning`() = runTest {
        withRepository(beforeWrite = { throw CancellationException("cancelled") }) { repository ->
            val fixture = PromptFixture(this, repository)
            try {
                fixture.start()
                fixture.pump()
                requireNotNull(fixture.confirm).invoke()
                fixture.pump()
                assertTrue(fixture.pending)
                assertNotNull(fixture.confirm)
                assertFalse(fixture.saving)
                assertFalse(fixture.saveFailed)
                assertFalse(repository.isAcknowledged())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `transient read failure recovers and observes confirmation from another activity`() = runTest {
        var reads = 0
        withRepository(beforeReadAction = { if (reads++ == 0) throw IOException("read failed") }) { repository ->
            val first = PromptFixture(this, repository)
            val second = PromptFixture(this, repository)
            try {
                first.start()
                first.pump()
                assertNotNull(first.confirm)
                second.start()
                second.pump()
                requireNotNull(second.confirm).invoke()
                first.pump()
                second.pump()
                assertFalse(first.pending)
                assertFalse(second.pending)
                assertNull(first.confirm)
                assertNull(second.confirm)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    private suspend fun TestScope.withRepository(
        beforeRead: CompletableDeferred<Unit>? = null,
        beforeReadAction: () -> Unit = {},
        beforeWrite: () -> Unit = {},
        test: suspend (DebugBuildWarningRepository) -> Unit
    ) {
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(storeJob + StandardTestDispatcher(testScheduler))
        try {
            val store = PreferenceDataStoreFactory.create(scope = storeScope) {
                File(temporaryFolder.root, "warning.preferences_pb")
            }
            val controlledStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    beforeReadAction()
                    beforeRead?.await()
                    emitAll(store.data)
                }

                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences
                ): Preferences {
                    beforeWrite()
                    return store.updateData(transform)
                }
            }
            test(DebugBuildWarningRepository(controlledStore))
        } finally {
            storeScope.cancel()
            storeJob.join()
        }
    }

    private class PromptFixture(
        private val scope: TestScope,
        private val repository: DebugBuildWarningRepository,
        private val isDebugBuild: Boolean = true
    ) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        var canShowDialog by mutableStateOf(true)
        var isResumed by mutableStateOf(true)
        var pending = false
        var saving = false
        var saveFailed = false
        var confirm: (() -> Unit)? = null

        fun start() {
            composition.setContent {
                val isPending = startupDebugBuildWarningPrompt(
                    canShowDialog = canShowDialog,
                    repository = repository,
                    isDebugBuild = isDebugBuild,
                    isResumed = isResumed
                ) { isSaving, failed, onConfirm ->
                    SideEffect {
                        confirm = onConfirm
                        saving = isSaving
                        saveFailed = failed
                    }
                    DisposableEffect(Unit) {
                        onDispose { confirm = null }
                    }
                }
                SideEffect { pending = isPending }
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
