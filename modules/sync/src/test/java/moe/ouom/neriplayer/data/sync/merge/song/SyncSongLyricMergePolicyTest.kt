package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SyncSongLyricMergePolicyTest {
    @Test
    fun `ordinary unedited snapshots retain their existing song and playlist storage`() {
        val songs = (1L..1000L).map { SyncSong(id = it, lyricSyncEdited = false) }
        val data = SyncData(playlists = listOf(SyncPlaylist(songs = songs)))
        assertSame(data, SyncSongLyricMergePolicy.converge(data))
    }
    @Test
    fun `reset registry survives removal of every song copy and rejects a later stale playlist`() {
        val reset = SyncSong(id = 1L, lyricSyncEdited = false, lyricSyncRevision = 20L)
        val empty = SyncSongLyricMergePolicy.converge(SyncData(lyricOverrides = listOf(reset)))
        val stale = reset.copy(matchedLyric = "stale", lyricSyncEdited = true, lyricSyncRevision = 10L)
        val result = SyncSongLyricMergePolicy.converge(empty.copy(playlists = listOf(SyncPlaylist(songs = listOf(stale)))))
        assertEquals(1, empty.lyricOverrides.size)
        assertNull(result.playlists.single().songs.single().matchedLyric)
        assertEquals(20L, result.lyricOverrides.single().lyricSyncRevision)
    }

    @Test
    fun `override registry excludes cache and strips unrelated song metadata`() {
        val edit = SyncSong(id = 1L, name = "song title", coverUrl = "https://example.com", lyricSyncEdited = true,
            lyricSyncRevision = 20L, matchedLyric = "edit", originalLyric = "cache")
        val cached = SyncSong(id = 2L, matchedLyric = "cache", lyricSyncEdited = false)
        val records = SyncSongLyricMergePolicy.mergeOverrides(listOf(edit, cached))
        assertEquals(1, records.size)
        assertEquals("edit", records.single().matchedLyric)
        assertEquals("", records.single().name)
        assertNull(records.single().coverUrl)
        assertEquals("cache", records.single().originalLyric)
    }
    @Test
    fun `reset propagates from one playlist to stale copies in favorites and history`() {
        val reset = SyncSong(id = 1L, lyricSyncEdited = false, lyricSyncRevision = 20L)
        val stale = reset.copy(matchedLyric = "old edit", lyricSyncEdited = true, lyricSyncRevision = 10L)
        val data = SyncData(
            playlists = listOf(SyncPlaylist(id = 1L, songs = listOf(reset))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2L, songs = listOf(stale))),
            recentPlays = listOf(SyncRecentPlay(song = stale, playedAt = 100L))
        )
        val result = SyncSongLyricMergePolicy.converge(data)
        assertNull(result.favoritePlaylists.single().songs.single().matchedLyric)
        assertNull(result.recentPlays.single().song.matchedLyric)
        assertEquals(20L, result.recentPlays.single().song.lyricSyncRevision)
        assertEquals(result, SyncSongLyricMergePolicy.converge(result))
    }
    @Test
    fun `newer song metadata cannot resurrect a reset lyric override`() {
        val reset = SyncSong(id = 1L, lyricSyncEdited = false, lyricSyncRevision = 20L)
        val stale = reset.copy(name = "new title", matchedLyric = "old edit", lyricSyncEdited = true, lyricSyncRevision = 10L)
        val merged = SyncSongMetadataMergePolicy.resolveSelectedPayload(stale, listOf(stale, reset))
        assertEquals("new title", merged.name)
        assertEquals(20L, merged.lyricSyncRevision)
        assertEquals(false, merged.lyricSyncEdited)
        assertNull(merged.matchedLyric)
    }

    @Test
    fun `same revision collisions converge in either merge order`() {
        val a = SyncSong(id = 1L, lyricSyncEdited = true, lyricSyncRevision = 20L, matchedLyric = "a")
        val b = a.copy(matchedLyric = "b")
        val left = SyncSongLyricMergePolicy.merge(a, listOf(a, b))
        val right = SyncSongLyricMergePolicy.merge(b, listOf(b, a))
        assertEquals("b", left.matchedLyric)
        assertEquals(left, right)
    }

    @Test
    fun `concurrent changes to every lyric variant and match reference converge without loss`() {
        val base = SyncSong(id = 1L, lyricSyncEdited = true, lyricSyncRevision = 20L)
        val edits = listOf(
            base.copy(matchedLyric = "original edit"),
            base.copy(matchedTranslatedLyric = "translated edit"),
            base.copy(matchedRomanizedLyric = "romanized edit"),
            base.copy(originalLyric = "original baseline"),
            base.copy(originalTranslatedLyric = "translation baseline"),
            base.copy(originalRomanizedLyric = "romanized baseline"),
            base.copy(matchedLyricSource = "NETEASE"),
            base.copy(matchedSongId = "matched song")
        )
        edits.forEach { edit ->
            val merged = SyncSongLyricMergePolicy.merge(base, listOf(base, edit))
            assertEquals(edit, merged)
            assertEquals(merged, SyncSongLyricMergePolicy.merge(edit, listOf(edit, base)))
        }
    }

    @Test
    fun `normalization strips all cache variants while preserving empty edits and stable objects`() {
        val cache = SyncSong(
            matchedLyric = "cache", matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            originalLyric = "older cache", originalTranslatedLyric = "older translation", lyricSyncEdited = false
        )
        val normalized = SyncSongLyricMergePolicy.normalize(cache)
        assertNull(normalized.matchedLyric)
        assertNull(normalized.matchedTranslatedLyric)
        assertNull(normalized.matchedRomanizedLyric)
        assertNull(normalized.originalTranslatedLyric)
        assertSame(normalized, SyncSongLyricMergePolicy.normalize(normalized))
        val emptyEdit = SyncSong(lyricSyncEdited = true, lyricSyncRevision = -1L)
        assertEquals(1L, SyncSongLyricMergePolicy.normalize(emptyEdit).lyricSyncRevision)
        val stableEdit = emptyEdit.copy(lyricSyncRevision = 10L)
        assertSame(stableEdit, SyncSongLyricMergePolicy.normalize(stableEdit))
        val legacyEmpty = SyncSong()
        assertSame(legacyEmpty, SyncSongLyricMergePolicy.normalize(legacyEmpty))
    }

    @Test
    fun `same revision reset wins over an edit`() {
        val edit = SyncSong(lyricSyncEdited = true, lyricSyncRevision = 20L, matchedLyric = "edit")
        val reset = edit.copy(lyricSyncEdited = false, matchedLyric = null)
        assertEquals(reset, SyncSongLyricMergePolicy.merge(edit, listOf(edit, reset)))
    }

    @Test
    fun `legacy baseline and unknown matching are preserved conservatively`() {
        val cache = SyncSong(matchedLyric = "base", originalLyric = "base")
        val edit = cache.copy(matchedLyric = "edit")
        assertEquals("base", SyncSongLyricMergePolicy.normalize(cache).matchedLyric)
        assertEquals("edit", SyncSongLyricMergePolicy.normalize(edit).matchedLyric)
        assertEquals(1L, SyncSongLyricMergePolicy.normalize(edit).lyricSyncRevision)
        assertEquals("edit", SyncSongLyricMergePolicy.normalize(edit.copy(lyricSyncEdited = true)).matchedLyric)
        assertEquals("base", SyncSongLyricMergePolicy.normalize(edit).originalLyric)
    }
}
