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
    fun `old automatic matching does not become a confirmed lyric edit`() {
        val automatic = song.copy(
            matchedLyric = "automatically fetched lyrics",
            matchedTranslatedLyric = "automatically fetched translation",
            matchedRomanizedLyric = "automatically fetched romanized",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )
        val snapshot = SyncSong.fromSongItem(automatic)
        assertNull(snapshot.matchedLyric)
        assertNull(snapshot.matchedTranslatedLyric)
        assertNull(snapshot.matchedRomanizedLyric)
        assertFalse(snapshot.lyricSyncEdited == true)
        assertEquals(0L, snapshot.lyricSyncRevision)
        assertEquals("42", snapshot.matchedSongId)
    }

    @Test
    fun `unconfirmed local lyrics remain available after a metadata only round trip`() {
        val unknown = song.copy(matchedLyric = "unconfirmed old lyrics", matchedTranslatedLyric = "old translation")
        val snapshot = SyncSong.fromSongItem(unknown)
        assertNull(snapshot.matchedLyric)
        val restored = snapshot.toSongItem(unknown)
        assertEquals(unknown.matchedLyric, restored.matchedLyric)
        assertEquals(unknown.matchedTranslatedLyric, restored.matchedTranslatedLyric)
        assertNull(restored.lyricSyncEdited)
        assertNull(restored.originalLyric)
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
