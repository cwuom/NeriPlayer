package moe.ouom.neriplayer.core.download.cleanup

import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongDeleteTarget
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadResult
import org.junit.Assert.*
import org.junit.Test

class ManagedDownloadFullDeleteOwnershipTest {
    @Test fun `new execution and original pending receipt share physical publication ownership`() {
        val audio = entry("song.mp3")
        val formal = entry("song.mp3.npmeta.json")
        val pending = entry(".tmp/song.mp3.npmeta.pending.json")
        val old = metadata(audio).copy(operationId = "original-write")
        val current = old.copy(operationId = "retry-request", audioPublicationOwnerId = "original-write")
        val state = inventory(listOf(audio, formal), emptyList(), mapOf(
            formal.reference to current, pending.reference to old
        )).copy(temporaryEntries = listOf(pending))

        val plan = planOwnedFullLibraryDeletion(state)

        assertTrue(plan.snapshotComplete)
        assertEquals(setOf(audio.reference, formal.reference, pending.reference), plan.requestedReferences)
        val conflicting = state.copy(metadataByReference = state.metadataByReference +
            (pending.reference to ManagedMetadataReadResult.Found(old.copy(operationId = "another-write"))))
        assertFalse(planOwnedFullLibraryDeletion(conflicting).snapshotComplete)
        assertTrue(planOwnedFullLibraryDeletion(conflicting).requestedReferences.isEmpty())
    }

    @Test fun `stale catalog cover never expands full deletion beyond current receipt`() {
        catalogCoverOwnership(full = true, hasReceipt = true)
    }

    @Test fun `stale catalog cover never expands partial deletion beyond current receipt`() {
        catalogCoverOwnership(full = false, hasReceipt = true)
    }

    @Test fun `missing receipt never promotes catalog cover ownership for full deletion`() {
        catalogCoverOwnership(full = true, hasReceipt = false)
    }

    @Test fun `missing receipt never promotes catalog cover ownership for partial deletion`() {
        catalogCoverOwnership(full = false, hasReceipt = false)
    }

