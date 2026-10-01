package moe.ouom.neriplayer.data.local.storage.host

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class StorageUsageAndroidTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun logCleanupUsesLoggerDirectoryRatherThanSuppliedDirectory() {
        val external = temporary.newFolder()
        val context = mockContext(external = external)
        val logs = File(external, "logs").apply { mkdirs() }
        val log = File(logs, "log.txt").apply { writeText("log") }
        val unrelated = temporary.newFolder()
        val unrelatedFile = File(unrelated, "keep.txt").apply { writeText("keep") }

        assertTrue(AndroidStorageCacheFiles(context).clear(unrelated, StorageCacheKind.LogFiles))

        assertFalse(log.exists())
        assertTrue(logs.isDirectory)
        assertTrue(unrelatedFile.exists())
    }

    @Test
    fun crashCleanupDelegatesContextAndPreservesSuccess() {
        val context = mockContext()
        val directory = temporary.newFolder()
        val marker = File(directory, "keep.txt").apply { writeText("keep") }
        var receivedContext: Context? = null
        bindHost(CrashLogCleanup {
            receivedContext = it
            true
        })

        assertTrue(AndroidStorageCacheFiles(context).clear(directory, StorageCacheKind.CrashLogs))

        assertSame(context, receivedContext)
        assertTrue(marker.exists())
    }

    @Test
    fun crashCleanupPreservesHostFailure() {
        val context = mockContext()
        bindHost(CrashLogCleanup { false })

        assertFalse(AndroidStorageCacheFiles(context).clear(temporary.newFolder(), StorageCacheKind.CrashLogs))
    }

    @Test
    fun failedDirectoryDeletionDoesNotRecreateDirectory() {
        val directory = mock(File::class.java)
        `when`(directory.isFile).thenReturn(true)
        `when`(directory.exists()).thenReturn(true)
        `when`(directory.delete()).thenReturn(false)

        assertFalse(AndroidStorageCacheFiles(mockContext()).clear(directory, StorageCacheKind.Lyrics))

        verify(directory, never()).mkdirs()
    }

    @Test
    fun directoryAccessFailureReturnsFalse() {
        val directory = mock(File::class.java)
        `when`(directory.isDirectory).thenThrow(SecurityException("denied"))

        assertFalse(AndroidStorageCacheFiles(mockContext()).clear(directory, StorageCacheKind.SharedMedia))

        verify(directory, never()).mkdirs()
    }

    @Test
    fun successfulDeletionKeepsItsResultWhenRecreationFails() {
        val directory = mock(File::class.java)
        `when`(directory.isFile).thenReturn(true)
        `when`(directory.delete()).thenReturn(true)
        `when`(directory.mkdirs()).thenReturn(false)

        assertTrue(AndroidStorageCacheFiles(mockContext()).clear(directory, StorageCacheKind.SharedMedia))

        verify(directory).mkdirs()
    }

    @Test
    fun storageLocationsIncludeExternalDiagnosticsAndDatabaseSidecars() {
        val external = temporary.newFolder()
        val context = mockContext(external = external)
        bindHost()

        val locations = storageLocations(context)

        assertEquals(File(external, "logs"), locations.logDir)
        assertEquals(File(external, "crashes"), locations.crashDir)
        val database = context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME)
        assertEquals(listOf(database, File(database.path + "-wal"), File(database.path + "-shm")), locations.databaseFiles)
        assertEquals(
            listOf(
                "managed_download_snapshot_v1.json", "custom_download_snapshot.json",
                "pending_download_queue_v1.json", "cancelled_download_keys_v1.json",
                "downloaded_song_catalog_v3.json", "downloaded_song_catalog_v4.json"
            ).map { File(context.filesDir, it) },
            locations.downloadMetadataFiles
        )
        assertEquals(context.filesDir, locations.filesDir)
        assertEquals(context.cacheDir, locations.cacheDir)
    }

    @Test
    fun storageLocationsFallBackToInternalFilesForDiagnostics() {
        val context = mockContext()
        bindHost()

        val locations = storageLocations(context)

        assertEquals(File(context.filesDir, "logs"), locations.logDir)
        assertEquals(File(context.filesDir, "crashes"), locations.crashDir)
    }

    @Test
    fun downloadUsageRequestsFreshSnapshotAndMapsReadOnlyEntries() = runBlocking<Unit> {
        val context = mockContext()
        val downloads = bindHost()
        val snapshot = mock(DownloadLibrarySnapshot::class.java)
        val audio = mock(DownloadLibraryEntry::class.java)
        `when`(audio.name).thenReturn("song.m4a")
        `when`(audio.reference).thenReturn("content://downloads/song")
        `when`(audio.mediaUri).thenReturn("content://downloads/song")
        `when`(audio.sizeBytes).thenReturn(100L)
        `when`(snapshot.audioEntries).thenReturn(listOf(audio))
        `when`(snapshot.lyricEntriesByName).thenReturn(emptyMap())
        `when`(snapshot.coverEntriesByName).thenReturn(emptyMap())
        `when`(snapshot.metadataEntriesByAudioName).thenReturn(emptyMap())
        `when`(downloads.buildDownloadLibrarySnapshot(context, forceRefresh = true)).thenReturn(snapshot)

        val usage = AndroidStorageUsageSource(context).downloadLibraryUsage()

        assertEquals(FileStats(100L, 1), usage.audioFiles)
        assertEquals(FileStats.Empty, usage.lyricFiles)
        assertEquals(FileStats.Empty, usage.coverFiles)
        assertEquals(FileStats.Empty, usage.metadataFiles)
        assertTrue(usage.localFiles.isEmpty())
        verify(downloads).buildDownloadLibrarySnapshot(context, forceRefresh = true)
    }

    @Test
    fun downloadUsagePropagatesCancellation() = runBlocking<Unit> {
        val context = mockContext()
        val downloads = bindHost()
        val cancellation = CancellationException("cancelled")
        `when`(downloads.buildDownloadLibrarySnapshot(context, forceRefresh = true)).thenThrow(cancellation)

        val failure = runCatching { AndroidStorageUsageSource(context).downloadLibraryUsage() }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    private fun bindHost(
        crashLogs: CrashLogCleanup = CrashLogCleanup { error("此用例不应清理崩溃日志") }
    ): LocalMediaDownloadAccess {
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        `when`(downloads.snapshotCacheFileName).thenReturn("custom_download_snapshot.json")
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), crashLogs)
        return downloads
    }

    private fun mockContext(external: File? = null): Context = mock(Context::class.java).apply {
        val root = temporary.newFolder()
        `when`(filesDir).thenReturn(File(root, "files").apply { mkdirs() })
        `when`(cacheDir).thenReturn(File(root, "cache").apply { mkdirs() })
        `when`(getExternalFilesDir(null)).thenReturn(external)
        `when`(getDatabasePath(NeriUserDataDatabase.DATABASE_NAME)).thenReturn(File(root, "database"))
        `when`(applicationContext).thenReturn(this)
    }
}
