package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongBuilderCoverPolicyTest {

    @Test
    fun `restored remote cover does not fall back to a stale indexed sidecar`() {
        val originalCover = "https://example.com/original-cover.jpg"

        assertFalse(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = originalCover,
                    originalCoverUrl = originalCover,
                    customCoverUrl = null,
                    coverPath = null
                )
            )
        )
    }

    @Test
    fun `ordinary downloads retain indexed cover fallback`() {
        assertTrue(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = "https://example.com/cover.jpg"
                )
            )
        )
    }

    @Test
    fun `stale local original cover keeps current indexed sidecar fallback`() {
        val staleReference =
            "content://com.android.externalstorage.documents/tree/primary%3AOld/" +
                "document/primary%3AOld%2FCovers%2Fcover.jpg"
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            coverUrl = staleReference,
            originalCoverUrl = staleReference
        )

        assertTrue(shouldUseIndexedDownloadedCoverFallback(metadata))
    }

    @Test
    fun `stale SAF cover path rebinds to the current stable sidecar`() {
        val stableKey = "1313341399|netease|"
        val audio = storedEntry(
            name = "netease - Artist - Song.mp3",
            reference = "content://new-root/Song.mp3"
        )
        val currentCover = storedEntry(
            name = ManagedDownloadStorageNaming.buildStableCoverCandidateNames(
                baseName = audio.nameWithoutExtension,
                stableKey = stableKey
            ).first(),
            reference = "content://new-root/Covers/Song.jpg"
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = stableKey,
            coverPath = "content://old-root/Covers/Song.jpg",
            coverUrl = "https://example.com/cover.jpg"
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = mapOf(audio.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = mapOf(currentCover.name to currentCover),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audio.reference, currentCover.reference)
        )

        assertEquals(
            currentCover.reference,
            resolveIndexedDownloadedCoverReference(
                metadata = metadata,
                storedAudio = audio,
                snapshot = snapshot
            )
        )
    }

    @Test
    fun `local cover references remain distinguishable from remote metadata urls`() {
        assertTrue(isResolvableLocalReference("content://downloads/Covers/song.jpg"))
        assertTrue(isResolvableLocalReference("file:///data/user/0/app/song.jpg"))
        assertTrue(isResolvableLocalReference("file:/data/user/0/app/song.jpg"))
        assertTrue(isResolvableLocalReference("/storage/emulated/0/song.jpg"))
        assertFalse(isResolvableLocalReference("https://example.com/song.jpg"))
    }

    @Test
    fun `legacy recovery reference rebinds a hash named cover after reauthorization`() {
        val audio = storedEntry(
            name = "netease - Artist - Song.mp3",
            reference = "content://new-root/Song.mp3"
        )
        val cover = storedEntry(
            name = "legacy-cover-cb470461.jpg",
            reference = "content://new-root/Covers/legacy-cover-cb470461.jpg"
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            restorableMetadata = ManagedDownloadRestorableMetadata(
                sourceStableKey = "1|netease|",
                baseline = ManagedDownloadRestorableMetadata.Baseline(),
                overrides = ManagedDownloadRestorableMetadata.Overrides(),
                legacyCoverRecoveryReferences = listOf(
                    "content://old-root/document/root%2FCovers%2Flegacy-cover-cb470461.jpg"
                )
            )
        )
        val snapshot = emptySnapshot(
            knownReferences = setOf(audio.reference, cover.reference),
            coverEntriesByName = mapOf(cover.name to cover)
        )

        assertEquals(
            cover.reference,
            resolveIndexedDownloadedCoverReference(metadata, audio, snapshot)
        )
    }

    @Test
    fun `slow metadata cover fallback is disabled for fast catalog hydration`() {
        assertFalse(
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    name = "Song",
                    artist = "Artist",
                    durationMs = 1_000L
                ),
                coverReference = null,
                needsLocalLyricFallback = false
            )
        )
    }

    @Test
    fun `only accessible typed evidence is accepted as a managed reference candidate`() {
        assertTrue(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Accessible
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Missing
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
    }

    @Test
    fun `current indexed lyric replaces stale metadata from the previous SAF root`() {
        val currentReference = "content://downloads-new/Lyrics/song.lrc"
        val staleReference = "content://downloads-old/Lyrics/song.lrc"
        val inspected = mutableListOf<String>()

        val selection = selectDownloadedLyricReference(
            indexedReference = currentReference,
            metadataReference = staleReference,
            loadLyricContents = true,
            inspectReference = { reference ->
                inspected += reference
                if (reference == currentReference) {
                    ManagedDownloadReferenceIo.AccessResult.Accessible
                } else {
                    ManagedDownloadReferenceIo.AccessResult.PermissionLost
                }
            }
        )

        assertEquals(currentReference, selection.resolvedReference)
        assertNull(selection.fallbackReference)
        assertEquals(listOf(currentReference), inspected)
    }

    @Test
    fun `inaccessible stale lyric is never retained as a read fallback`() {
        val staleReference = "content://downloads-old/Lyrics/song_roma.lrc"
        var inspections = 0

        val selection = selectDownloadedLyricReference(
            indexedReference = null,
            metadataReference = staleReference,
            loadLyricContents = true,
            inspectReference = {
                inspections += 1
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            }
        )

        assertNull(selection.resolvedReference)
        assertNull(selection.fallbackReference)
        assertEquals(1, inspections)
    }

    @Test
    fun `stale local cover metadata is removed before playback while remote urls remain`() {
        val currentReference = "content://downloads-new/Covers/song.jpg"
        val snapshot = emptySnapshot(knownReferences = setOf(currentReference))

        assertNull(
            sanitizeDownloadedCoverMetadataReference(
                "content://downloads-old/Covers/song.jpg",
                snapshot
            )
        )
        assertEquals(
            currentReference,
            sanitizeDownloadedCoverMetadataReference(currentReference, snapshot)
        )
        assertEquals(
            "https://example.com/song.jpg",
            sanitizeDownloadedCoverMetadataReference(
                "https://example.com/song.jpg",
                snapshot
            )
        )
    }

    private fun storedEntry(
        name: String,
        reference: String
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
    }

    private fun emptySnapshot(
        knownReferences: Set<String> = emptySet(),
        coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry> = emptyMap()
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = emptyList(),
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
            knownReferences = knownReferences
        )
    }
}
