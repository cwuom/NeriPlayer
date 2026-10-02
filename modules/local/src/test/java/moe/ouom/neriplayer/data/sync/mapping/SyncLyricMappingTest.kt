package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLyricMappingTest {
    private fun song(): SongItem = SongItem(
        id = 1L, name = "song", artist = "artist", album = "netease",
        albumId = 1L, durationMs = 100L, coverUrl = null
    )
    @Test
    fun `ordinary cached lyrics are excluded from song snapshot`() {
        val song = SongItem(
            id = 1L, name = "song", artist = "artist", album = "netease",
            albumId = 1L, durationMs = 100L, coverUrl = null,
            matchedLyric = "cached original", matchedTranslatedLyric = "cached translation",
            originalLyric = "cached original", originalTranslatedLyric = "cached translation"
        )

        val snapshot = SyncSong.fromSongItem(song)

        assertNull(snapshot.matchedLyric)
        assertNull(snapshot.matchedTranslatedLyric)
        assertNull(snapshot.originalLyric)
        assertNull(snapshot.originalTranslatedLyric)
    }

    @Test
    fun `edited lyrics include all variants but exclude original cache`() {
        val snapshot = SyncSong.fromSongItem(song().copy(
            matchedLyric = "edit", matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            originalLyric = "cached", lyricSyncEdited = true, lyricSyncRevision = 20L
        ))

        assertEquals("edit", snapshot.matchedLyric)
        assertEquals("translation", snapshot.matchedTranslatedLyric)
        assertEquals("romanized", snapshot.matchedRomanizedLyric)
        assertEquals(20L, snapshot.lyricSyncRevision)
        assertTrue(snapshot.lyricSyncEdited == true)
        assertNull(snapshot.originalLyric)
    }

    @Test
    fun `explicit ordinary cache is omitted even when original baseline differs`() {
        val snapshot = SyncSong.fromSongItem(song().copy(
            matchedLyric = "refreshed cache", originalLyric = "older cache", lyricSyncEdited = false
        ))
        assertNull(snapshot.matchedLyric)
        assertFalse(snapshot.lyricSyncEdited == true)
    }

    @Test
    fun `confirmed old override receives an edit revision without uploading its baseline`() {
        val snapshot = SyncSong.fromSongItem(song().copy(matchedLyric = "edit", originalLyric = "base", lyricSyncEdited = true))
        assertEquals("edit", snapshot.matchedLyric)
        assertEquals(1L, snapshot.lyricSyncRevision)
        assertTrue(snapshot.lyricSyncEdited == true)
    }

    @Test
    fun `omitted remote cache preserves local full lyrics`() {
        val existing = song().copy(
            matchedLyric = "cache", matchedTranslatedLyric = "translated", matchedRomanizedLyric = "romanized",
            lyricSyncEdited = false
        )
        val restored = SyncSong.fromSongItem(existing).toSongItem(existing)
        assertEquals("cache", restored.matchedLyric)
        assertEquals("translated", restored.matchedTranslatedLyric)
        assertEquals("romanized", restored.matchedRomanizedLyric)
    }

    @Test
    fun `remote reset clears edited variants while keeping original cache`() {
        val existing = song().copy(
            matchedLyric = "edit", matchedRomanizedLyric = "edited romanized",
            originalLyric = "base", originalRomanizedLyric = "base romanized",
            lyricSyncEdited = true, lyricSyncRevision = 10L
        )
        val reset = SyncSong.fromSongItem(existing.copy(lyricSyncEdited = false, lyricSyncRevision = 20L))
        val restored = reset.toSongItem(existing)
        assertNull(reset.matchedLyric)
        assertEquals("base", restored.matchedLyric)
        assertEquals("base romanized", restored.matchedRomanizedLyric)
        assertEquals(20L, restored.lyricSyncRevision)
        assertFalse(restored.lyricSyncEdited == true)
    }

    @Test
    fun `repeated reset keeps a known ordinary cache without promoting unknown legacy edits`() {
        val reset = SyncSong.fromSongItem(song().copy(lyricSyncEdited = false, lyricSyncRevision = 20L))
        val cached = song().copy(matchedLyric = "ordinary cache", lyricSyncEdited = false)
        assertEquals("ordinary cache", reset.toSongItem(cached).matchedLyric)
        val legacyEdit = song().copy(matchedLyric = "unknown legacy edit")
        assertNull(reset.toSongItem(legacyEdit).matchedLyric)
        assertNull(reset.toSongItem(legacyEdit).originalLyric)
    }

    @Test
    fun `old remote edits cannot replace a newer local reset`() {
        val existing = song().copy(matchedLyric = "base", originalLyric = "base", lyricSyncEdited = false, lyricSyncRevision = 20L)
        val old = SyncSong.fromSongItem(existing.copy(matchedLyric = "old edit", lyricSyncEdited = true, lyricSyncRevision = 10L))
        val restored = old.toSongItem(existing)
        assertEquals("base", restored.matchedLyric)
        assertEquals(20L, restored.lyricSyncRevision)
        assertFalse(restored.lyricSyncEdited == true)
    }

    @Test
    fun `remote edits retain local cache baselines for every variant and later reset`() {
        val existing = song().copy(
            matchedLyric = "local cache", matchedTranslatedLyric = "local translation", matchedRomanizedLyric = "local romanized",
            lyricSyncEdited = false
        )
        val edit = SyncSong.fromSongItem(song().copy(
            matchedLyric = "edit", matchedTranslatedLyric = "translated edit", matchedRomanizedLyric = "romanized edit",
            lyricSyncEdited = true, lyricSyncRevision = 20L
        ))
        val applied = edit.toSongItem(existing)
        assertEquals("edit", applied.matchedLyric)
        assertEquals("local cache", applied.originalLyric)
        assertEquals("local translation", applied.originalTranslatedLyric)
        assertEquals("local romanized", applied.originalRomanizedLyric)
        val reset = edit.copy(lyricSyncEdited = false, lyricSyncRevision = 21L,
            matchedLyric = null, matchedTranslatedLyric = null, matchedRomanizedLyric = null)
        assertEquals("local cache", reset.toSongItem(applied).matchedLyric)
        assertEquals("local translation", reset.toSongItem(applied).matchedTranslatedLyric)
        assertEquals("local romanized", reset.toSongItem(applied).matchedRomanizedLyric)
    }

    @Test
    fun `favorite restore preserves local caches by identity and handles first download`() {
        val cached = song().copy(matchedLyric = "local cache", lyricSyncEdited = false)
        val existing = FavoritePlaylist(1L, "favorite", null, 1, "netease", songs = listOf(cached))
        val remote = SyncFavoritePlaylist(id = 1L, songs = listOf(SyncSong.fromSongItem(cached)))
        assertEquals("local cache", remote.toFavoritePlaylist(existing).songs.single().matchedLyric)
        assertNull(remote.toFavoritePlaylist().songs.single().matchedLyric)
        val edited = remote.copy(songs = listOf(SyncSong.fromSongItem(cached.copy(
            matchedLyric = "user edit", lyricSyncEdited = true, lyricSyncRevision = 20L
        ))))
        val restored = edited.toFavoritePlaylist(existing).songs.single()
        assertEquals("user edit", restored.matchedLyric)
        assertEquals("local cache", restored.originalLyric)
    }
}
