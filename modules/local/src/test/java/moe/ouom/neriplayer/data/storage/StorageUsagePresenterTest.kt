package moe.ouom.neriplayer.data.storage

import android.content.res.Resources
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.storage.DownloadIndexUsageStats
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageMeasurement
import moe.ouom.neriplayer.data.model.storage.StorageUsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class StorageUsagePresenterTest {
    @Test
    fun indexWithRecordsAndFilesUsesCombinedCountDescription() {
        val resources = mockResources()
        `when`(resources.getQuantityString(
            CoreCommonR.plurals.storage_details_download_index_record_and_file_count, 3, 3, 2
        )).thenReturn("3 records, 2 files")
        val measurement = StorageUsageMeasurement(FileStats(100L, 2), "/index", 3)

        val item = StorageUsagePresenter(resources).present(snapshot(StorageUsageItemKind.DownloadIndex, measurement))
            .sections.flatMap { it.items }.single { it.kind == StorageUsageItemKind.DownloadIndex }

        assertEquals("3 records, 2 files", item.countDescription)
        assertNull(item.databaseRecordCount)
        assertEquals(100L, item.sizeBytes)
        assertEquals(2, item.fileCount)
        assertEquals("/index", item.path)
        assertNull(item.cacheKind)
        verify(resources).getQuantityString(
            CoreCommonR.plurals.storage_details_download_index_record_and_file_count, 3, 3, 2
        )
    }

    @Test
    fun indexWithOnlyRecordsUsesRecordCountDescription() {
        val resources = mockResources()
        `when`(resources.getQuantityString(
            CoreCommonR.plurals.storage_details_download_index_record_count, 4, 4
        )).thenReturn("4 records")

        val item = StorageUsagePresenter(resources).present(snapshot(
            StorageUsageItemKind.DownloadIndex, StorageUsageMeasurement(FileStats.Empty, databaseRecordCount = 4)
        )).sections.flatMap { it.items }.single { it.kind == StorageUsageItemKind.DownloadIndex }

        assertEquals("4 records", item.countDescription)
        assertNull(item.databaseRecordCount)
        verify(resources).getQuantityString(CoreCommonR.plurals.storage_details_download_index_record_count, 4, 4)
    }

    @Test
    fun absentIndexRecordCountDoesNotCreateDescription() {
        val item = StorageUsagePresenter(mockResources()).present(snapshot(
            StorageUsageItemKind.DownloadIndex, StorageUsageMeasurement(FileStats(20L, 1))
        )).sections.flatMap { it.items }.single { it.kind == StorageUsageItemKind.DownloadIndex }

        assertNull(item.countDescription)
        assertNull(item.databaseRecordCount)
        assertEquals(1, item.fileCount)
    }

    @Test
    fun zeroAndNegativeIndexRecordCountsDoNotRequestPluralResources() {
        val resources = mock(Resources::class.java)

        assertNull(downloadIndexCountDescription(resources, DownloadIndexUsageStats(20L, 1, 0)))
        assertNull(downloadIndexCountDescription(resources, DownloadIndexUsageStats(20L, 1, -1)))

        verifyNoInteractions(resources)
    }

    @Test
    fun otherItemsKeepDatabaseCountAndCacheClassification() {
        val item = StorageUsagePresenter(mockResources()).present(snapshot(
            StorageUsageItemKind.NeteasePlaylistCache,
            StorageUsageMeasurement(FileStats(30L, 2), "/playlist", 5)
        )).sections.flatMap { it.items }.single { it.kind == StorageUsageItemKind.NeteasePlaylistCache }

        assertEquals(5, item.databaseRecordCount)
        assertNull(item.countDescription)
        assertEquals(StorageCacheKind.NeteasePlaylist, item.cacheKind)
        assertEquals("/playlist", item.path)
        assertEquals(30L, item.sizeBytes)
        assertEquals(2, item.fileCount)
        assertEquals("resource-${CoreCommonR.string.storage_type_netease_playlist_cache}", item.title)
        assertEquals("resource-${CoreCommonR.string.storage_desc_netease_playlist_cache}", item.description)
    }

    private fun mockResources(): Resources = mock(Resources::class.java).apply {
        `when`(getString(anyInt())).thenAnswer { "resource-${it.arguments[0]}" }
    }

    private fun snapshot(kind: StorageUsageItemKind, measurement: StorageUsageMeasurement) = StorageUsageSnapshot(
        StorageUsageItemKind.entries.associateWith { StorageUsageMeasurement(FileStats.Empty) } + (kind to measurement)
    )
}
