package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedLibraryRebuilderTest {
    @Test
    fun `rebuild plan keeps stable identity and durable logical time`() {
        val audio = audio(lastModifiedMs = 99L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            artifactId = "artifact-song",
            downloadTimeMs = 11L,
            createdAtMs = 22L,
            libraryAddedAtMs = 33L
        )
        val snapshot = snapshot(audio, metadata)

        val item = ManagedLibraryRebuilder.plan(snapshot).single()

        assertEquals("stable-song", item.stableKey)
        assertEquals("artifact-song", item.artifactId)
        assertEquals(11L, item.logicalTimeMs)
    }

    @Test
    fun `metadata-less audio remains in plan and falls back to file time`() {
        val audio = audio(lastModifiedMs = 77L)
        val item = ManagedLibraryRebuilder.plan(snapshot(audio, null)).single()

        assertNull(item.stableKey)
        assertEquals(77L, item.logicalTimeMs)
    }

    private fun snapshot(
        audio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = mapOf(audio.reference to audio),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = metadata?.let { mapOf(audio.name to it) }.orEmpty(),
            audioEntriesWithoutMetadata = if (metadata == null) listOf(audio) else emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audio.reference)
        )
    }

    private fun audio(lastModifiedMs: Long): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/library/song.mp3",
            mediaUri = "/library/song.mp3",
            localFilePath = "/library/song.mp3",
            sizeBytes = 10L,
            lastModifiedMs = lastModifiedMs
        )
    }
}
