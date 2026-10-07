package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadSnapshotEntryKeyTest {
    @Test
    fun `entry keys fall back from reference to media uri to name and keep the first duplicate`() {
        val byReference = entry("a.flac", reference = "/music/a.flac", mediaUri = "file:///music/a.flac")
        val sameReference = entry("a-copy.flac", reference = "/music/a.flac", mediaUri = "file:///music/a-copy.flac")
        val byMediaUri = entry("b.flac", reference = " ", mediaUri = "content://downloads/b")
        val byName = entry("c.flac", reference = "", mediaUri = "")
        val cover = entry("a.jpg", reference = "/music/Covers/a.jpg", mediaUri = "file:///music/Covers/a.jpg")

        val entities = ManagedDownloadSnapshotRoomMapper.toEntryEntities(
            rootKey = "root-1",
            snapshot = snapshot(
                audioEntries = listOf(byReference, sameReference, byMediaUri),
                pendingAudioEntries = listOf(byName),
                coverEntriesByName = mapOf(cover.name to cover)
            )
        )

        assertEquals(
            listOf(
                listOf("audio", "/music/a.flac", "a.flac", 0),
                listOf("audio", "uri:content://downloads/b", "b.flac", 1),
                listOf("audio", "name:c.flac", "c.flac", 2),
                listOf("cover", "/music/Covers/a.jpg", "a.jpg", 0)
            ),
            entities.map { entity ->
                listOf(entity.bucket, entity.entryKey, entity.name, entity.displayPosition)
            }
        )
        assertEquals(setOf("root-1"), entities.map { entity -> entity.rootKey }.toSet())
    }

    private fun entry(name: String, reference: String, mediaUri: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = mediaUri,
        localFilePath = null,
        sizeBytes = 64L,
        lastModifiedMs = 5L
    )

    private fun snapshot(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        pendingAudioEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
    ) = ManagedDownloadStorage.DownloadLibrarySnapshot(
        audioEntries = audioEntries,
        audioEntriesByLookupKey = emptyMap(),
        metadataEntriesByAudioName = emptyMap(),
        metadataByAudioName = emptyMap(),
        audioEntriesWithoutMetadata = emptyList(),
        audioEntriesByStableKey = emptyMap(),
        audioEntriesBySongId = emptyMap(),
        audioEntriesByMediaUri = emptyMap(),
        audioEntriesByRemoteTrackKey = emptyMap(),
        coverEntriesByName = coverEntriesByName,
        lyricEntriesByName = emptyMap(),
        knownReferences = emptySet(),
        pendingAudioEntries = pendingAudioEntries
    )
}
