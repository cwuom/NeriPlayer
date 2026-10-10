package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadMetadataCoverFallbackTest {
    @Test
    fun `metadata without a usable stable key may borrow the unnumbered cover`() {
        val cover = coverEntry("Artist - Song.jpg")

        listOf(null, "", "   ").forEach { stableKey ->
            assertEquals(
                stableKey.toString(),
                cover.reference,
                resolve(listOf(cover), DownloadedAudioMetadata(stableKey = stableKey))
            )
        }
    }

    @Test
    fun `stable keyed metadata prefers its own cover and never borrows a numbered sibling`() {
        val stableKey = "1|netease|"
        val metadata = DownloadedAudioMetadata(stableKey = stableKey)
        val siblingCover = coverEntry("Artist - Song.jpg")
        val exactCover = coverEntry("Artist - Song (1).png")
        val stableCover = coverEntry(
            "Artist - Song (1)-${ManagedDownloadStorageNaming.coverStableKeySuffix(stableKey)}.webp"
        )

        assertNull(resolve(listOf(siblingCover), metadata))
        assertEquals(exactCover.reference, resolve(listOf(siblingCover, exactCover), metadata))
        assertEquals(stableCover.reference, resolve(listOf(siblingCover, exactCover, stableCover), metadata))
    }

    private fun resolve(
        covers: List<ManagedDownloadStorage.StoredEntry>,
        metadata: DownloadedAudioMetadata
    ): String? = ManagedDownloadCoverLookup.resolveMetadataCoverReference(
        snapshot = snapshot(covers),
        audioName = "Artist - Song (1).flac",
        metadata = metadata
    )

    private fun coverEntry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "/music/Covers/$name",
        mediaUri = "file:///music/Covers/$name",
        localFilePath = "/music/Covers/$name",
        sizeBytes = 64L,
        lastModifiedMs = 1L
    )

    private fun snapshot(covers: List<ManagedDownloadStorage.StoredEntry>) =
        ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = emptyList(),
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = emptyMap(),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = covers.associateBy { cover -> cover.name },
            lyricEntriesByName = emptyMap(),
            knownReferences = covers.mapTo(linkedSetOf()) { cover -> cover.reference }
        )
}
