package moe.ouom.neriplayer.core.download.storage.migration

import kotlin.system.measureTimeMillis
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationPresenceProbeTest {
    @Test
    fun `one metadata sidecar is a migratable managed entry`() {
        assertTrue(
            hasManagedEntries(
                rootEntries = listOf(entry("track.mp3.npmeta.json"))
            )
        )
    }

    @Test
    fun `empty directory has no migratable managed entries`() {
        assertFalse(hasManagedEntries())
    }

    @Test
    fun `foreign SAF audio without managed evidence is not migratable`() {
        assertFalse(
            hasManagedEntries(
                rootEntries = listOf(entry("recording.mp3")),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `directory named like metadata is not a migratable file`() {
        assertFalse(
            hasManagedEntries(
                rootEntries = listOf(entry("track.mp3.npmeta.json", isDirectory = true))
            )
        )
    }

    @Test
    fun `private root audio remains backward compatible without metadata`() {
        assertTrue(
            hasManagedEntries(
                rootEntries = listOf(entry("legacy.mp3")),
                allowMetadataLessAudio = true
            )
        )
    }

    @Test
    fun `metadata sidecar avoids cover and lyric enumeration`() {
        assertFalse(
            ManagedDownloadMigrationEntryCollector.requiresSidecarEvidence(
                rootEntries = listOf(entry("track.mp3.npmeta.json")),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `metadata less SAF audio requests sidecar evidence exactly once`() {
        assertTrue(
            ManagedDownloadMigrationEntryCollector.requiresSidecarEvidence(
                rootEntries = listOf(entry("legacy.mp3")),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `empty root avoids cover and lyric enumeration`() {
        assertFalse(
            ManagedDownloadMigrationEntryCollector.requiresSidecarEvidence(
                rootEntries = emptyList(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `two thousand entry presence check stays CPU only and bounded`() {
        val entries = List(2_000) { index ->
            entry("track-$index.mp3.npmeta.json")
        }

        val elapsedMs = measureTimeMillis {
            repeat(20) {
                assertTrue(hasManagedEntries(rootEntries = entries))
            }
        }

        assertTrue("presence checks took ${elapsedMs}ms", elapsedMs < 1_000L)
    }

    private fun hasManagedEntries(
        rootEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        coverEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        lyricEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        allowMetadataLessAudio: Boolean = false
    ): Boolean {
        return ManagedDownloadMigrationEntryCollector.hasAnyManagedEntry(
            rootEntries = rootEntries,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            allowMetadataLessAudio = allowMetadataLessAudio
        )
    }

    private fun entry(
        name: String,
        isDirectory: Boolean = false
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "test-reference",
            mediaUri = "content://test/document",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L,
            isDirectory = isDirectory
        )
    }
}
