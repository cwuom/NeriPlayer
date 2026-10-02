package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.sync.hasLegacyLyricOverride
import moe.ouom.neriplayer.data.model.sync.hasUserEditedLyricsForSync
import moe.ouom.neriplayer.data.model.sync.nextLyricSyncRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLyricProvenanceTest {
    private val song = SongItem(1L, "song", "artist", "netease", 1L, 100L, null)

    @Test
    fun `known provenance overrides old matching hints and allows an intentional empty edit`() {
        val cache = song.copy(matchedLyric = "cache", matchedLyricSource = MusicPlatform.entries.first())
        assertFalse(cache.hasUserEditedLyricsForSync())
        assertFalse(cache.copy(lyricSyncEdited = false).hasUserEditedLyricsForSync())
        assertTrue(song.copy(lyricSyncEdited = true).hasUserEditedLyricsForSync())
        assertFalse(song.hasUserEditedLyricsForSync())
    }

    @Test
    fun `each legacy lyric variant preserves a modification and a missing baseline`() {
        assertFalse(hasLegacyLyricOverride(null, null, null, null, null, null, "manual"))
        assertFalse(hasLegacyLyricOverride("base", "translation", "romanized", "base", "translation", "romanized", null))
        assertTrue(hasLegacyLyricOverride("edit", "translation", "romanized", "base", "translation", "romanized", null))
        assertTrue(hasLegacyLyricOverride("base", "edit", "romanized", "base", "translation", "romanized", null))
        assertTrue(hasLegacyLyricOverride("base", "translation", "edit", "base", "translation", "romanized", null))
        assertTrue(hasLegacyLyricOverride("base", null, null, "base", null, null, "manual"))
        assertTrue(hasLegacyLyricOverride("", null, null, null, null, null, null))
        assertTrue(hasLegacyLyricOverride(null, "legacy translation", null, null, null, null, null))
        assertTrue(hasLegacyLyricOverride(null, null, "legacy romanized", null, null, null, null))
    }

    @Test
    fun `local revisions increase after clock reversal and reject exhausted versions`() {
        assertEquals(100L, nextLyricSyncRevision(0L, 100L))
        assertEquals(101L, nextLyricSyncRevision(100L, 50L))
        assertEquals(101L, nextLyricSyncRevision(100L, 100L))
        assertEquals(1L, nextLyricSyncRevision(-1L, -100L))
        assertEquals(Long.MAX_VALUE, nextLyricSyncRevision(Long.MAX_VALUE - 1L, 0L))
        assertThrows(IllegalStateException::class.java) { nextLyricSyncRevision(Long.MAX_VALUE, 0L) }
    }
}
