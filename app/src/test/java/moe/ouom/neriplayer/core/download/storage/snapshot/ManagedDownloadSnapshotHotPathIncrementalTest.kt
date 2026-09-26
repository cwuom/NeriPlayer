package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadSnapshotHotPathIncrementalTest {
    @Test
    fun `pending core write keeps finalized library indexes`() {
        val snapshot = populatedSnapshot()
        val pending = entry(
            name = "next.mp3.npdl_pending.operation.pending",
            reference = "content://provider/pending/next"
        )

        val updated = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(
            snapshot = snapshot,
            storedEntry = pending,
            bucket = ManagedDownloadStorage.SnapshotEntryBucket.AUDIO
        )

        assertSame(snapshot.audioEntries, updated.audioEntries)
        assertSame(snapshot.audioEntriesByLookupKey, updated.audioEntriesByLookupKey)
        assertSame(snapshot.audioEntriesByStableKey, updated.audioEntriesByStableKey)
        assertSame(snapshot.audioEntriesBySongId, updated.audioEntriesBySongId)
        assertEquals(listOf(pending), updated.pendingAudioEntries)
        assertTrue(pending.reference in updated.knownReferences)
    }

    @Test
    fun `publication marker with the same identity keeps audio indexes`() {
        val snapshot = populatedSnapshot()
        val audioName = snapshot.audioEntries.first().name
        val previous = requireNotNull(snapshot.metadataByAudioName[audioName])
        val metadataEntry = requireNotNull(snapshot.metadataEntriesByAudioName[audioName]).copy(
            reference = "content://provider/metadata/replaced"
        )

        val updated = ManagedDownloadSnapshotIndex.applyMetadataWrite(
            snapshot = snapshot,
            metadataEntry = metadataEntry,
            metadata = previous.copy(audioPublicationPending = true)
        )

        assertSame(snapshot.audioEntries, updated.audioEntries)
        assertSame(snapshot.audioEntriesByLookupKey, updated.audioEntriesByLookupKey)
        assertSame(snapshot.audioEntriesByStableKey, updated.audioEntriesByStableKey)
        assertSame(snapshot.audioEntriesBySongId, updated.audioEntriesBySongId)
        assertEquals(metadataEntry, updated.metadataEntriesByAudioName[audioName])
    }

    @Test
    fun `deleting an unindexed receipt keeps finalized library indexes`() {
        val receipt = "content://provider/tmp/receipt"
        val populated = populatedSnapshot()
        val snapshot = populated.copy(knownReferences = populated.knownReferences + receipt)

        val updated = ManagedDownloadSnapshotIndex.applyReferenceDeletes(
            snapshot = snapshot,
            references = setOf(receipt)
        )

        assertSame(snapshot.audioEntries, updated.audioEntries)
        assertSame(snapshot.audioEntriesByLookupKey, updated.audioEntriesByLookupKey)
        assertSame(snapshot.audioEntriesByStableKey, updated.audioEntriesByStableKey)
        assertSame(snapshot.audioEntriesBySongId, updated.audioEntriesBySongId)
        assertTrue(receipt !in updated.knownReferences)
    }

    @Test
    fun `deleting a file uri alias removes the matching audio entry`() {
        val audio = entry(
            name = "alias.flac",
            reference = "/music/alias.flac"
        ).copy(
            mediaUri = "file:///music/alias.flac",
            localFilePath = "/music/alias.flac"
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(audio),
            metadataEntries = emptyList(),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

        val updated = ManagedDownloadSnapshotIndex.applyReferenceDeletes(
            snapshot,
            setOf(audio.mediaUri)
        )

        assertTrue(updated.audioEntries.isEmpty())
        assertFalse(audio.reference in updated.knownReferences)
    }

    private fun populatedSnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val audioEntries = (0 until 256).map { index ->
            entry("song-$index.mp3", "content://provider/audio/$index")
        }
        val metadataEntries = audioEntries.mapIndexed { index, audio ->
            entry("${audio.name}.npmeta.json", "content://provider/metadata/$index")
        }
        val metadata = audioEntries.mapIndexed { index, audio ->
            audio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "stable-$index",
                songId = index.toLong() + 1L,
                audioFileName = audio.name,
                downloadFinalized = true
            )
        }.toMap()
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadata,
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
    }

    private fun entry(
        name: String,
        reference: String
    ) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = reference,
        localFilePath = null,
        sizeBytes = 128L,
        lastModifiedMs = 1L
    )
}
