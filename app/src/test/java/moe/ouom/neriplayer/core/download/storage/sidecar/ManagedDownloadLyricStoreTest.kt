package moe.ouom.neriplayer.core.download.storage.sidecar

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadLyricStoreTest {
    private val context = mock(Context::class.java)
    private val song = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 1_000L,
        coverUrl = null
    )

    @Test
    fun `stale metadata lyrics outside the current snapshot are never probed`() {
        val staleOriginal = "content://downloads-old/Lyrics/song.lrc"
        val staleRomanized = "content://downloads-old/Lyrics/song_roma.lrc"
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            lyricPath = staleOriginal,
            romanizedLyricPath = staleRomanized
        )
        val probed = mutableListOf<String?>()

        val original = ManagedDownloadLyricStore.resolveManagedLyricReference(
            context = context,
            snapshot = emptySnapshot(),
            song = song,
            resolvedAudio = null,
            resolvedMetadata = metadata,
            translated = false,
            fileNameTemplate = null,
            exists = { _, reference ->
                probed += reference
                true
            }
        )
        val romanized = ManagedDownloadLyricStore.resolveManagedRomanizedLyricReference(
            context = context,
            snapshot = emptySnapshot(),
            song = song,
            resolvedAudio = null,
            resolvedMetadata = metadata,
            fileNameTemplate = null,
            exists = { _, reference ->
                probed += reference
                true
            }
        )

        assertNull(original)
        assertNull(romanized)
        assertEquals(emptyList<String?>(), probed)
    }

    @Test
    fun `metadata lyric is probed when the current snapshot enumerated it`() {
        val currentReference = "content://downloads-new/Lyrics/song.lrc"
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            lyricPath = currentReference
        )
        val probed = mutableListOf<String?>()

        val resolved = ManagedDownloadLyricStore.resolveManagedLyricReference(
            context = context,
            snapshot = emptySnapshot(knownReferences = setOf(currentReference)),
            song = song,
            resolvedAudio = null,
            resolvedMetadata = metadata,
            translated = false,
            fileNameTemplate = null,
            exists = { _, reference ->
                probed += reference
                true
            }
        )

        assertEquals(currentReference, resolved)
        assertEquals(listOf(currentReference), probed)
    }

    private fun emptySnapshot(
        knownReferences: Set<String> = emptySet()
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
            lyricEntriesByName = emptyMap(),
            knownReferences = knownReferences
        )
    }
}
