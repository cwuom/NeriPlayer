package moe.ouom.neriplayer.core.download.storage.sidecar

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadLyricFallbackSelectionTest {
    private val store = ManagedDownloadLyricStore

    @Test
    fun `indexed lyric lookup prefers the song id name then the audio base names`() {
        val byId = lyricEntry("42.lrc")
        val byUnsetId = lyricEntry("0.lrc")
        val byBaseName = lyricEntry("Artist - Song.lrc")
        val translatedByBaseName = lyricEntry("Artist - Song_trans.lrc.txt")
        val snapshot = snapshot(byId, byUnsetId, byBaseName, translatedByBaseName)

        assertEquals(
            byId.reference,
            store.findLyricLocation(snapshot, songId = 42L, candidateBaseNames = listOf("Artist - Song"), translated = false)
        )
        assertEquals(
            byBaseName.reference,
            store.findLyricLocation(
                snapshot,
                songId = 0L,
                candidateBaseNames = listOf("Missing", "Artist - Song"),
                translated = false
            )
        )
        assertEquals(
            translatedByBaseName.reference,
            store.findLyricLocation(snapshot, songId = 42L, candidateBaseNames = listOf("Artist - Song"), translated = true)
        )
        assertNull(store.findLyricLocation(snapshot, songId = -1L, candidateBaseNames = listOf("Other"), translated = true))
    }

    @Test
    fun `embedded lyric fallback prefers matched lyrics over original ones`() {
        val metadata = DownloadedAudioMetadata(
            matchedLyric = "matched",
            originalLyric = "original",
            matchedTranslatedLyric = "matched-trans",
            originalTranslatedLyric = "original-trans",
            matchedRomanizedLyric = "matched-roma",
            originalRomanizedLyric = "original-roma"
        )

        assertEquals("matched", store.fallbackEmbeddedLyric(metadata, translated = false))
        assertEquals("matched-trans", store.fallbackEmbeddedLyric(metadata, translated = true))
        assertEquals("matched-roma", store.fallbackEmbeddedRomanizedLyric(metadata))
        assertEquals("matched", store.selectedEmbeddedLyric(metadata, translated = false))
        assertEquals("matched-trans", store.selectedEmbeddedLyric(metadata, translated = true))
        assertEquals("matched-roma", store.selectedEmbeddedRomanizedLyric(metadata))
    }

    @Test
    fun `unmatched songs fall back to the original lyric but select nothing`() {
        val metadata = DownloadedAudioMetadata(
            originalLyric = "original",
            originalTranslatedLyric = "original-trans",
            originalRomanizedLyric = "original-roma"
        )

        assertEquals("original", store.fallbackEmbeddedLyric(metadata, translated = false))
        assertEquals("original-trans", store.fallbackEmbeddedLyric(metadata, translated = true))
        assertEquals("original-roma", store.fallbackEmbeddedRomanizedLyric(metadata))
        assertNull(store.selectedEmbeddedLyric(metadata, translated = false))
        assertNull(store.selectedEmbeddedLyric(metadata, translated = true))
        assertNull(store.selectedEmbeddedRomanizedLyric(metadata))
    }

    @Test
    fun `missing metadata provides no embedded lyric`() {
        assertNull(store.fallbackEmbeddedLyric(null, translated = false))
        assertNull(store.fallbackEmbeddedLyric(null, translated = true))
        assertNull(store.fallbackEmbeddedRomanizedLyric(null))
        assertNull(store.selectedEmbeddedLyric(null, translated = false))
        assertNull(store.selectedEmbeddedLyric(null, translated = true))
        assertNull(store.fallbackEmbeddedLyric(DownloadedAudioMetadata(), translated = true))
    }

    private fun lyricEntry(name: String): ManagedDownloadStorage.StoredEntry {
        val reference = "content://downloads/Lyrics/$name"
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
    }

    private fun snapshot(
        vararg lyricEntries: ManagedDownloadStorage.StoredEntry
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
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = lyricEntries.associateBy(ManagedDownloadStorage.StoredEntry::name),
            knownReferences = lyricEntries.map(ManagedDownloadStorage.StoredEntry::reference).toSet()
        )
    }
}
