package moe.ouom.neriplayer.ui.screen.tab

import java.io.File
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
        assertTrue(source.contains("if (controller.isPreparing)"))
        assertTrue(source.contains("R.string.settings_download_directory_preparing_desc"))
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

        assertTrue(
            Regex("persistedMigrationProgress = migrationProgressFromWorkData")
                .findAll(source)
                .count() >= 2
        )
        assertTrue(
            source.contains(
                "migrationProgressState.value ?: persistedMigrationProgressState.value"
            )
        )
        assertTrue(source.contains("val activeMigrationProgress = controller.migrationProgress"))
        assertTrue(source.contains("persistedMigrationProgress = null"))
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
