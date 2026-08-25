package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadCoverLookupTest {

    @Test
    fun `stable cover candidates prefer short hash and retain legacy digest`() {
        val baseName = "Artist - Song"
        val stableKey = "1|netease|"
        val shortSuffix = ManagedDownloadStorageNaming.coverStableKeySuffix(stableKey)
        val legacyDigestSuffix = ManagedDownloadStorageNaming.stableKeySuffix(stableKey)
        val candidates = ManagedDownloadStorageNaming
            .buildStableCoverCandidateNames(baseName, stableKey)

        assertEquals(8, shortSuffix.length)
        assertEquals(32, legacyDigestSuffix.length)
        assertEquals("$baseName-$shortSuffix.jpg", candidates.first())
        assertTrue("$baseName-$legacyDigestSuffix.jpg" in candidates)
    }

    @Test
    fun `stable cover lookup reads legacy 32 digit digest name`() {
        val baseName = "Artist - Song"
        val stableKey = "1|netease|"
        val legacyCover = coverEntry(
            "$baseName-${ManagedDownloadStorageNaming.stableKeySuffix(stableKey)}.jpg"
        )
        val audio = audioEntry("$baseName.mp3")
        val snapshot = snapshot(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(audio.name to metadata(stableKey = stableKey)),
            coverEntries = listOf(legacyCover)
        )

        assertEquals(
            legacyCover.reference,
            ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
        )
    }

    @Test
    fun `metadata keeps reading an existing pure sha cover reference`() {
        val baseName = "Artist - Song"
        val hash = "a".repeat(64)
        val contentAddressedCover = coverEntry("$hash.jpg")
        val audio = audioEntry("$baseName.mp3")
        val snapshot = snapshot(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(
                audio.name to metadata(
                    stableKey = "1|netease|",
                    coverPath = contentAddressedCover.reference
                )
            ),
            coverEntries = listOf(contentAddressedCover)
        )

        assertEquals(
            contentAddressedCover.reference,
            ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
        )
    }

    @Test
    fun `stable cover lookup does not use colliding legacy hash names`() {
        val baseName = "Artist - Song"
        val firstKey = "FB"
        val collidingKey = "Ea"
        val legacyName = "$baseName-${ManagedDownloadStorageNaming.legacyStableKeySuffix(firstKey)}.jpg"
        val legacyCover = coverEntry(legacyName)
        val snapshot = snapshot(
            audioEntries = listOf(audioEntry("$baseName.mp3")),
            metadataByAudioName = mapOf(
                "$baseName.mp3" to metadata(stableKey = collidingKey)
            ),
            coverEntries = listOf(legacyCover)
        )

        assertFalse(
            ManagedDownloadStorageNaming
                .buildStableCoverCandidateNames(baseName, collidingKey)
                .contains(legacyName)
        )
        assertNull(
            ManagedDownloadCoverLookup.findCoverReference(
                snapshot,
                snapshot.audioEntries.single()
            )
        )
    }

    @Test
    fun `stable duplicate does not inherit the first legacy cover`() {
        val firstAudio = audioEntry("Artist - Song.flac")
        val duplicateAudio = audioEntry("Artist - Song (1).flac")
        val legacyCover = coverEntry("Artist - Song.jpg")
        val snapshot = snapshot(
            audioEntries = listOf(firstAudio, duplicateAudio),
            metadataByAudioName = mapOf(
                firstAudio.name to metadata(
                    stableKey = "1|netease|",
                    coverPath = legacyCover.reference
                ),
                duplicateAudio.name to metadata(stableKey = "2|netease|")
            ),
            coverEntries = listOf(legacyCover)
        )

        assertNull(ManagedDownloadCoverLookup.findCoverReference(snapshot, duplicateAudio))
    }

    @Test
    fun `stable duplicate resolves its own suffixed cover before legacy fallback`() {
        val duplicateAudio = audioEntry("Artist - Song (1).flac")
        val stableKey = "2|netease|"
        val legacyCover = coverEntry("Artist - Song.jpg")
        val dedicatedCover = coverEntry(
            ManagedDownloadStorageNaming
                .buildStableCoverCandidateNames(duplicateAudio.nameWithoutExtension, stableKey)
                .first()
        )
        val snapshot = snapshot(
            audioEntries = listOf(duplicateAudio),
            metadataByAudioName = mapOf(duplicateAudio.name to metadata(stableKey = stableKey)),
            coverEntries = listOf(legacyCover, dedicatedCover)
        )

        assertEquals(
            dedicatedCover.reference,
            ManagedDownloadCoverLookup.findCoverReference(snapshot, duplicateAudio)
        )
    }

    @Test
    fun `legacy duplicate keeps numbered name fallback without stable identity`() {
        val duplicateAudio = audioEntry("Artist - Song (1).flac")
        val legacyCover = coverEntry("Artist - Song.jpg")
        val snapshot = snapshot(
            audioEntries = listOf(duplicateAudio),
            metadataByAudioName = mapOf(duplicateAudio.name to metadata(stableKey = null)),
            coverEntries = listOf(legacyCover)
        )

        assertEquals(
            legacyCover.reference,
            ManagedDownloadCoverLookup.findCoverReference(snapshot, duplicateAudio)
        )
    }

    private fun snapshot(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = audioEntries,
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = metadataByAudioName,
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = coverEntries.associateBy(ManagedDownloadStorage.StoredEntry::name),
            lyricEntriesByName = emptyMap(),
            knownReferences = (audioEntries + coverEntries)
                .map(ManagedDownloadStorage.StoredEntry::reference)
                .toSet()
        )
    }

    private fun metadata(
        stableKey: String?,
        coverPath: String? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata {
        return ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = stableKey,
            name = "Song",
            artist = "Artist",
            coverPath = coverPath
        )
    }

    private fun audioEntry(name: String): ManagedDownloadStorage.StoredEntry {
        return storedEntry(name, "/music/$name")
    }

    private fun coverEntry(name: String): ManagedDownloadStorage.StoredEntry {
        return storedEntry(name, "/music/Covers/$name")
    }

    private fun storedEntry(
        name: String,
        reference: String
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = "file://$reference",
            localFilePath = reference,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
    }
}
