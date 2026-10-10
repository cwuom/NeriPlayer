package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadIndexedCoverReferenceTest {
    @Test
    fun `the first base name with an indexed cover wins in extension priority order`() {
        val snapshot = snapshot("Song.webp", "Song.png", "Artist - Song.jpg")

        assertEquals(
            "/library/Covers/Song.png",
            ManagedDownloadArtifactPlanner.indexedCoverReference(
                candidateBaseNames = listOf("Missing", "Song", "Artist - Song"),
                snapshot = snapshot
            )
        )
    }

    @Test
    fun `base names without indexed covers resolve to no reference`() {
        val snapshot = snapshot("Song.gif", "Other.jpg")

        assertNull(ManagedDownloadArtifactPlanner.indexedCoverReference(listOf("Song", "Missing"), snapshot))
        assertNull(ManagedDownloadArtifactPlanner.indexedCoverReference(emptyList(), snapshot))
    }

    @Test
    fun `numbered audio prefers its own cover before the unnumbered base cover`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Song (1).flac",
            reference = "/library/Song (1).flac",
            mediaUri = "/library/Song (1).flac",
            localFilePath = "/library/Song (1).flac",
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )

        assertEquals(
            "/library/Covers/Song.jpg",
            ManagedDownloadArtifactPlanner.indexedCoverReference(audio, snapshot("Song.jpg"))
        )
        assertEquals(
            "/library/Covers/Song (1).webp",
            ManagedDownloadArtifactPlanner.indexedCoverReference(
                audio,
                snapshot("Song.jpg", "Song (1).webp")
            )
        )
    }

    private fun snapshot(vararg coverNames: String) = ManagedDownloadStorage.DownloadLibrarySnapshot(
        audioEntries = emptyList(),
        audioEntriesByLookupKey = emptyMap(),
        metadataEntriesByAudioName = emptyMap(),
        metadataByAudioName = emptyMap(),
        audioEntriesWithoutMetadata = emptyList(),
        audioEntriesByStableKey = emptyMap(),
        audioEntriesBySongId = emptyMap(),
        audioEntriesByMediaUri = emptyMap(),
        audioEntriesByRemoteTrackKey = emptyMap(),
        coverEntriesByName = coverNames.associateWith { name ->
            ManagedDownloadStorage.StoredEntry(
                name = name,
                reference = "/library/Covers/$name",
                mediaUri = "/library/Covers/$name",
                localFilePath = "/library/Covers/$name",
                sizeBytes = 1L,
                lastModifiedMs = 1L
            )
        },
        lyricEntriesByName = emptyMap(),
        knownReferences = emptySet()
    )
}
