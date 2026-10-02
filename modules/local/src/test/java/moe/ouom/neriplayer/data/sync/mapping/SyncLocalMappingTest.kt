package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class SyncLocalMappingTest {
    @Test
    fun `playlist restoration preserves local songs and custom cover while replacing remote songs`() {
        val mapper = SyncLocalRestoreMapping(mock(Context::class.java))
        val local = song(2).copy(mediaUri = "content://local/2")
        val current = LocalPlaylist(10, "old", mutableListOf(song(9), local), customCoverUrl = "file:/cover")
        val remote = SyncPlaylist(10, "remote", songs = listOf(SyncSong.fromSongItem(song(1)), SyncSong.fromSongItem(song(1))), modifiedAt = 40)
        val data = SyncData(playlists = listOf(remote, remote.copy(id = 11), remote.copy(id = 12, isDeleted = true)))
        val restored = mapper.playlists(data, listOf(current))
        assertEquals(listOf(10L, 11L), restored.map { it.id })
        assertEquals(listOf(1L, 2L), restored.first().songs.map { it.id })
        assertEquals("file:/cover", restored.first().customCoverUrl)
        assertNull(restored.last().customCoverUrl)
        assertEquals("remote", restored.first().name)
        assertEquals(40L, restored.first().modifiedAt)
        assertTrue(mapper.playlists(SyncData(), emptyList()).isEmpty())
    }

    @Test
    fun `history restoration excludes remote local references and retains unique local history`() {
        val mapper = SyncLocalRestoreMapping(mock(Context::class.java))
        val remote = SyncRecentPlay(songId = 1, song = SyncSong.fromSongItem(song(1)), playedAt = 20, resumePositionMs = 8)
        val invalid = remote.copy(song = remote.song.copy(mediaUri = "content://remote-local/1"))
        val local = PlayedEntry(2, "local", "artist", "album", 1, 10, coverUrl = null, mediaUri = "content://local/2", playedAt = 30)
        val previousRemote = local.copy(id = 9, mediaUri = "https://remote.test/9")
        val result = mapper.history(SyncData(recentPlays = listOf(remote, remote, invalid)), listOf(local, previousRemote))
        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(8L, result.last().resumePositionMs)
        assertTrue(mapper.history(SyncData(), emptyList()).isEmpty())
        val complete = mapper.history(SyncData(recentPlays = (1..501).map { remote.copy(playedAt = it.toLong()) }), emptyList())
        assertEquals(501, complete.size)
        assertEquals(501L, complete.first().playedAt)
    }

    @Test
    fun `snapshot mappings retain deletion identity local filtering and remote cover fallback`() {
        val playlist = LocalPlaylist(10, "playlist", mutableListOf(song(1), song(2).copy(mediaUri = "content://local/2")))
        assertEquals(listOf(1L), SyncPlaylist.fromLocalPlaylist(playlist, 50).songs.map { it.id })
        val systemPlaylist = mapResolvedLocalPlaylist(playlist, 50, SystemLocalPlaylists.Descriptor(-1001, "favorites"), null)
        assertEquals(-1001L, systemPlaylist.id)
        assertEquals("favorites", systemPlaylist.name)
        val favorite = FavoritePlaylist(20, "favorite", null, 100, "netease", songs = playlist.songs)
        val active = SyncFavoritePlaylist.fromFavoritePlaylist(favorite)
        assertEquals(1, active.trackCount)
        assertEquals(song(1).coverUrl, active.coverUrl)
        val empty = SyncFavoritePlaylist.fromFavoritePlaylist(favorite.copy(songs = emptyList()))
        assertNull(empty.coverUrl)
        assertEquals(100, empty.trackCount)
        val deleted = SyncFavoritePlaylist.fromFavoritePlaylist(favorite.copy(isDeleted = true))
        assertTrue(deleted.isDeleted)
        assertTrue(deleted.songs.isEmpty())
        assertEquals(0, deleted.trackCount)
        val validRule = SyncBiliVideoSkipRule("BV1test", 42, listOf(SyncBiliVideoSkipInterval(1, 10)), modifiedAt = 20)
        assertEquals(validRule, validRule.toBiliVideoSkipRuleOrNull()?.toSyncBiliVideoSkipRule())
        assertNull(validRule.copy(bvid = "", cid = 0).toBiliVideoSkipRuleOrNull())
        assertNull(validRule.copy(intervals = emptyList()).toBiliVideoSkipRuleOrNull())
        val tombstone = validRule.copy(isDeleted = true, modifiedAt = -1).toBiliVideoSkipRuleOrNull()!!
        assertTrue(tombstone.isDeleted)
        assertTrue(tombstone.intervals.isEmpty())
        assertEquals(0L, tombstone.modifiedAt)
    }

    private fun song(id: Long) = SongItem(id, "song", "artist", "netease", 1, 10, "https://cover.test/a", channelId = "netease", audioId = id.toString())
}
