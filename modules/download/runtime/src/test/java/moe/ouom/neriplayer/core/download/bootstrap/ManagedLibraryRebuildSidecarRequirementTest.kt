package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedLibraryRebuildSidecarRequirementTest {
    private val audio = ManagedDownloadStorage.StoredEntry(
        name = "song.mp3",
        reference = "/library/song.mp3",
        mediaUri = "/library/song.mp3",
        localFilePath = "/library/song.mp3",
        sizeBytes = 10L,
        lastModifiedMs = 77L
    )
    private val finalized = DownloadedAudioMetadata(
        stableKey = "stable-song",
        downloadFinalized = true,
        metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
    )

    @Test
    fun `missing required translated or romanized lyric sidecars keep audio out of the plan`() {
        val translatedMissing = finalized.copy(matchedTranslatedLyric = "[00:01.00]translated")
        val romanizedUnknown = finalized.copy(
            originalRomanizedLyric = "[00:01.00]romanized",
            romanizedLyricPath = "/library/Lyrics/song.roma.lrc"
        )

        assertEquals(emptyList<ManagedLibraryRebuildItem>(), ManagedLibraryRebuilder.plan(snapshot(translatedMissing)))
        assertEquals(emptyList<ManagedLibraryRebuildItem>(), ManagedLibraryRebuilder.plan(snapshot(romanizedUnknown)))
    }

    @Test
    fun `known translated and romanized lyric sidecars keep audio in the plan`() {
        val translatedPath = "/library/Lyrics/song.trans.lrc"
        val romanizedPath = "/library/Lyrics/song.roma.lrc"
        val metadata = finalized.copy(
            matchedTranslatedLyric = "[00:01.00]translated",
            translatedLyricPath = " $translatedPath ",
            originalRomanizedLyric = "[00:01.00]romanized",
            romanizedLyricPath = romanizedPath
        )
        val snapshot = snapshot(metadata).copy(
            knownReferences = setOf(audio.reference, translatedPath, romanizedPath)
        )

        assertEquals(listOf(audio), ManagedLibraryRebuilder.plan(snapshot).map { item -> item.audio })
    }

    @Test
    fun `an incomplete sidecar listing does not hide finalized audio`() {
        val metadata = finalized.copy(
            coverUrl = "https://example.com/cover.jpg",
            matchedLyric = "[00:01.00]lyric"
        )

        assertEquals(emptyList<ManagedLibraryRebuildItem>(), ManagedLibraryRebuilder.plan(snapshot(metadata)))
        assertEquals(
            listOf(audio),
            ManagedLibraryRebuilder.plan(snapshot(metadata).copy(sidecarEntriesComplete = false))
                .map { item -> item.audio }
        )
    }

    private fun snapshot(metadata: DownloadedAudioMetadata) = ManagedDownloadStorage.DownloadLibrarySnapshot(
        audioEntries = listOf(audio),
        audioEntriesByLookupKey = mapOf(audio.reference to audio),
        metadataEntriesByAudioName = emptyMap(),
        metadataByAudioName = mapOf(audio.name to metadata),
        audioEntriesWithoutMetadata = emptyList(),
        audioEntriesByStableKey = emptyMap(),
        audioEntriesBySongId = emptyMap(),
        audioEntriesByMediaUri = emptyMap(),
        audioEntriesByRemoteTrackKey = emptyMap(),
        coverEntriesByName = emptyMap(),
        lyricEntriesByName = emptyMap(),
        knownReferences = setOf(audio.reference)
    )
}
