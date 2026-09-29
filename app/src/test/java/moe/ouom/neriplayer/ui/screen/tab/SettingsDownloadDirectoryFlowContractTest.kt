package moe.ouom.neriplayer.ui.screen.tab

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.PendingDownloadDirectoryChange
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.isDownloadDirectoryChangeEnabled
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DirectoryChangeBlockReason
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.directoryChangeBlockReason
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.shouldReleaseBlockedDirectoryGrant
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.resolveDownloadDirectoryProcessingPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.shouldAttemptMigrationAutoResume
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.shouldRetryMigrationSnapshotRead
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.shouldStopMigrationRecoveryAfterNoProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDownloadDirectoryFlowContractTest {
    @Test
    fun `directory preparation immediately gates repeated actions`() {
        val source = settingsSource()
        val enabledGuard = source.substringAfter("override val changeEnabled: Boolean")
            .substringBefore("override val hasActiveDownloadOperations: Boolean")
        val pickerFlow = selectionOwnerSource()
        val resetFlow = resetFlow()

        assertTrue(enabledGuard.contains("isDownloadDirectoryChangeEnabled("))
        assertTrue(pickerFlow.contains("isPreparingState.value = true"))
        assertTrue(resetFlow.contains("isPreparingState.value = true"))
        assertTrue(
            pickerFlow.indexOf("isPreparingState.value = true") <
                pickerFlow.indexOf("scope.launch")
        )
        assertTrue(
            resetFlow.indexOf("isPreparingState.value = true") <
                resetFlow.indexOf("scope.launch")
        )
        assertTrue(source.contains("processingPresentation.showPreparation"))
        assertTrue(source.contains("R.string.settings_download_directory_preparing_desc"))
        assertTrue(source.contains("onCancelPreparation"))
    }

    @Test
    fun `picked grant has one cleanup owner until preparation succeeds`() {
        val preparationFlow = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/directory/operation/SettingsDownloadDirectoryPreparationOwner.kt"
        ).readText()
        val pickerFlow = selectionOwnerSource()

        assertFalse(preparationFlow.contains("releasePersistedDirectoryPermission"))
        assertEquals(
            1,
            Regex("ManagedDownloadStorage\\.releasePersistedDirectoryPermission")
                .findAll(pickerFlow)
                .count()
        )
        assertTrue(pickerFlow.contains("grantPersisted && !keepGrant"))
        assertTrue(pickerFlow.contains("withContext(NonCancellable + Dispatchers.IO)"))
    }

    @Test
    fun `async preparation failures are visible and cancellation is rethrown`() {
        val source = settingsSource()
        val pickerFlow = selectionOwnerSource()
        val resetFlow = resetFlow()

        assertTrue(pickerFlow.contains("if (error is CancellationException) throw error"))
        assertTrue(pickerFlow.contains("throw error"))
        assertTrue(pickerFlow.contains("catch (error: Exception)"))
        assertTrue(pickerFlow.contains("onError(error)"))
        assertTrue(pickerFlow.contains("finally"))
        assertTrue(resetFlow.contains("catch (error: CancellationException)"))
        assertTrue(resetFlow.contains("onError(error)"))
        assertTrue(resetFlow.contains("finally"))
    }

    @Test
    fun `worker progress restores after the settings process is recreated`() {
        val source = settingsSource()

        assertTrue(source.contains("mergeMigrationUiProgress(progress, activeWork)"))
        assertTrue(source.contains("migrationProgressFromWorkData(it.progress)"))
        assertTrue(
            source.contains(
                "persistedMigrationProgress = migrationProgressFromWorkData(workInfo.progress)"
            )
        )
        assertTrue(
            source.contains("liveProgressState.value ?: persistedProgressMutableState.value")
        )
        assertTrue(source.contains("downloadDirectoryMigrationDialogPresentation(controller.migrationProgress)"))
        assertTrue(source.contains("persistedMigrationProgress = null"))
        assertTrue(source.contains("ManagedLibraryProcessingDetailsCard("))
        assertTrue(source.contains("managedLibraryProcessingStageId(migrationProgress?.stage, state.phase)"))
        assertTrue(source.contains("ManagedLibraryProcessingCurrentFile(presentation.currentFileName)"))
    }

    @Test
    fun `settings reads durable migration checkpoint before clearing finished work`() {
        val source = settingsSource()
        val startup = source.substringAfter("suspend fun recoverStartup() {")
            .substringBefore("suspend fun watchActiveWork() {")
        val finishedBranch = source.substringAfter("if (workInfo == null || workInfo.state.isFinished)")

        assertTrue(startup.contains("readSnapshot("))
        assertTrue(startup.contains("applyPersistedMigrationSnapshot"))
        assertTrue(startup.contains("tracker.canContinue(snapshot)"))
        assertTrue(startup.contains("MIGRATION_CHECKPOINT_RETRY_DELAY_MS"))
        assertTrue(source.contains("runCatching { gateway.readSnapshot() }"))
        assertTrue(finishedBranch.contains("readSnapshot(\"迁移任务结束后读取持久 checkpoint 失败\")"))
        assertTrue(source.contains("readPersistedMigrationUiSnapshot(context)"))
        assertTrue(finishedBranch.contains("durableSnapshot.shouldPreserveUi"))
        assertTrue(finishedBranch.contains("if (durableSnapshot == null)"))
        assertTrue(finishedBranch.contains("activeMigrationWorkId == previousWorkId"))
        assertTrue(source.contains("resumePersistedRequestIfNeeded(context)"))
    }

    @Test
    fun `terminal migration snapshot clears stale progress before restoring ui`() {
        val source = settingsSource()
        val apply = source.substringAfter("suspend fun applyPersistedMigrationSnapshot(")
            .substringBefore("suspend fun recoverStartup() {")
        val preserveCheck = apply.indexOf("if (!snapshot.shouldPreserveUi)")
        val clearUi = apply.indexOf("clearPersistedMigrationUi()")
        val restoreProgress = apply.indexOf("val restoredProgress = restoredMigrationProgress(")

        assertTrue(preserveCheck >= 0)
        assertTrue(clearUi > preserveCheck)
        assertTrue(restoreProgress > clearUi)
        assertTrue(source.contains("persistedMigrationProgress = null"))
        assertTrue(source.contains("isMigrating = false"))
        assertTrue(source.contains("activeMigrationWorkId = null"))
    }

    @Test
    fun `migration snapshot recovery has bounded reads and one auto resume attempt`() {
        assertTrue(shouldRetryMigrationSnapshotRead(consecutiveFailures = 0))
        assertTrue(shouldRetryMigrationSnapshotRead(consecutiveFailures = 2))
        assertFalse(shouldRetryMigrationSnapshotRead(consecutiveFailures = 3))

        assertTrue(
            shouldAttemptMigrationAutoResume(
                shouldResume = true,
                autoResumeAttempted = false
            )
        )
        assertFalse(
            shouldAttemptMigrationAutoResume(
                shouldResume = true,
                autoResumeAttempted = true
            )
        )
        assertFalse(
            shouldAttemptMigrationAutoResume(
                shouldResume = false,
                autoResumeAttempted = false
            )
        )

        assertTrue(
            shouldStopMigrationRecoveryAfterNoProgress(
                shouldPreserveUi = true,
                needsRecovery = true,
                snapshotChanged = false,
                autoResumeAttempted = true
            )
        )
        assertFalse(
            shouldStopMigrationRecoveryAfterNoProgress(
                shouldPreserveUi = true,
                needsRecovery = true,
                snapshotChanged = true,
                autoResumeAttempted = true
            )
        )
        assertFalse(
            shouldStopMigrationRecoveryAfterNoProgress(
                shouldPreserveUi = true,
                needsRecovery = true,
                snapshotChanged = false,
                autoResumeAttempted = false
            )
        )
    }

    @Test
    fun `settings migration effects clear only local ui after recovery budget is exhausted`() {
        val source = settingsSource()
        val controller = migrationRecoverySource()

        assertTrue(source.contains("MIGRATION_SNAPSHOT_READ_RETRY_LIMIT = 3"))
        assertTrue(controller.contains("private var consecutiveFailures = 0"))
        assertTrue(controller.contains("tracker.canContinue(snapshot)"))
        assertTrue(controller.contains("autoResumeAttemptedState"))
        assertTrue(controller.contains("clearPersistedMigrationUi()"))
        assertTrue(controller.contains("保留 checkpoint 和 journal"))
        assertTrue(controller.contains("shouldStopMigrationRecoveryAfterNoProgress("))
        assertTrue(controller.contains("migrationAutoResumeAttempted ||"))
        assertTrue(controller.contains("autoResumeAttempted = migrationAutoResumeAttempted"))
    }

    @Test
    fun `reset probes readable source and bypasses migration only when unavailable`() {
        val resetFlow = resetFlow()

        assertTrue(resetFlow.contains("resolveDownloadDirectoryAvailability("))
        assertTrue(resetFlow.contains("DownloadDirectoryAvailability.Available"))
        assertTrue(resetFlow.contains("actions.prepareDefault("))
        assertTrue(resetFlow.contains("DownloadDirectoryAvailability.Unavailable"))
        assertTrue(resetFlow.contains("actions.applyDefault("))
        assertTrue(settingsSource().contains("applyOwner.apply(null, targetSummary, previousUri, true)"))
        assertTrue(resetFlow.contains("is DownloadDirectoryAvailability.ProviderFailure"))
        assertTrue(resetFlow.contains("R.string.managed_library_processing_retry"))
        assertFalse(resetFlow.contains("throw availability.error"))
    }

    @Test
    fun `directory preflight has a bounded retryable deadline`() {
        val source = settingsSource()
        val preflight = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/directory/SettingsDownloadDirectoryPreflight.kt"
        ).readText()

        assertTrue(preflight.contains("DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS = 3_000L"))
        assertTrue(preflight.contains("runDownloadDirectoryPreflight"))
        assertTrue(source.contains("runDownloadDirectoryPreflight"))
        assertTrue(source.contains("status=retryable"))
        assertTrue(source.contains("RELEASE_PERSISTED_PERMISSION"))
    }

    @Test
    fun `migration skip keeps the selected target and switches without copying`() {
        val source = settingsSource()
        val dialogs = source.substringAfter("internal fun DownloadDirectoryDialogs(")

        assertTrue(source.contains("val onSkipPendingChange:"))
        assertTrue(source.contains("onSkipPendingChange = pendingOwner::skip"))
        assertTrue(pendingOwnerSource().contains("actions.applyWithoutMigration(change)"))
        assertTrue(dialogs.contains("controller.onSkipPendingChange(change)"))
        assertTrue(dialogs.contains("R.string.settings_download_directory_migrate_skip"))
        assertTrue(source.contains("!change.targetUri.isNullOrBlank()"))
    }

    @Test
    fun `migration cancel keeps the current directory and releases only the new grant`() {
        val source = settingsSource()
        val dialogs = source.substringAfter("internal fun DownloadDirectoryDialogs(")
        val cancelFlow = pendingOwnerSource()
            .substringAfter("fun cancel(change: PendingDownloadDirectoryChange)")
            .substringBefore("fun skip(change: PendingDownloadDirectoryChange)")

        assertTrue(source.contains("val onCancelPendingChange:"))
        assertTrue(dialogs.contains("controller.onCancelPendingChange(change)"))
        assertTrue(
            dialogs.contains(
                "R.string.settings_download_directory_migrate_cancel"
            )
        )
        assertTrue(cancelFlow.contains("pendingChangeState.value = null"))
        assertTrue(cancelFlow.contains("gateway.releaseTargetGrant(change.targetUri)"))
        assertFalse(cancelFlow.contains("actions.applyWithoutMigration("))
    }

    @Test
    fun `private target does not show the external folder conflict warning`() {
        val source = settingsSource()

        assertTrue(source.contains("targetNonEmpty && !targetUri.isNullOrBlank()"))
        assertTrue(source.contains("change.shouldShowTargetConflictWarning"))
    }

    @Test
    fun `pending change releases only a distinct previous grant and warns for a populated custom target`() {
        val previous = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val equivalent = previous + "/document/primary%3AMusic%2FNeriPlayer"
        val different = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FNeriPlayer"
        val pending = PendingDownloadDirectoryChange(previous, different, "Downloads", true, true)

        assertTrue(pending.shouldReleasePreviousPermission)
        assertTrue(pending.shouldShowTargetConflictWarning)
        assertFalse(pending.copy(targetUri = equivalent).shouldReleasePreviousPermission)
        assertFalse(pending.copy(targetUri = null).shouldShowTargetConflictWarning)
        assertFalse(pending.copy(previousUri = null).shouldReleasePreviousPermission)
        assertFalse(pending.copy(targetNonEmpty = false).shouldShowTargetConflictWarning)
    }

    @Test
    fun `migration preflight enumerates each root once without reading every sidecar`() {
        val storageSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/facade/" +
                "ManagedDownloadStorageFacadeSetup.kt"
        ).readText()
        val presenceProbe = storageSource
            .substringAfter("internal suspend fun ManagedDownloadStorage.hasMigratableDownloadsImpl(")
            .substringBefore("internal suspend fun ManagedDownloadStorage.hasActualDirectoryEntriesImpl(")

        assertEquals(
            1,
            Regex("refreshManagedMigrationEntries").findAll(presenceProbe).count()
        )
        assertTrue(presenceProbe.contains("hasAnyManagedEntry("))
        assertTrue(presenceProbe.contains("requiresSidecarEntries ="))
        assertFalse(presenceProbe.contains("collectManagedMigrationEntries("))
        assertFalse(presenceProbe.contains("parseDownloadedAudioMetadata("))
        assertFalse(presenceProbe.contains("readManagedLibraryIdBlocking("))
        assertFalse(presenceProbe.contains("readText"))
    }

    @Test
    fun `populated migration target shows localized conflict semantics`() {
        val source = settingsSource()
        val localizedResources = listOf(
            "app/src/main/res/values/strings_settings_general.xml",
            "app/src/main/res/values-zh/strings_settings_general.xml",
            "app/src/main/res/values-en/strings_settings_general.xml"
        ).map { path -> locateProjectFile(path).readText() }

        assertTrue(source.contains("targetNonEmpty"))
        assertTrue(source.contains("hasActualDirectoryEntries(context, targetUri)"))
        assertTrue(
            source.contains("settings_download_directory_migrate_conflict_warning")
        )
        localizedResources.forEach { resources ->
            assertTrue(
                resources.contains(
                    "<string name=\"settings_download_directory_migrate_conflict_warning\">"
                )
            )
            assertTrue(
                resources.contains("同曲") ||
                    resources.contains("matching tracks", ignoreCase = true)
            )
        }
    }

    @Test
    fun `directory mutation requires the shared exclusive processing lease`() {
        val source = settingsSource()
        val applyFlow = source
            .substringAfter("internal class DownloadDirectoryApplyOwner(")
            .substringBefore("internal fun rememberDownloadDirectorySettingsController(")

        assertTrue(
            source.contains(
                "libraryProcessing != ManagedLibraryProcessingState.Idle"
            )
        )
        assertTrue(
            applyFlow.contains(
                "gateway.tryBeginExclusive()"
            )
        )
        assertTrue(applyFlow.contains("ManagedLibraryProcessingBusyException("))
        assertTrue(
            applyFlow.indexOf("tryBeginExclusive(") <
                applyFlow.indexOf("gateway.configure(")
        )
    }

    @Test
    fun `generic retry copy does not claim the readable directory is unavailable`() {
        val localizedResources = listOf(
            "app/src/main/res/values/strings_settings_general.xml",
            "app/src/main/res/values-zh/strings_settings_general.xml",
            "app/src/main/res/values-en/strings_settings_general.xml"
        ).map { path -> locateProjectFile(path).readText() }

        localizedResources.forEach { resources ->
            val retryCopy = resources
                .substringAfter("<string name=\"managed_library_processing_retry\">")
                .substringBefore("</string>")
            assertFalse(retryCopy.contains("无法读取"))
            assertFalse(retryCopy.contains("temporarily unavailable", ignoreCase = true))
            assertTrue(
                retryCopy.contains("自动重试") ||
                    retryCopy.contains("retry automatically", ignoreCase = true)
            )
        }
    }

    @Test
    fun `shared processing state suppresses duplicate local dialogs`() {
        val state = ManagedLibraryProcessingState.Running(
            operationId = "operation",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

        val presentation = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = true,
            isMigrating = true,
            processingState = state,
            migrationProgress = null
        )

        assertFalse(presentation.showPreparation)
        assertFalse(presentation.showMigration)
        assertTrue(presentation.usesSharedProcessing)
    }

    @Test
    fun `directory actions are enabled only while all storage owners are idle`() {
        val idle = ManagedLibraryProcessingState.Idle
        val busy = ManagedLibraryProcessingState.Running(
            operationId = "storage",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

        assertTrue(isDownloadDirectoryChangeEnabled(false, false, false, idle))
        assertFalse(isDownloadDirectoryChangeEnabled(true, false, false, idle))
        assertFalse(isDownloadDirectoryChangeEnabled(false, true, false, idle))
        assertFalse(isDownloadDirectoryChangeEnabled(false, false, true, idle))
        assertFalse(isDownloadDirectoryChangeEnabled(false, false, false, busy))
    }

    @Test
    fun `directory guard prioritizes shared processing then preparation before downloads`() {
        val busy = ManagedLibraryProcessingState.Running(
            operationId = "storage",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        var downloadChecks = 0
        val activeDownloads = { downloadChecks++; true }

        assertEquals(
            DirectoryChangeBlockReason.LIBRARY_PROCESSING,
            directoryChangeBlockReason(false, false, false, busy, activeDownloads)
        )
        assertEquals(
            DirectoryChangeBlockReason.PREPARATION_OR_MIGRATION,
            directoryChangeBlockReason(
                true,
                false,
                false,
                ManagedLibraryProcessingState.Idle,
                activeDownloads
            )
        )
        assertEquals(
            DirectoryChangeBlockReason.PREPARATION_OR_MIGRATION,
            directoryChangeBlockReason(
                false,
                false,
                true,
                ManagedLibraryProcessingState.Idle,
                activeDownloads
            )
        )
        assertEquals(0, downloadChecks)
        assertEquals(
            DirectoryChangeBlockReason.ACTIVE_DOWNLOADS,
            directoryChangeBlockReason(
                true,
                true,
                false,
                ManagedLibraryProcessingState.Idle,
                activeDownloads
            )
        )
        assertEquals(
            DirectoryChangeBlockReason.NONE,
            directoryChangeBlockReason(
                false,
                false,
                false,
                ManagedLibraryProcessingState.Idle
            ) { false }
        )
    }

    @Test
    fun `blocked picker releases only a newly acquired distinct permission`() {
        val previous = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val equivalent = previous + "/document/primary%3AMusic%2FNeriPlayer"
        val different = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FNeriPlayer"

        assertTrue(shouldReleaseBlockedDirectoryGrant(previous, different, true))
        assertFalse(shouldReleaseBlockedDirectoryGrant(previous, equivalent, true))
        assertFalse(shouldReleaseBlockedDirectoryGrant(previous, null, true))
        assertFalse(shouldReleaseBlockedDirectoryGrant(previous, different, false))
    }

    @Test
    fun `local migration dialog is visible only before shared worker starts`() {
        val presentation = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = false,
            isMigrating = true,
            processingState = ManagedLibraryProcessingState.Idle,
            migrationProgress = null
        )

        assertFalse(presentation.showPreparation)
        assertTrue(presentation.showMigration)
        assertFalse(presentation.usesSharedProcessing)
    }

    @Test
    fun `preparation dialog remains available while probes are running`() {
        val presentation = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = true,
            isMigrating = false,
            processingState = ManagedLibraryProcessingState.Idle,
            migrationProgress = null
        )

        assertTrue(presentation.showPreparation)
        assertFalse(presentation.showMigration)
    }

    @Test
    fun `durable migration progress suppresses preparation before shared processing starts`() {
        val progress = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 1,
            processedFiles = 0,
            copiedFiles = 0,
            copiedBytes = 0,
            totalBytes = 0,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 1,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 1
        )
        val presentation = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = true,
            isMigrating = false,
            processingState = ManagedLibraryProcessingState.Idle,
            migrationProgress = progress
        )

        assertFalse(presentation.showPreparation)
        assertTrue(presentation.showMigration)
    }

    @Test
    fun `idle and persisted migration progress select distinct dialog states`() {
        val idle = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = false,
            isMigrating = false,
            processingState = ManagedLibraryProcessingState.Idle,
            migrationProgress = null
        )
        assertFalse(idle.showPreparation)
        assertFalse(idle.showMigration)

        val progress = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = false,
            isMigrating = false,
            processingState = ManagedLibraryProcessingState.Idle,
            migrationProgress = ManagedDownloadStorage.MigrationProgress(
                stage = ManagedDownloadStorage.MigrationStage.COPYING,
                totalFiles = 1,
                processedFiles = 0,
                copiedFiles = 0,
                copiedBytes = 0,
                totalBytes = 0,
                metadataFilesProcessed = 0,
                metadataFilesTotal = 1,
                cleanupFilesProcessed = 0,
                cleanupFilesTotal = 1
            )
        )
        assertFalse(progress.showPreparation)
        assertTrue(progress.showMigration)
    }

    @Test
    fun `directory summary probe is bounded and migration progress feeds shared counts`() {
        val source = settingsSource()

        assertTrue(
            source.contains(
                "ManagedDownloadStorage.describeConfiguredDirectory(context, targetUri)"
            )
        )
        assertTrue(source.contains("directoryProbeTimeoutFailure("))
        assertTrue(source.contains("ManagedLibraryProcessingCoordinator.updateProgress("))
        assertTrue(source.contains("progress.processedFiles.coerceAtLeast(0)"))
        assertTrue(source.contains("progress.totalFiles.coerceAtLeast(0)"))
    }

    private fun selectionOwnerSource(): String = locateProjectFile(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/directory/operation/SettingsDownloadDirectorySelectionOwner.kt"
    ).readText()

    private fun pendingOwnerSource(): String = locateProjectFile(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/directory/operation/SettingsDownloadDirectoryPendingOwner.kt"
    ).readText()

    private fun resetFlow(): String = locateProjectFile(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/directory/operation/SettingsDownloadDirectoryResetOwner.kt"
    ).readText()

    private fun settingsSource(): String {
        return listOf(
            "settings/download/migration/SettingsDownloadDirectoryMigrationSnapshot.kt",
            "settings/download/migration/SettingsDownloadDirectoryMigrationRecovery.kt",
            "settings/download/directory/operation/SettingsDownloadDirectoryApplyOwner.kt",
            "settings/download/directory/operation/SettingsDownloadDirectoryPreparationOwner.kt",
            "settings/download/directory/operation/SettingsDownloadDirectorySelectionOwner.kt",
            "settings/download/directory/operation/SettingsDownloadDirectoryResetOwner.kt",
            "settings/download/directory/operation/SettingsDownloadDirectoryGuardOwner.kt",
            "settings/download/directory/operation/SettingsDownloadDirectoryPendingOwner.kt",
            "settings/download/directory/SettingsDownloadDirectorySwitchOwner.kt",
            "settings/download/directory/SettingsDownloadDirectoryStateSync.kt",
            "settings/download/directory/SettingsDownloadDirectoryActions.kt",
            "settings/download/directory/SettingsDownloadDirectoryController.kt",
            "settings/SettingsScreen.kt",
            "settings/download/directory/SettingsDownloadDirectoryPresentation.kt"
        ).joinToString("\n") { relativePath ->
            locateProjectFile("app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/$relativePath").readText()
        }
    }

    private fun migrationRecoverySource(): String = locateProjectFile(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/download/migration/SettingsDownloadDirectoryMigrationRecovery.kt"
    ).readText()

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
