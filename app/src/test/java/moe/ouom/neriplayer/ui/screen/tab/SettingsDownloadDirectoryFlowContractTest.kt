package moe.ouom.neriplayer.ui.screen.tab

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDownloadDirectoryFlowContractTest {
    @Test
    fun `directory preparation immediately gates repeated actions`() {
        val source = settingsSource()
        val enabledGuard = source.substringAfter("val changeEnabled: Boolean")
            .substringBefore("val hasActiveDownloadOperations: Boolean")
        val pickerFlow = pickerFlow(source)
        val resetFlow = resetFlow(source)

        assertTrue(enabledGuard.contains("!isPreparingState.value"))
        assertTrue(pickerFlow.contains("isPreparing = true"))
        assertTrue(resetFlow.contains("isPreparing = true"))
        assertTrue(
            pickerFlow.indexOf("isPreparing = true") <
                pickerFlow.indexOf("scope.launch")
        )
        assertTrue(
            resetFlow.indexOf("isPreparing = true") <
                resetFlow.indexOf("scope.launch")
        )
        assertTrue(source.contains("processingPresentation.showPreparation"))
        assertTrue(source.contains("R.string.settings_download_directory_preparing_desc"))
        assertTrue(source.contains("onCancelPreparation"))
    }

    @Test
    fun `picked grant has one cleanup owner until preparation succeeds`() {
        val source = settingsSource()
        val preparationFlow = controllerFlow(source)
            .substringAfter("suspend fun prepareDirectoryChange(")
            .substringBefore("val directoryContract =")
        val pickerFlow = pickerFlow(source)

        assertFalse(preparationFlow.contains("releasePersistedDirectoryPermission"))
        assertEquals(
            1,
            Regex("ManagedDownloadStorage\\.releasePersistedDirectoryPermission")
                .findAll(pickerFlow)
                .count()
        )
        assertTrue(pickerFlow.contains("permissionWasPersisted && !keepPersistedPermission"))
        assertTrue(pickerFlow.contains("withContext(NonCancellable + Dispatchers.IO)"))
    }

    @Test
    fun `async preparation failures are visible and cancellation is rethrown`() {
        val source = settingsSource()
        val pickerFlow = pickerFlow(source)
        val resetFlow = resetFlow(source)

        assertTrue(pickerFlow.contains("catch (error: CancellationException)"))
        assertTrue(pickerFlow.contains("throw error"))
        assertTrue(pickerFlow.contains("catch (error: Exception)"))
        assertTrue(pickerFlow.contains("showPreparationError(error)"))
        assertTrue(pickerFlow.contains("finally"))
        assertTrue(resetFlow.contains("catch (error: CancellationException)"))
        assertTrue(resetFlow.contains("showPreparationError(error)"))
        assertTrue(resetFlow.contains("finally"))
    }

    @Test
    fun `worker progress restores after the settings process is recreated`() {
        val source = settingsSource()

        assertTrue(source.contains("migrationProgressFromWorkData(work.progress)"))
        assertTrue(
            source.contains(
                "persistedMigrationProgress = migrationProgressFromWorkData(workInfo.progress)"
            )
        )
        assertTrue(
            source.contains(
                "migrationProgressState.value ?: persistedMigrationProgressState.value"
            )
        )
        assertTrue(source.contains("val activeMigrationProgress = controller.migrationProgress"))
        assertTrue(source.contains("persistedMigrationProgress = null"))
        assertTrue(source.contains("ManagedLibraryProcessingDetailsCard("))
        assertTrue(source.contains("val stageText = when (migrationProgress?.stage)"))
        assertTrue(source.contains("val currentFileSummary = migrationProgress?.currentFileName"))
    }

    @Test
    fun `settings reads durable migration checkpoint before clearing finished work`() {
        val source = settingsSource()
        val startup = source.substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("LaunchedEffect(activeMigrationWorkId)")
        val finishedBranch = source.substringAfter("if (workInfo == null || workInfo.state.isFinished)")

        assertTrue(startup.contains("readPersistedMigrationUiSnapshot(context)"))
        assertTrue(startup.contains("applyPersistedMigrationSnapshot"))
        assertTrue(startup.contains("if (snapshot == null)"))
        assertTrue(startup.contains("MIGRATION_CHECKPOINT_RETRY_DELAY_MS"))
        assertTrue(finishedBranch.contains("readPersistedMigrationUiSnapshot(context)"))
        assertTrue(finishedBranch.contains("durableSnapshot.shouldPreserveUi"))
        assertTrue(finishedBranch.contains("if (durableSnapshot == null)"))
        assertTrue(finishedBranch.contains("activeMigrationWorkId == previousWorkId"))
        assertTrue(source.contains("resumePersistedRequestIfNeeded(context)"))
    }

    @Test
    fun `reset probes readable source and bypasses migration only when unavailable`() {
        val resetFlow = resetFlow(settingsSource())

        assertTrue(resetFlow.contains("resolveDownloadDirectoryAvailability("))
        assertTrue(resetFlow.contains("DownloadDirectoryAvailability.Available"))
        assertTrue(resetFlow.contains("prepareDirectoryChange("))
        assertTrue(resetFlow.contains("DownloadDirectoryAvailability.Unavailable"))
        assertTrue(resetFlow.contains("applyDirectoryChange("))
        assertTrue(resetFlow.contains("targetUri = null"))
        assertTrue(resetFlow.contains("is DownloadDirectoryAvailability.ProviderFailure"))
        assertTrue(resetFlow.contains("directoryProbeRetryMessage(resources)"))
        assertFalse(resetFlow.contains("throw availability.error"))
    }

    @Test
    fun `directory preflight has a bounded retryable deadline`() {
        val source = settingsSource()

        assertTrue(source.contains("DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS = 3_000L"))
        assertTrue(source.contains("runDownloadDirectoryPreflight"))
        assertTrue(source.contains("status=retryable"))
        assertTrue(source.contains("RELEASE_PERSISTED_PERMISSION"))
    }

    @Test
    fun `migration skip keeps the selected target and switches without copying`() {
        val source = settingsSource()
        val dialogs = source.substringAfter("private fun DownloadDirectoryDialogs(")

        assertTrue(source.contains("val onSkipPendingChange:"))
        assertTrue(source.contains("onSkipPendingChange = { change ->"))
        assertTrue(source.contains("applyPendingChangeWithoutMigration(change)"))
        assertTrue(dialogs.contains("controller.onSkipPendingChange(change)"))
        assertTrue(dialogs.contains("R.string.settings_download_directory_migrate_skip"))
        assertTrue(source.contains("if (change.targetUri.isNullOrBlank())"))
    }

    @Test
    fun `migration preflight enumerates each root once without reading every sidecar`() {
        val storageSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val presenceProbe = storageSource
            .substringAfter("suspend fun hasMigratableDownloads(")
            .substringBefore("suspend fun migrateManagedDownloads(")

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
        val controller = controllerFlow(source)
        val applyFlow = controller
            .substringAfter("suspend fun applyDirectoryChange(")
            .substringBefore("suspend fun prepareDirectoryChange(")

        assertTrue(
            source.contains(
                "libraryProcessingState.value != ManagedLibraryProcessingState.Idle"
            )
        )
        assertTrue(
            applyFlow.contains(
                "ManagedLibraryProcessingCoordinator.tryBeginExclusive("
            )
        )
        assertTrue(applyFlow.contains("ManagedLibraryProcessingBusyException("))
        assertTrue(
            applyFlow.indexOf("tryBeginExclusive(") <
                applyFlow.indexOf("updateConfiguredTreeUri(")
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

    private fun pickerFlow(source: String): String {
        return controllerFlow(source)
            .substringAfter("val directoryLauncher =")
            .substringBefore("LaunchedEffect(downloadDirectoryUri, defaultDirectorySummary)")
    }

    private fun resetFlow(source: String): String {
        return controllerFlow(source)
            .substringAfter("onResetRequested = {")
            .substringBefore("onDismissSwitchWarning =")
    }

    private fun controllerFlow(source: String): String {
        return source.substringAfter("private fun rememberDownloadDirectorySettingsController(")
            .substringBefore("private fun DownloadDirectoryDialogs(")
    }

    private fun settingsSource(): String {
        return locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsScreen.kt"
        ).readText()
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
