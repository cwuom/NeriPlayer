package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncArchiveLyricPreservationProjectionTest {
    @Test
    fun `song references omit all full text and restore both edits and originals from the shared record`() {
        val override = SyncSong(id = 1, album = "netease", lyricSyncEdited = true, lyricSyncRevision = 1,
            matchedLyric = "edited", matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            originalLyric = "base", originalTranslatedLyric = "base translation", originalRomanizedLyric = "base romanized")

        val reference = SyncArchiveLyricProjection.reference(override)

        assertNull(reference.matchedLyric)
        assertNull(reference.matchedTranslatedLyric)
        assertNull(reference.matchedRomanizedLyric)
        assertNull(reference.originalLyric)
        assertNull(reference.originalTranslatedLyric)
        assertNull(reference.originalRomanizedLyric)
        val data = SyncData(playlists = listOf(SyncPlaylist(songs = listOf(reference))), lyricOverrides = listOf(override))
        val restored = SyncArchiveLyricProjection.restore(data)
        assertEquals(override, restored.playlists.single().songs.single())
        assertEquals(override, restored.lyricOverrides.single())
    }
}
