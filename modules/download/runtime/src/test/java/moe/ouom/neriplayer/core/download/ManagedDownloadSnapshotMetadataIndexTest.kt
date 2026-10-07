package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadSnapshotMetadataIndexTest {
    @Test
    fun `declared audio names index metadata by canonical name keeping the first owner`() {
        val first = DownloadedAudioMetadata(stableKey = "first", audioFileName = " Song.FLAC ")
        val duplicate = DownloadedAudioMetadata(stableKey = "duplicate", audioFileName = "song.flac")
        val other = DownloadedAudioMetadata(stableKey = "other", audioFileName = "Other.mp3")
        val undeclared = DownloadedAudioMetadata(stableKey = "undeclared")
        val blank = DownloadedAudioMetadata(stableKey = "blank", audioFileName = "  ")

        val snapshot = snapshot(
            linkedMapOf(
                "a.flac" to first,
                "b.flac" to duplicate,
                "c.mp3" to other,
                "d.flac" to undeclared,
                "e.flac" to blank
            )
        )

        assertEquals(mapOf("song.flac" to first, "other.mp3" to other), snapshot.metadataByDeclaredAudioName)
    }

    @Test
    fun `stored references index metadata by trimmed media uri keeping the first owner`() {
        val first = DownloadedAudioMetadata(stableKey = "first", mediaUri = " content://downloads/song.flac ")
        val duplicate = DownloadedAudioMetadata(stableKey = "duplicate", mediaUri = "content://downloads/song.flac")
        val other = DownloadedAudioMetadata(stableKey = "other", mediaUri = "content://downloads/other.mp3")
        val unbound = DownloadedAudioMetadata(stableKey = "unbound")
        val blank = DownloadedAudioMetadata(stableKey = "blank", mediaUri = "\t")

        val snapshot = snapshot(
            linkedMapOf(
                "a.flac" to first,
                "b.flac" to duplicate,
                "c.mp3" to other,
                "d.flac" to unbound,
                "e.flac" to blank
            )
        )

        assertEquals(
            mapOf("content://downloads/song.flac" to first, "content://downloads/other.mp3" to other),
            snapshot.metadataByStoredReference
        )
    }

    private fun snapshot(
        metadataByAudioName: Map<String, DownloadedAudioMetadata>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = emptyList(),
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = metadataByAudioName,
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = emptySet()
        )
    }
}
