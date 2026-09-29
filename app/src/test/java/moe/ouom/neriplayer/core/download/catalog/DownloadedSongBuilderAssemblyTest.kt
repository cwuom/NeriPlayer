package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

class DownloadedSongBuilderAssemblyTest {
    @Test
    fun `missing snapshot metadata reads the sidecar and retains its logical download time`() = runTest {
        val context = mock(Context::class.java)
        val store = mock(DownloadedAudioMetadataStore::class.java)
        val audio = entry("Artist - Song.flac", "/downloads/Artist - Song.flac")
        `when`(store.read(context, audio, null)).thenReturn(DownloadedAudioMetadata(
            name = "Sidecar title", album = "Sidecar album", downloadTimeMs = 456L
        ))

        val song = DownloadedSongBuilder(store, "test").build(
            context, audio, emptySnapshot(audio), allowSlowLocalInspection = false
        )

        assertEquals("Sidecar title", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Sidecar album", song.album)
        assertEquals(456L, song.downloadTime)
        verify(store).read(context, audio, null)
        verifyNoInteractions(context)
    }

    @Test
    fun `absent persisted time falls back to the current clock with localized album`() = runTest {
        val context = mock(Context::class.java)
        `when`(context.getString(R.string.local_files)).thenReturn("Localized files")
        val store = mock(DownloadedAudioMetadataStore::class.java)
        val audio = entry("Artist - Song.flac", "/downloads/Artist - Song.flac").copy(lastModifiedMs = 0L)
        val before = System.currentTimeMillis()

        val song = DownloadedSongBuilder(store, "test").build(
            context, audio, emptySnapshot(audio), allowSlowLocalInspection = false
        )

        assertTrue(song.downloadTime in before..System.currentTimeMillis())
        assertEquals("Localized files", song.album)
        assertEquals("Song", song.name)
        verify(store).read(context, audio, null)
    }

    @Test
    fun `fast snapshot hydration passes metadata file cover and time to the assembler without IO`() = runTest {
        val context = mock(Context::class.java)
        val metadataStore = mock(DownloadedAudioMetadataStore::class.java)
        val audio = entry("Artist - Song.flac", "/downloads/Artist - Song.flac")
        val cover = entry("Artist - Song.jpg", "/downloads/Covers/Artist - Song.jpg")
        val metadata = DownloadedAudioMetadata(
            songId = 123L, name = "Metadata title", artist = "Metadata artist", album = "Metadata album",
            originalName = "Original title", originalArtist = "Original artist", durationMs = 100L,
            matchedLyric = "", matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanization",
            coverUrl = "https://example.com/cover.jpg", customCoverUrl = "https://example.com/custom.jpg",
            originalCoverUrl = "https://example.com/original.jpg", stableKey = "123|netease|"
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio), audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(), metadataByAudioName = mapOf(audio.name to metadata),
            audioEntriesWithoutMetadata = emptyList(), audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(), audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(), coverEntriesByName = mapOf(cover.name to cover),
            lyricEntriesByName = emptyMap(), knownReferences = setOf(audio.reference, cover.reference)
        )

        val song = DownloadedSongBuilder(metadataStore, "test").build(
            context, audio, snapshot, existingDownloadTime = 987L,
            loadLyricContents = false, resolveLyricFallbacks = false,
            allowSlowLocalInspection = false, verifySnapshotReferences = false
        )

        assertEquals("Metadata title", song.name)
        assertEquals("Metadata artist", song.artist)
        assertEquals("Metadata album", song.album)
        assertEquals("Original title", song.originalName)
        assertEquals(100L, song.durationMs)
        assertEquals(987L, song.downloadTime)
        assertEquals(audio.reference, song.filePath)
        assertEquals(audio.mediaUri, song.mediaUri)
        assertEquals(audio.sizeBytes, song.fileSize)
        assertEquals(audio.logicalName, song.localFileName)
        assertEquals(cover.reference, song.coverPath)
        assertEquals(metadata.coverUrl, song.coverUrl)
        assertEquals(metadata.customCoverUrl, song.customCoverUrl)
        assertEquals(metadata.originalCoverUrl, song.originalCoverUrl)
        assertEquals("", song.matchedLyric)
        assertEquals("translation", song.matchedTranslatedLyric)
        assertEquals("romanization", song.matchedRomanizedLyric)
        assertEquals("123|netease|", song.stableKey)
        verifyNoInteractions(context, metadataStore)
    }

    private fun entry(name: String, path: String) = ManagedDownloadStorage.StoredEntry(
        name, path, path, path, sizeBytes = 5L, lastModifiedMs = 6L
    )

    private fun emptySnapshot(audio: ManagedDownloadStorage.StoredEntry) = ManagedDownloadStorage.DownloadLibrarySnapshot(
        audioEntries = listOf(audio), audioEntriesByLookupKey = emptyMap(),
        metadataEntriesByAudioName = emptyMap(), metadataByAudioName = emptyMap(),
        audioEntriesWithoutMetadata = listOf(audio), audioEntriesByStableKey = emptyMap(),
        audioEntriesBySongId = emptyMap(), audioEntriesByMediaUri = emptyMap(),
        audioEntriesByRemoteTrackKey = emptyMap(), coverEntriesByName = emptyMap(),
        lyricEntriesByName = emptyMap(), knownReferences = setOf(audio.reference)
    )
}
