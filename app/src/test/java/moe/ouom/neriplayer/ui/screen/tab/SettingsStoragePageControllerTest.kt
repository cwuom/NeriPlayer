package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.storage.StorageUsageSection
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoragePageControllerTest {
    @Test
    fun `shared processing details appear only on the storage page`() {
        val shared = DownloadDirectoryProcessingPresentation(false, false, true)
        val local = DownloadDirectoryProcessingPresentation(false, true, false)

        assertTrue(shouldShowSharedDownloadDirectoryProcessing(SettingsPage.Storage, shared))
        assertFalse(shouldShowSharedDownloadDirectoryProcessing(SettingsPage.Downloads, shared))
        assertFalse(shouldShowSharedDownloadDirectoryProcessing(SettingsPage.Storage, local))
    }

    @Test
    fun `storage page availability follows directory and active operation independently`() {
        assertEquals(
            SettingsStoragePageAvailability(false, true),
            settingsStoragePageAvailability(null, false)
        )
        assertEquals(
            SettingsStoragePageAvailability(false, false),
            settingsStoragePageAvailability("", true)
        )
        assertEquals(
            SettingsStoragePageAvailability(true, true),
            settingsStoragePageAvailability("content://selected", false)
        )
        assertEquals(
            SettingsStoragePageAvailability(true, false),
            settingsStoragePageAvailability("content://selected", true)
        )
    }

    @Test
    fun `cache choices keep the original safe defaults and independent toggles`() {
        val selection = SettingsStorageSelectionState()

        assertFalse(selection.showClearCacheDialog)
        assertTrue(selection.clearAudioCache)
        assertTrue(selection.clearImageCache)
        assertFalse(selection.clearDownloadStagingCache)
        assertFalse(selection.clearSharedMediaCache)
        assertFalse(selection.clearLyricsCache)
        assertFalse(selection.clearNeteasePlaylistCache)
        assertFalse(selection.clearBiliFavoriteCache)
        assertFalse(selection.clearBiliArchiveCache)
        assertFalse(selection.clearYoutubePlaylistCache)
        assertFalse(selection.clearLogFiles)
        assertFalse(selection.clearCrashLogs)

        selection.clearDownloadStagingCache = true
        assertTrue(selection.clearDownloadStagingCache)
        assertFalse(selection.clearSharedMediaCache)
    }

    @Test
    fun `storage scan only starts after a request and publishes its result`() = runTest {
        val request = mutableIntStateOf(0)
        val details = mutableStateOf(StorageUsageSummary.Empty)
        val loading = mutableStateOf(false)
        val scanned = StorageUsageSummary(listOf(StorageUsageSection("cache", emptyList())))
        var scanCount = 0
        lateinit var controller: SettingsStorageDetailsController
        controller = SettingsStorageDetailsController(details, loading, request) {
            scanCount++
            assertTrue(controller.isScanning)
            scanned
        }

        controller.scanRequested()
        assertEquals(0, scanCount)
        controller.requestRefresh()
        controller.scanRequested()

        assertEquals(1, request.intValue)
        assertEquals(1, scanCount)
        assertEquals(scanned, controller.details)
        assertFalse(controller.isScanning)
    }

    @Test
    fun `storage refresh ignores requests while scanning and clears loading on failure`() = runTest {
        val request = mutableIntStateOf(0)
        val loading = mutableStateOf(false)
        lateinit var controller: SettingsStorageDetailsController
        controller = SettingsStorageDetailsController(
            mutableStateOf(StorageUsageSummary.Empty), loading, request
        ) {
            assertTrue(controller.isScanning)
            controller.requestRefresh()
            assertEquals(1, request.intValue)
            throw IllegalStateException("scan failed")
        }

        controller.requestRefresh()
        val failure = runCatching { controller.scanRequested() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, request.intValue)
        assertFalse(controller.isScanning)
    }

    @Test
    fun `opening empty storage details requests one scan but other pages do not`() {
        val request = mutableIntStateOf(0)
        val details = mutableStateOf(StorageUsageSummary.Empty)
        val controller = SettingsStorageDetailsController(
            details, mutableStateOf(false), request
        ) { StorageUsageSummary.Empty }

        controller.requestIfDetailsPage(SettingsPage.Storage)
        assertEquals(0, request.intValue)
        controller.requestIfDetailsPage(SettingsPage.StorageCacheDetails)
        assertEquals(1, request.intValue)
        details.value = StorageUsageSummary(listOf(StorageUsageSection("cache", emptyList())))
        controller.requestIfDetailsPage(SettingsPage.StorageCacheDetails)
        assertEquals(1, request.intValue)
    }

}
