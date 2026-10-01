package moe.ouom.neriplayer.data.local.storage.host

import moe.ouom.neriplayer.data.local.storage.presentation.StorageUsagePresenter
import android.content.Context
import android.content.res.Resources
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats
import moe.ouom.neriplayer.data.local.storage.scan.StorageUsageScanner
import moe.ouom.neriplayer.data.local.storage.source.StorageLocations
import moe.ouom.neriplayer.data.local.storage.source.StorageUsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class StorageUsageHostAdapterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun presenterUsesCapturedSnapshotAfterFilesChange() = runBlocking {
        val root = temporary.newFolder()
        val locations = StorageLocations(
            filesDir = File(root, "files").apply { mkdirs() },
            cacheDir = File(root, "cache").apply { mkdirs() },
            diagnosticsDir = File(root, "external").apply { mkdirs() },
            databaseFiles = emptyList(),
            downloadMetadataFiles = emptyList()
        )
        val cached = write(locations.mediaCacheDir, "audio", 20)
        val source = object : StorageUsageSource {
            override suspend fun downloadLibraryUsage() = ManagedDownloadLibraryUsage.Empty
            override suspend fun platformCacheStats() = emptyMap<String, StoragePlatformCacheStats>()
            override suspend fun downloadIndexStats() = StorageDownloadIndexStats.Empty
        }
        val snapshot = StorageUsageScanner(locations, source).scan()
        assertTrue(cached.delete())
        val resources = mock(Resources::class.java)
        `when`(resources.getString(anyInt())).thenAnswer { "resource-${it.arguments[0]}" }

        val summary = StorageUsagePresenter(resources).present(snapshot)

        assertEquals(3, summary.sections.size)
        assertEquals(21, summary.sections.sumOf { it.items.size })
        assertEquals(20L, summary.cleanableSizeBytes)
        assertEquals(20L, summary.totalSizeBytes)
        assertEquals(1, summary.totalFileCount)
    }

    @Test
    fun androidFileAdapterDeletesAndRecreatesOrdinaryCacheDirectory() {
        val directory = temporary.newFolder()
        write(directory, "cache", 7)
        val adapter = AndroidStorageCacheFiles(mock(Context::class.java))

        assertEquals(FileStats(7, 1), adapter.stats(directory))
        assertTrue(adapter.clear(directory, StorageCacheKind.SharedMedia))
        assertTrue(directory.isDirectory)
        assertEquals(FileStats.Empty, adapter.stats(directory))
    }

    private fun write(directory: File, name: String, bytes: Int): File {
        directory.mkdirs()
        return File(directory, name).apply { writeBytes(ByteArray(bytes)) }
    }
}
