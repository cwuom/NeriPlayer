package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.work.Data
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.DownloadDirectoryMigrationRecoveryController
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.DownloadDirectoryMigrationRecoveryGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.MIGRATION_SNAPSHOT_READ_RETRY_LIMIT
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.MigrationRecoveryLoopDecision
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.MigrationSharedProcessingProgress
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.PersistedMigrationUiSnapshot
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.activeMigrationWorkId
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.migrationRecoveryLoopDecision
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.migrationSharedProcessingProgress
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.restoredMigrationProgress
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.shouldKeepMigrationLoading
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.shouldPollMigrationRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsDownloadDirectoryMigrationRecoveryTest {
    @Test
    fun `restored progress prefers durable checkpoint over worker and current values`() {
        val durable = progress(3)
        val worker = progress(2)
        val current = progress(1)
        val snapshot = snapshot(
            activeWorkId = "active-work",
            state = WorkInfo.State.RUNNING,
            progress = durable
        )

        assertEquals(durable, restoredMigrationProgress(snapshot, worker, current))
        assertEquals(worker,
            restoredMigrationProgress(snapshot.copy(progress = null), worker, current)
        )
        assertEquals(current,
            restoredMigrationProgress(snapshot.copy(progress = null), null, current)
        )
    }

    @Test
    fun `only unfinished work is adopted from a durable snapshot`() {
        val running = snapshot(activeWorkId = "active-work", state = WorkInfo.State.RUNNING)

        assertEquals("active-work", activeMigrationWorkId(running))
        assertNull(activeMigrationWorkId(running.copy(activeWorkState = WorkInfo.State.SUCCEEDED)))
        assertNull(activeMigrationWorkId(running.copy(activeWorkId = null)))
    }

    @Test
    fun `recovery keeps loading for an existing migration or durable request`() {
        val terminal = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
        val durableRequest = terminal.copy(requestAutoResume = true, hasPersistedRequest = true)

        assertFalse(shouldKeepMigrationLoading(false, false, terminal))
        assertTrue(shouldKeepMigrationLoading(true, false, terminal))
        assertTrue(shouldKeepMigrationLoading(false, true, terminal))
        assertTrue(shouldKeepMigrationLoading(false, false, durableRequest))
        assertTrue(shouldPollMigrationRecovery(durableRequest, null))
        assertFalse(shouldPollMigrationRecovery(durableRequest, "running-work"))
    }

    @Test
    fun `recovery loop distinguishes terminal state from retry and exhausted resume`() {
        assertEquals(
            MigrationRecoveryLoopDecision.STOP,
            migrationRecoveryLoopDecision(false, true, true, false, true)
        )
        assertEquals(
            MigrationRecoveryLoopDecision.STOP,
            migrationRecoveryLoopDecision(true, false, true, false, true)
        )
        assertEquals(
            MigrationRecoveryLoopDecision.CLEAR_UI,
            migrationRecoveryLoopDecision(true, true, true, false, true)
        )
        assertEquals(
            MigrationRecoveryLoopDecision.RETRY,
            migrationRecoveryLoopDecision(true, true, true, true, true)
        )
        assertEquals(
            MigrationRecoveryLoopDecision.RETRY,
            migrationRecoveryLoopDecision(true, true, false, false, true)
        )
    }

    @Test
    fun `finished work is polled only while the same durable request still needs recovery`() {
        val controller = controller()
        val durable = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
            .copy(requestAutoResume = true, hasPersistedRequest = true)

        assertTrue(controller.shouldPollFinishedMigrationRecovery(durable, null))
        assertFalse(controller.shouldPollFinishedMigrationRecovery(durable, "old-work"))
        assertFalse(controller.shouldPollFinishedMigrationRecovery(
            durable.copy(activeWorkId = "new-work"), null
        ))
        assertFalse(controller.shouldPollFinishedMigrationRecovery(
            durable.copy(requestAutoResume = false, hasPersistedRequest = false), null
        ))
    }

    @Test
    fun `shared processing ignores unrelated work and clamps negative counts`() {
        val running = ManagedLibraryProcessingState.Running(
            operationId = "directory-change",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        assertNull(
            migrationSharedProcessingProgress(
                ManagedLibraryProcessingState.Idle,
                progress(1)
            )
        )
        assertNull(migrationSharedProcessingProgress(running, null))
        assertNull(
            migrationSharedProcessingProgress(
                running.copy(reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE),
                progress(1)
            )
        )
        assertEquals(
            MigrationSharedProcessingProgress("directory-change", 0, 4),
            migrationSharedProcessingProgress(running, progress(-1))
        )
    }

    @Test
    fun `active work restores migration and terminal snapshot clears only local state`() = runTest {
        val controller = controller()
        controller.beginMigration()
        assertTrue(controller.isMigrating)

        val active = controller.applyPersistedMigrationSnapshot(
            snapshot = snapshot(activeWorkId = "active-work", state = WorkInfo.State.RUNNING),
            autoResumeAttempted = false
        )
        assertTrue(active.preservedUi)
        assertFalse(active.attemptedAutoResume)
        assertEquals("active-work", controller.activeMigrationWorkId)

        val terminal = controller.applyPersistedMigrationSnapshot(
            snapshot = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED),
            autoResumeAttempted = false
        )
        assertFalse(terminal.preservedUi)
        assertFalse(controller.isMigrating)
        assertEquals(null, controller.activeMigrationWorkId)
    }

    @Test
    fun `failed enqueue clears loading without inventing a work id`() {
        val controller = controller()
        controller.beginMigration()

        controller.failMigration()

        assertFalse(controller.isMigrating)
        assertEquals(null, controller.activeMigrationWorkId)
    }

    @Test
    fun `startup adopts active durable work before opening the settings page`() = runTest {
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot =
                snapshot(activeWorkId = "durable-work", state = WorkInfo.State.RUNNING)
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val controller = controller(gateway)

        controller.recoverStartup()

        assertEquals("durable-work", controller.activeMigrationWorkId)
        assertTrue(controller.isMigrating)
    }

    @Test
    fun `durable request resumes once and adopts the returned worker`() = runTest {
        var resumeCalls = 0
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot = error("unexpected snapshot read")
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? {
                resumeCalls++
                return "resumed-work"
            }
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val controller = controller(gateway)
        val durableRequest = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
            .copy(requestAutoResume = true, hasPersistedRequest = true)

        val resumed = controller.applyPersistedMigrationSnapshot(durableRequest, autoResumeAttempted = false)
        val alreadyAttempted = controller.applyPersistedMigrationSnapshot(durableRequest, autoResumeAttempted = true)

        assertTrue(resumed.preservedUi)
        assertTrue(resumed.attemptedAutoResume)
        assertFalse(alreadyAttempted.attemptedAutoResume)
        assertEquals("resumed-work", controller.activeMigrationWorkId)
        assertEquals(1, resumeCalls)
    }

    @Test
    fun `unreadable checkpoint keeps ui without enqueueing a new worker`() = runTest {
        val controller = controller()
        val unreadable = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
            .copy(checkpointReadFailed = true)

        val result = controller.applyPersistedMigrationSnapshot(unreadable, autoResumeAttempted = false)

        assertTrue(result.preservedUi)
        assertFalse(result.attemptedAutoResume)
        assertNull(controller.activeMigrationWorkId)
    }

    @Test
    fun `failed auto resume consumes one attempt without fabricating a worker`() = runTest {
        var resumeCalls = 0
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot = error("unexpected snapshot read")
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? {
                resumeCalls++
                error("provider unavailable")
            }
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val controller = controller(gateway)
        val durable = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
            .copy(requestAutoResume = true, hasPersistedRequest = true)

        val result = controller.applyPersistedMigrationSnapshot(durable, autoResumeAttempted = false)

        assertTrue(result.preservedUi)
        assertTrue(result.attemptedAutoResume)
        assertNull(controller.activeMigrationWorkId)
        assertEquals(1, resumeCalls)
    }

    @Test
    fun `shared processing receives bounded migration counters from the recovery owner`() = runTest {
        var published: Triple<String, Int, Int>? = null
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot = error("unexpected snapshot read")
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) {
                published = Triple(operationId, processed, total)
            }
        }
        val controller = controller(gateway, liveProgress = progress(2))

        controller.updateSharedProcessingProgress(
            ManagedLibraryProcessingState.Running(
                operationId = "directory-change",
                reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
            )
        )

        assertEquals(Triple("directory-change", 2, 4), published)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `shared processing observer follows operation changes`() = runTest {
        val published = mutableListOf<Triple<String, Int, Int>>()
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot = error("unexpected snapshot read")
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) {
                published += Triple(operationId, processed, total)
            }
        }
        val controller = controller(gateway, liveProgress = progress(2))
        val processing = mutableStateOf<ManagedLibraryProcessingState>(
            ManagedLibraryProcessingState.Running(
                operationId = "first",
                reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
            )
        )
        val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.observeSharedProcessingProgress(processing)
        }
        runCurrent()
        processing.value = ManagedLibraryProcessingState.Running(
            operationId = "second",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        Snapshot.sendApplyNotifications()
        runCurrent()
        observation.cancel()

        assertEquals(listOf(Triple("first", 2, 4), Triple("second", 2, 4)), published)
    }

    @Test
    fun `startup stops after the bounded checkpoint read failures`() = runTest {
        var reads = 0
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot {
                reads++
                error("checkpoint unavailable")
            }
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val controller = controller(gateway)
        controller.beginMigration()

        controller.recoverStartup()

        assertEquals(MIGRATION_SNAPSHOT_READ_RETRY_LIMIT, reads)
        assertFalse(controller.isMigrating)
    }

    @Test
    fun `running worker is observed until terminal durable state clears its ui`() = runTest {
        val running = workInfo(WorkInfo.State.RUNNING)
        val finished = workInfo(WorkInfo.State.SUCCEEDED)
        var workReads = 0
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot =
                snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
            override fun findWorkInfo(workId: String): WorkInfo {
                workReads++
                return if (workReads == 1) running else finished
            }
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val resources = mock(Resources::class.java)
        `when`(resources.getQuantityString(CoreCommonR.plurals.settings_download_directory_migrated, 0, 0))
            .thenReturn("migrated")
        val controller = controller(gateway, resources = resources)
        controller.recordActiveWorkId("active-work")

        controller.watchActiveWork()

        assertEquals(2, workReads)
        assertFalse(controller.isMigrating)
        assertNull(controller.activeMigrationWorkId)
    }

    @Test
    fun `finished worker waits through a transient checkpoint failure before clearing ui`() = runTest {
        var snapshotReads = 0
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot {
                snapshotReads++
                val terminal = snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
                return if (snapshotReads == 1) terminal.copy(checkpointReadFailed = true) else terminal
            }
            override fun findWorkInfo(workId: String): WorkInfo = workInfo(WorkInfo.State.SUCCEEDED)
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val resources = mock(Resources::class.java)
        `when`(resources.getQuantityString(CoreCommonR.plurals.settings_download_directory_migrated, 0, 0))
            .thenReturn("migrated")
        val controller = controller(gateway, resources = resources)
        controller.recordActiveWorkId("finished-work")

        controller.watchActiveWork()

        assertEquals(2, snapshotReads)
        assertFalse(controller.isMigrating)
        assertNull(controller.activeMigrationWorkId)
    }

    @Test
    fun `finished worker adopts a durable resume without erasing its progress`() = runTest {
        val gateway = object : DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot =
                snapshot(activeWorkId = null, state = WorkInfo.State.SUCCEEDED)
                    .copy(requestAutoResume = true, hasPersistedRequest = true)
            override fun findWorkInfo(workId: String): WorkInfo = workInfo(WorkInfo.State.SUCCEEDED)
            override suspend fun resumePersistedRequestIfNeeded(): String = "resumed-work"
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        }
        val controller = controller(gateway)
        controller.recordActiveWorkId("finished-work")

        controller.watchActiveWork()

        assertTrue(controller.isMigrating)
        assertEquals("resumed-work", controller.activeMigrationWorkId)
    }

    private fun snapshot(
        activeWorkId: String?,
        state: WorkInfo.State,
        progress: ManagedDownloadStorage.MigrationProgress? = null
    ) = PersistedMigrationUiSnapshot(
        activeWorkId = activeWorkId,
        activeWorkState = state,
        progress = progress,
        requestAutoResume = false,
        hasPersistedRequest = false,
        journalPhase = null,
        checkpointReadFailed = false
    )

    private fun controller(
        gateway: DownloadDirectoryMigrationRecoveryGateway = object :
            DownloadDirectoryMigrationRecoveryGateway {
            override fun readSnapshot(): PersistedMigrationUiSnapshot = error("unexpected snapshot read")
            override fun findWorkInfo(workId: String): WorkInfo? = error("unexpected work read")
            override suspend fun resumePersistedRequestIfNeeded(): String? = error("unexpected resume")
            override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) = Unit
        },
        liveProgress: ManagedDownloadStorage.MigrationProgress? = null,
        resources: Resources = mock(Resources::class.java)
    ) = DownloadDirectoryMigrationRecoveryController(
        resources = resources,
        ioDispatcher = Dispatchers.Unconfined,
        gateway = gateway,
        onInlineMessageChange = {},
        isMigratingMutableState = mutableStateOf(false),
        liveProgressState = mutableStateOf(liveProgress),
        persistedProgressMutableState = mutableStateOf<ManagedDownloadStorage.MigrationProgress?>(
            null
        ),
        activeWorkIdMutableState = mutableStateOf<String?>(null),
        autoResumeAttemptedMutableState = mutableStateOf(false)
    )

    private fun progress(processed: Int) = ManagedDownloadStorage.MigrationProgress(
        stage = ManagedDownloadStorage.MigrationStage.COPYING,
        totalFiles = 4,
        processedFiles = processed,
        copiedFiles = processed,
        copiedBytes = processed * 10L,
        totalBytes = 40,
        metadataFilesProcessed = 0,
        metadataFilesTotal = 4,
        cleanupFilesProcessed = 0,
        cleanupFilesTotal = 4
    )

    private fun workInfo(state: WorkInfo.State): WorkInfo = mock(WorkInfo::class.java).also {
        `when`(it.state).thenReturn(state)
        `when`(it.progress).thenReturn(Data.EMPTY)
        `when`(it.outputData).thenReturn(Data.EMPTY)
    }
}
