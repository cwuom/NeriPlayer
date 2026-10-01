package moe.ouom.neriplayer.data.local.storage.mapping

import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.StorageLibraryEntry

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageHostTest {
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
}
