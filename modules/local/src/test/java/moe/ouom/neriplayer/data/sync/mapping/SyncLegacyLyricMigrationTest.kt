package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertThrows
import android.content.Context
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.history.toPlayedEntry
import org.mockito.Mockito.mock

class SyncLegacyLyricMigrationTest {
    private val song = SongItem(1L, "song", "artist", "netease", 1L, 100L, null)

    @Test
    fun `old matching is preserved when its edit provenance is unknown`() {
        val automatic = song.copy(
            matchedLyric = "automatically fetched lyrics",
            matchedTranslatedLyric = "automatically fetched translation",
            matchedRomanizedLyric = "automatically fetched romanized",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )
        val snapshot = SyncSong.fromSongItem(automatic)
        assertEquals(automatic.matchedLyric, snapshot.matchedLyric)
        assertEquals(automatic.matchedTranslatedLyric, snapshot.matchedTranslatedLyric)
        assertEquals(automatic.matchedRomanizedLyric, snapshot.matchedRomanizedLyric)
        assertEquals(true, snapshot.lyricSyncEdited)
        assertEquals(1L, snapshot.lyricSyncRevision)
        assertEquals("42", snapshot.matchedSongId)
    }

    @Test
    fun `unconfirmed local lyrics remain available after a metadata only round trip`() {
        val unknown = song.copy(matchedLyric = "unconfirmed old lyrics", matchedTranslatedLyric = "old translation")
        val snapshot = SyncSong.fromSongItem(unknown)
        assertEquals(unknown.matchedLyric, snapshot.matchedLyric)
        val restored = snapshot.toSongItem(unknown)
        assertEquals(unknown.matchedLyric, restored.matchedLyric)
        assertEquals(unknown.matchedTranslatedLyric, restored.matchedTranslatedLyric)
        assertEquals(true, restored.lyricSyncEdited)
        assertNull(restored.originalLyric)
    }

    @Test
    fun `explicit optimization omits ambiguous old lyrics but keeps bilibili and confirmed edits`() {
        val unknown = song.copy(matchedLyric = "old lyrics", originalLyric = "original")
        val optimized = SyncSong.fromSongItem(unknown, optimizeLegacyLyrics = true)
        assertNull(optimized.matchedLyric)
        assertNull(optimized.originalLyric)
        assertFalse(optimized.lyricSyncEdited == true)
        val bilibili = SyncSong.fromSongItem(unknown.copy(album = "Bilibili", channelId = "bilibili", lyricSyncEdited = false),
            optimizeLegacyLyrics = true)
        assertEquals("old lyrics", bilibili.matchedLyric)
        assertEquals("original", bilibili.originalLyric)
        assertEquals(true, bilibili.lyricSyncEdited)
        assertEquals("old lyrics", SyncSong.fromSongItem(unknown.copy(lyricSyncEdited = true),
            optimizeLegacyLyrics = true).matchedLyric)
    }

    @Test
    fun `baseline only old lyrics survive the initial snapshot and first restore`() {
        val unknown = song.copy(originalLyric = "original", originalTranslatedLyric = "translated",
            originalRomanizedLyric = "romanized")
        val snapshot = SyncSong.fromSongItem(unknown)
        val restored = snapshot.toSongItem()
        assertEquals("original", restored.matchedLyric)
        assertEquals("translated", restored.matchedTranslatedLyric)
        assertEquals("romanized", restored.matchedRomanizedLyric)
        assertEquals("original", restored.originalLyric)
        assertEquals(1L, restored.lyricSyncRevision)
        assertEquals(true, restored.lyricSyncEdited)
    }

    @Test
    fun `positive restore and deletion retain every old local candidate before returning replacements`() {
        val first = song.copy(matchedLyric = "old first", matchedRomanizedLyric = "old romanized")
        val second = first.copy(matchedLyric = "old second")
        val removed = song.copy(id = 2, matchedLyric = "removed old lyrics")
        val current = listOf(LocalPlaylist(7, "playlist", mutableListOf(first, second)), LocalPlaylist(8, "deleted", mutableListOf(removed)))
        var retained = emptyList<SyncSong>()
        val incoming = SyncSong.fromSongItem(first.copy(matchedLyric = "confirmed", lyricSyncEdited = true, lyricSyncRevision = 5))
        val result = SyncLocalRestoreMapping(mock(Context::class.java), { retained = it }).playlists(
            SyncData(playlists = listOf(SyncPlaylist(id = 7, songs = listOf(incoming)))), current
        )
        assertEquals("confirmed", result.single().songs.single().matchedLyric)
        assertEquals(setOf("old first", "old second", "removed old lyrics"), retained.map { it.matchedLyric }.toSet())
        assertEquals("old romanized", retained.first().matchedRomanizedLyric)
        assertNull(retained.first().lyricSyncEdited)
        assertEquals(0L, retained.first().lyricSyncRevision)
    }

    @Test
    fun `failed legacy retention prevents playlist and history replacements`() {
        val unknown = song.copy(matchedLyric = "old lyrics")
        val mapping = SyncLocalRestoreMapping(mock(Context::class.java)) { error("durable recovery failed") }
        assertThrows(IllegalStateException::class.java) { mapping.playlists(SyncData(), listOf(LocalPlaylist(7, "playlist", mutableListOf(unknown)))) }
        assertThrows(IllegalStateException::class.java) { mapping.history(SyncData(), listOf(unknown.toPlayedEntry(5))) }
    }
}