    private fun catalogCoverOwnership(full: Boolean, hasReceipt: Boolean) {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val owned = entry("Covers/owned.jpg")
        val foreign = entry("Covers/song.jpg")
        val data = metadata(audio, owned).copy(mediaUri = audio.reference)
        val song = moe.ouom.neriplayer.core.download.model.DownloadedSong(
            1, "song", "artist", "album", audio.reference, 10, 1, coverPath = foreign.reference)
        val references = if (full) {
            planOwnedFullLibraryDeletion(inventory(listOfNotNull(audio, receipt.takeIf { hasReceipt }), listOf(owned, foreign),
                if (hasReceipt) mapOf(receipt.reference to data) else emptyMap()), selectedSongs = listOf(song)).requestedReferences
        } else {
            val snapshot = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
                audioEntries = listOf(audio), audioEntriesByLookupKey = mapOf(audio.reference to audio),
                metadataEntriesByAudioName = if (hasReceipt) mapOf(audio.name to receipt) else emptyMap(),
                metadataByAudioName = if (hasReceipt) mapOf(audio.name to data) else emptyMap(),
                coverEntriesByName = listOf(owned, foreign).associateBy { it.name },
                knownReferences = setOf(audio.reference, receipt.reference, owned.reference, foreign.reference))
            ManagedDownloadArtifactPlanner.collectArtifactReferences(snapshot, audio,
                explicitReferences = ManagedDownloadArtifactPlanner.buildDeleteContext(song, snapshot).explicitReferences,
                uniqueAudioReferencesByName = mapOf(audio.name to audio.reference))
        }
        assertFalse("catalog cover cannot authorize foreign file deletion", foreign.reference in references)
        assertEquals(hasReceipt, owned.reference in references)
        assertTrue(audio.reference in references)
    }

    @Test fun `full delete owns proven durable pending core but task clear protects it`() {
        val audio = entry("song.mp3.npdl_pending.owner.pending")
        val receipt = entry("song.mp3.npmeta.pending.json")
        val data = DownloadedAudioMetadata(stableKey = "key", operationId = "owner", audioFileName = "song.mp3", artifactState = "CORE_COMMITTED")
        val inventory = inventory(emptyList(), emptyList(), mapOf(receipt.reference to data)).copy(temporaryEntries = listOf(audio, receipt))
        val plan = planOwnedFullLibraryDeletion(inventory)
        assertEquals(setOf(audio.reference, receipt.reference), plan.requestedReferences)
        assertTrue(plan.snapshotComplete)
        val cancelled = ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(listOf(audio, receipt),
            listOf(ManagedDownloadParsedMetadataEntry(receipt, data)), "key", "owner")
        assertTrue(cancelled.referencesToDelete.isEmpty())
        assertEquals(setOf(audio.reference, receipt.reference), cancelled.protectedReferences)
    }

    @Test fun `precise ownership preserves flat foreign files and nested directories`() {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val cover = entry("Covers/owned.jpg")
        val foreign = listOf(entry("Covers/song.jpg"), entry("Lyrics/song.lrc"), entry("Covers/Personal", true))
        val plan = planOwnedFullLibraryDeletion(inventory(listOf(audio, receipt), listOf(cover) + foreign,
            mapOf(receipt.reference to metadata(audio, cover))))
        assertEquals(setOf(audio.reference, receipt.reference, cover.reference), plan.requestedReferences)
        assertTrue(plan.snapshotComplete)
    }

    @Test fun `duplicate audio and conflicting receipt owners cannot be selected by name`() {
        val audio = entry("song.mp3")
        val duplicate = audio.copy(reference = "opaque-duplicate")
        val receipt = entry("song.mp3.npmeta.json")
        val cover = entry("Covers/owned.jpg")
        val data = metadata(audio, cover)
        val duplicatePlan = planOwnedFullLibraryDeletion(inventory(listOf(audio, duplicate, receipt), listOf(cover),
            mapOf(receipt.reference to data)))
        assertTrue(duplicatePlan.requestedReferences.isEmpty())
        val conflictReceipt = receipt.copy(reference = "opaque-conflict")
        val conflict = planOwnedFullLibraryDeletion(inventory(listOf(audio, receipt, conflictReceipt), listOf(cover),
            mapOf(receipt.reference to data, conflictReceipt.reference to data.copy(stableKey = "other"))))
        assertFalse(conflict.snapshotComplete)
        assertTrue(conflict.requestedReferences.isEmpty())
    }

    @Test fun `explicit uri disambiguates duplicate names without deleting sibling`() {
        val audio = entry("song.mp3")
        val duplicate = audio.copy(reference = "opaque-duplicate", mediaUri = "opaque-duplicate")
        val receipt = entry("song.mp3.npmeta.json")
        val plan = planOwnedFullLibraryDeletion(inventory(listOf(audio, duplicate, receipt), emptyList(),
            mapOf(receipt.reference to metadata(audio).copy(mediaUri = audio.reference))))
        assertEquals(setOf(audio.reference, receipt.reference), plan.requestedReferences)
    }

    @Test fun `shared sidecar with an unproven owner is retained`() {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val unknown = entry("unknown.mp3.npmeta.json")
        val cover = entry("Covers/shared.jpg")
        val plan = planOwnedFullLibraryDeletion(inventory(listOf(audio, receipt, unknown), listOf(cover),
            mapOf(receipt.reference to metadata(audio, cover), unknown.reference to DownloadedAudioMetadata(coverPath = cover.reference))))
        assertEquals(setOf(audio.reference, receipt.reference), plan.requestedReferences)
    }

    @Test fun `unavailable read aborts planning while empty json gives no ownership`() {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val initial = inventory(listOf(audio, receipt), emptyList(), mapOf(receipt.reference to DownloadedAudioMetadata()))
        assertTrue(planOwnedFullLibraryDeletion(initial).requestedReferences.isEmpty())
        val failed = planOwnedFullLibraryDeletion(initial.copy(metadataByReference = mapOf(
            receipt.reference to ManagedMetadataReadResult.Unavailable(IOException("busy"))
        )), targets = listOf(DownloadedSongDeleteTarget(audio.reference, "key")))
        assertFalse(failed.snapshotComplete)
        assertTrue(failed.requestedReferences.isEmpty())
    }

    @Test fun `persisted sidecars replay after both audio and metadata disappear`() {
        val sidecar = entry("Lyrics/survivor.lrc")
        val foreign = entry("Lyrics/foreign.lrc")
        val plan = planOwnedFullLibraryDeletion(inventory(emptyList(), listOf(sidecar, foreign), emptyMap()),
            persistedOwnedReferences = setOf("already-deleted-audio", sidecar.reference))
        assertEquals(setOf(sidecar.reference), plan.requestedReferences)
        assertTrue(plan.snapshotComplete)
    }

    @Test fun `five thousand songs use linear inventory visits and precise sidecars`() {
        val roots = mutableListOf<StoredEntry>()
        val covers = mutableListOf<StoredEntry>()
        val metadata = linkedMapOf<String, DownloadedAudioMetadata>()
        val expected = hashSetOf<String>()
        repeat(5000) { index ->
            val audio = entry("$index.mp3")
            val receipt = entry("$index.mp3.npmeta.json")
            val sidecars = (0..3).map { entry("Covers/$index-$it.asset") }
            roots += listOf(audio, receipt)
            covers += sidecars + entry("Covers/foreign-$index.jpg")
            expected += listOf(audio.reference, receipt.reference) + sidecars.map(StoredEntry::reference)
            metadata[receipt.reference] = metadata(audio, sidecars[0]).copy(lyricPath = sidecars[1].reference,
                translatedLyricPath = sidecars[2].reference, romanizedLyricPath = sidecars[3].reference)
        }
        var accesses = 0
        val counted = object : AbstractList<StoredEntry>() {
            override val size get() = roots.size
            override fun get(index: Int): StoredEntry { accesses++; return roots[index] }
        }
        val plan = planOwnedFullLibraryDeletion(inventory(counted, covers, metadata))
        assertEquals(expected, plan.requestedReferences)
        assertTrue("inventory was revisited $accesses times", accesses <= roots.size * 6)
    }

    private fun entry(name: String, directory: Boolean = false) = StoredEntry(name.substringAfterLast('/'),
        "/library/$name", "/library/$name", "/library/$name", 10, 1, isDirectory = directory)
    private fun metadata(audio: StoredEntry, cover: StoredEntry? = null) = DownloadedAudioMetadata(
        stableKey = audio.name, audioFileName = audio.name, downloadFinalized = true, coverPath = cover?.reference)
    private fun inventory(root: List<StoredEntry>, sidecars: List<StoredEntry>, metadata: Map<String, DownloadedAudioMetadata>) =
        ManagedFullDeleteInventory(root, sidecars, emptyList(), emptyList(), metadata.mapValues { ManagedMetadataReadResult.Found(it.value) }, true)
}
