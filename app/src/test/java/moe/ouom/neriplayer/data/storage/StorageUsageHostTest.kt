package moe.ouom.neriplayer.data.storage

import moe.ouom.neriplayer.data.storage.model.FileStats
import moe.ouom.neriplayer.data.storage.model.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.storage.model.StorageCacheKind
import moe.ouom.neriplayer.data.storage.model.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.storage.model.StorageLibraryEntry
import moe.ouom.neriplayer.data.storage.model.StoragePlatformCacheStats
import moe.ouom.neriplayer.data.storage.scan.StorageUsageScanner
import moe.ouom.neriplayer.data.storage.source.StorageLocations
import moe.ouom.neriplayer.data.storage.source.StorageUsageSource

import android.content.Context
import android.content.res.Resources
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class StorageUsageHostTest {
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

    @Test
    fun databaseAdaptersPreserveRecordCountsAndAllocatedPages() {
        assertEquals(
            StorageDownloadIndexStats(118, 8192),
            DownloadIndexStorageStats(118, 8192).toStorageUsageStats()
        )
        assertEquals(
            StoragePlatformCacheStats(3, 4096),
            PlatformPlaylistCacheStorageStats(3, 4096).toStorageUsageStats()
        )
    }

    @Test
    fun downloadedEntryAdapterPreservesIdentityAndAccountingFields() {
        val entry = storedEntry("directory", 12).copy(
            reference = "reference",
            mediaUri = "media",
            localFilePath = "/downloads/directory",
            isDirectory = true
        )

        assertEquals(
            StorageLibraryEntry("directory", "reference", "media", "/downloads/directory", 12, true),
            entry.toStorageLibraryEntry()
        )
    }

    @Test
    fun downloadSnapshotAdapterMapsEverySidecarCollection() {
        val audio = storedEntry("song.m4a", 100).copy(localFilePath = "/downloads/song.m4a")
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = mapOf("song.m4a" to storedEntry("song.meta.json", 7)),
            metadataByAudioName = emptyMap(),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = mapOf("song.jpg" to storedEntry("song.jpg", 11)),
            lyricEntriesByName = mapOf("song.lrc" to storedEntry("song.lrc", 13)),
            knownReferences = emptySet()
        )

        val usage = snapshot.toManagedDownloadLibraryUsage()

        assertEquals(FileStats(100, 1), usage.audioFiles)
        assertEquals(FileStats(13, 1), usage.lyricFiles)
        assertEquals(FileStats(11, 1), usage.coverFiles)
        assertEquals(FileStats(7, 1), usage.metadataFiles)
        assertEquals(listOf(File("/downloads/song.m4a")), usage.localFiles)
    }

    private fun storedEntry(name: String, sizeBytes: Long) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "content://downloads/$name",
        mediaUri = "content://downloads/$name",
        localFilePath = null,
        sizeBytes = sizeBytes,
        lastModifiedMs = 0
    )

    private fun write(directory: File, name: String, bytes: Int): File {
        directory.mkdirs()
        return File(directory, name).apply { writeBytes(ByteArray(bytes)) }
    }
}
