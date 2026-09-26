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

    @Test fun `unavailable read retains receipts while selected exact audio can still be deleted`() {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val initial = inventory(listOf(audio, receipt), emptyList(), mapOf(receipt.reference to DownloadedAudioMetadata()))
        assertTrue(planOwnedFullLibraryDeletion(initial).requestedReferences.isEmpty())
        val failed = planOwnedFullLibraryDeletion(initial.copy(metadataByReference = mapOf(
            receipt.reference to ManagedMetadataReadResult.Unavailable(IOException("busy"))
        )), targets = listOf(DownloadedSongDeleteTarget(audio.reference, "key")))
        assertFalse(failed.snapshotComplete)
        assertEquals(setOf(audio.reference), failed.requestedReferences)
    }

    @Test fun `unavailable receipt still permits deletion of an exact persisted lyric reference`() {
        val audio = entry("song.mp3")
        val receipt = entry("song.mp3.npmeta.json")
        val lyric = entry("Lyrics/song.lrc")
        val state = inventory(listOf(audio, receipt), listOf(lyric), emptyMap()).copy(
            metadataByReference = mapOf(
                receipt.reference to ManagedMetadataReadResult.Unavailable(IOException("provider busy"))
            )
        )

        val plan = planOwnedFullLibraryDeletion(
            state,
            targets = listOf(DownloadedSongDeleteTarget(audio.reference, "key")),
            persistedOwnedReferences = setOf(lyric.reference)
        )

        assertFalse(plan.snapshotComplete)
        assertEquals(setOf(audio.reference, lyric.reference), plan.requestedReferences)
        assertFalse(receipt.reference in plan.requestedReferences)
    }

    @Test fun `conflicting receipt owners do not block unrelated proven songs`() {
        val conflictingAudio = entry("conflicting.mp3")
        val formal = entry("conflicting.mp3.npmeta.json")
        val pending = entry(".tmp/conflicting.mp3.npmeta.pending.json")
        val conflictCover = entry("Covers/conflict.jpg")
        val healthyAudio = entry("healthy.mp3")
        val healthyReceipt = entry("healthy.mp3.npmeta.json")
        val healthyCover = entry("Covers/healthy.jpg")
        val state = inventory(
            listOf(conflictingAudio, formal, healthyAudio, healthyReceipt),
            listOf(conflictCover, healthyCover),
            mapOf(
                formal.reference to metadata(conflictingAudio, conflictCover).copy(operationId = "first-owner"),
                pending.reference to metadata(conflictingAudio, conflictCover).copy(operationId = "second-owner"),
                healthyReceipt.reference to metadata(healthyAudio, healthyCover)
            )
        ).copy(temporaryEntries = listOf(pending))

        val plan = planOwnedFullLibraryDeletion(state)

        assertFalse(plan.snapshotComplete)
        assertEquals(setOf(healthyAudio.reference, healthyReceipt.reference, healthyCover.reference), plan.requestedReferences)
        assertEquals(setOf(pending.reference), plan.unresolvedPendingReferences)
        assertEquals(1, plan.blockingReasonCounts[ManagedDownloadFullDeleteBlockReason.CONFLICTING_RECEIPT])
    }

    @Test fun `unreadable receipt preserves unrelated receipts and sidecars for later recovery`() {
        val unreadableAudio = entry("unreadable.mp3")
        val unreadableReceipt = entry("unreadable.mp3.npmeta.json")
        val healthyAudio = entry("healthy.mp3")
        val healthyReceipt = entry("healthy.mp3.npmeta.json")
        val sharedCover = entry("Covers/shared.jpg")
        val state = inventory(
            listOf(unreadableAudio, unreadableReceipt, healthyAudio, healthyReceipt),
            listOf(sharedCover),
            mapOf(healthyReceipt.reference to metadata(healthyAudio, sharedCover))
        ).copy(metadataByReference = mapOf(
            unreadableReceipt.reference to ManagedMetadataReadResult.Unavailable(IOException("provider busy")),
            healthyReceipt.reference to ManagedMetadataReadResult.Found(metadata(healthyAudio, sharedCover))
        ))

        val plan = planOwnedFullLibraryDeletion(state, targets = listOf(
            DownloadedSongDeleteTarget(unreadableAudio.reference, "unreadable"),
            DownloadedSongDeleteTarget(healthyAudio.reference, "healthy")
        ))

        assertFalse(plan.snapshotComplete)
        assertEquals(setOf(unreadableAudio.reference, healthyAudio.reference), plan.requestedReferences)
        assertEquals(1, plan.blockingReasonCounts[ManagedDownloadFullDeleteBlockReason.METADATA_UNAVAILABLE])
    }

    @Test fun `unresolved temporary core does not erase unrelated owned deletion plan`() {
        val audio = entry("healthy.mp3")
        val receipt = entry("healthy.mp3.npmeta.json")
        val pending = entry(".tmp/unknown.mp3.npdl_pending.unknown-owner.pending")
        val state = inventory(listOf(audio, receipt), emptyList(), mapOf(receipt.reference to metadata(audio)))
            .copy(temporaryEntries = listOf(pending))

        val plan = planOwnedFullLibraryDeletion(state)

        assertFalse(plan.snapshotComplete)
        assertEquals(setOf(audio.reference, receipt.reference), plan.requestedReferences)
        assertEquals(setOf(pending.reference), plan.unresolvedPendingReferences)
        assertEquals(1, plan.blockingReasonCounts[ManagedDownloadFullDeleteBlockReason.UNRESOLVED_PENDING])
    }

    @Test fun `selected song deletes exact receipts from multiple completed download attempts`() {
        selectedAttemptOwnership(pendingAudioPresent = true)
    }

    @Test fun `selected song retires previously owned pending receipt after its audio disappeared`() {
        selectedAttemptOwnership(pendingAudioPresent = false)
    }

    @Test fun `multiple attempts retain receipts when old library identity is absent`() {
        selectedAttemptOwnership(pendingAudioPresent = true, oldLibraryId = null)
        selectedAttemptOwnership(pendingAudioPresent = true, oldLibraryId = "")
    }

    @Test fun `multiple attempts cannot treat foreign or unknown absent references as deleted audio`() {
        selectedAttemptOwnership(pendingAudioPresent = false, foreignReference = true)
        selectedAttemptOwnership(pendingAudioPresent = false, unprovenMissing = true)
    }

    @Test fun `multiple attempts cannot claim an unrelated same name pending audio`() {
        selectedAttemptOwnership(pendingAudioPresent = true, extraUnknownAudio = true)
    }

    @Test fun `selected song cannot combine receipts from different libraries or stable keys`() {
        selectedAttemptOwnership(pendingAudioPresent = true, conflictingLibrary = true)
        selectedAttemptOwnership(pendingAudioPresent = true, conflictingStableKey = true)
    }

    private fun selectedAttemptOwnership(
        pendingAudioPresent: Boolean,
        extraUnknownAudio: Boolean = false,
        conflictingLibrary: Boolean = false,
        conflictingStableKey: Boolean = false,
        oldLibraryId: String? = "current-library",
        foreignReference: Boolean = false,
        unprovenMissing: Boolean = false
    ) {
        val audio = entry("song.mp3")
        val formal = entry("song.mp3.npmeta.json")
        val pendingAudio = entry(".tmp/song.mp3.npdl_pending.old.pending")
        val pendingReceipt = entry(".tmp/song.mp3.npmeta.pending.json")
        val unknownAudio = entry(".tmp/song.mp3.npdl_pending.unknown.pending")
        val cover = entry("Covers/current.jpg")
        val oldCover = entry("Covers/old.jpg")
        val foreignCover = entry("Covers/foreign.jpg")
        val current = metadata(audio, cover).copy(
            operationId = "current-owner", artifactId = "current-artifact", libraryId = "current-library",
            mediaUri = audio.reference
        )
        val old = metadata(audio, oldCover).copy(
            stableKey = if (conflictingStableKey) "different-song" else current.stableKey,
            operationId = "old-owner", artifactId = "old-artifact",
            libraryId = if (conflictingLibrary) "different-library" else oldLibraryId,
            mediaUri = if (foreignReference) "content://foreign/document/opaque" else pendingAudio.reference
        )
        val state = inventory(listOf(audio, formal), listOf(cover, oldCover, foreignCover), mapOf(
            formal.reference to current, pendingReceipt.reference to old
        )).copy(temporaryEntries = listOfNotNull(
            pendingAudio.takeIf { pendingAudioPresent }, pendingReceipt, unknownAudio.takeIf { extraUnknownAudio }
        ))

        val plan = planOwnedFullLibraryDeletion(state,
            targets = listOf(DownloadedSongDeleteTarget(audio.reference, current.stableKey)),
            persistedOwnedReferences = if (!pendingAudioPresent && !foreignReference && !unprovenMissing) {
                setOf(pendingAudio.reference)
            } else emptySet())

        if (extraUnknownAudio || conflictingLibrary || conflictingStableKey ||
            oldLibraryId.isNullOrBlank() || foreignReference || unprovenMissing
        ) {
            assertFalse(plan.snapshotComplete)
            assertEquals(setOf(audio.reference), plan.requestedReferences)
        } else {
            assertTrue(plan.blockingReasonCounts.toString(), plan.snapshotComplete)
            assertEquals(setOfNotNull(audio.reference, formal.reference, pendingReceipt.reference,
                pendingAudio.reference.takeIf { pendingAudioPresent }, cover.reference, oldCover.reference),
                plan.requestedReferences)
        }
        assertFalse(foreignCover.reference in plan.requestedReferences)
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
