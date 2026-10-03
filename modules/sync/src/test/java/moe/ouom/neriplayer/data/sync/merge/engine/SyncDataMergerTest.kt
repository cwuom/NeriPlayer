package moe.ouom.neriplayer.data.sync.merge.engine

import moe.ouom.neriplayer.data.sync.identity.identity

import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.model.sync.ConflictResolution
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncLogEntry
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDataMergerTest {
    private val merger = SyncDataMerger(TestSyncMergeHost()) { 999L }

    @Test
    fun `empty merge keeps the local device identity and uses the injected clock`() {
        val local = snapshot().copy(deviceId = "local", deviceName = "phone")
        val result = merger.merge(local, snapshot().copy(deviceId = "remote"), 100L)
        assertEquals("local", result.mergedData.deviceId)
        assertEquals("phone", result.mergedData.deviceName)
        assertEquals(999L, result.mergedData.lastModified)
        assertTrue(result.mergedData.playlists.isEmpty())
        assertTrue(result.syncResult.success)
        assertEquals(0, result.syncResult.playlistsAdded)
    }

    @Test
    fun `backend presentation changes only result text`() {
        val local = snapshot(playlist(1L))
        val remote = snapshot(playlist(2L))
        val github = SyncDataMerger(TestSyncMergeHost("github")) { 999L }.merge(local, remote, 100L)
        val webdav = SyncDataMerger(TestSyncMergeHost("webdav")) { 999L }.merge(local, remote, 100L)
        assertEquals(github.mergedData, webdav.mergedData)
        assertEquals(github.syncResult.copy(message = "webdav merged"), webdav.syncResult)
        assertEquals("github merged", github.syncResult.message)
    }

    @Test
    fun `unpaired active playlists precede tombstones and retain existing counters`() {
        val local = snapshot(playlist(1L), playlist(2L).copy(isDeleted = true))
        val remote = snapshot(playlist(3L), playlist(4L).copy(isDeleted = true))
        val result = merger.merge(local, remote, 100L)
        assertEquals(listOf(1L, 3L, 2L, 4L), result.mergedData.playlists.map { it.id })
        assertEquals(2, result.syncResult.playlistsAdded)
        assertEquals(2, result.syncResult.playlistsDeleted)
        assertEquals(0, result.syncResult.songsAdded)
    }

    @Test
    fun `remote only playlist cannot reintroduce a locally removed membership`() {
        val deletedSong = song(42L)
        val identity = deletedSong.identity()
        val local = snapshot().copy(playlistSongDeletions = listOf(
            SyncPlaylistSongDeletion(playlistId = 7, songId = identity.id, album = identity.album, deletedAt = 20)
        ))
        val remote = snapshot(playlist(7L).copy(songs = listOf(deletedSong)))

        val merged = merger.merge(local, remote, 100L).mergedData

        assertTrue(merged.playlists.single().songs.isEmpty())
        assertEquals(1, merged.playlistSongDeletions.size)
        assertEquals(merged.playlists, merger.merge(merged, remote, 100L).mergedData.playlists)
    }

    @Test
    fun `remote-only edits choose remote order while simultaneous edits keep local order`() {
        val local = snapshot(playlist(1L, modified = 50L), playlist(2L, modified = 50L))
        val remote = snapshot(playlist(2L), playlist(1L))
        assertEquals(listOf(2L, 1L), merger.merge(local, remote, 100L).mergedData.playlists.map { it.id })
        val editedLocal = local.copy(playlists = local.playlists.map { it.copy(modifiedAt = 300L) })
        assertEquals(listOf(1L, 2L), merger.merge(editedLocal, remote, 100L).mergedData.playlists.map { it.id })
    }

    @Test
    fun `unchanged equal names do not report conflicts`() {
        val data = snapshot(playlist(1L, modified = 50L))
        val result = merger.merge(data, data, 100L)
        assertTrue(result.syncResult.conflicts.isEmpty())
        assertEquals(0, result.syncResult.playlistsUpdated)
    }

    @Test
    fun `single-side rename selects that side and reports the original resolution`() {
        val original = playlist(1L, name = "old", modified = 50L)
        val renamed = original.copy(name = "new", modifiedAt = 200L)
        val remoteWins = merger.merge(snapshot(original), snapshot(renamed), 100L)
        assertEquals("new", remoteWins.mergedData.playlists.single().name)
        assertEquals(ConflictResolution.REMOTE_WINS, remoteWins.syncResult.conflicts.single().resolution)
        assertEquals("remote new", remoteWins.syncResult.conflicts.single().description)
        assertEquals(1, remoteWins.syncResult.playlistsUpdated)
        val localWins = merger.merge(snapshot(renamed), snapshot(original), 100L)
        assertEquals("new", localWins.mergedData.playlists.single().name)
        assertEquals(ConflictResolution.LOCAL_WINS, localWins.syncResult.conflicts.single().resolution)
        assertEquals("local new", localWins.syncResult.conflicts.single().description)
    }

    @Test
    fun `concurrent or untracked name divergence keeps local name for manual resolution`() {
        for (modified in listOf(50L, 200L)) {
            val result = merger.merge(
                snapshot(playlist(1L, name = "local", modified = modified)),
                snapshot(playlist(1L, name = "remote", modified = modified)), 100L
            )
            assertEquals("local", result.mergedData.playlists.single().name)
            assertEquals(ConflictResolution.MANUAL_REQUIRED, result.syncResult.conflicts.single().resolution)
        }
    }

    @Test
    fun `system playlist identity and name come from the host without a rename conflict`() {
        val result = merger.merge(
            snapshot(playlist(-1L, name = "old system name")),
            snapshot(playlist(-1L, name = "translated system name")), 100L
        )
        assertEquals(-1L, result.mergedData.playlists.single().id)
        assertEquals("favorites", result.mergedData.playlists.single().name)
        assertTrue(result.syncResult.conflicts.isEmpty())
    }

    @Test
    fun `newer tombstones win from either side and newer active snapshots restore the playlist`() {
        val active = playlist(1L, modified = 100L)
        val deleted = active.copy(isDeleted = true, modifiedAt = 200L)
        for ((local, remote) in listOf(active to deleted, deleted to active, deleted to deleted)) {
            val result = merger.merge(snapshot(local), snapshot(remote), 50L)
            assertTrue(result.mergedData.playlists.single().isDeleted)
            assertTrue(result.mergedData.playlists.single().songs.isEmpty())
            assertEquals(1, result.syncResult.playlistsDeleted)
        }
        val restored = active.copy(modifiedAt = 300L)
        for ((local, remote) in listOf(restored to deleted, deleted to restored)) {
            val result = merger.merge(snapshot(local), snapshot(remote), 50L)
            assertFalse(result.mergedData.playlists.single().isDeleted)
            assertEquals(1, result.syncResult.playlistsUpdated)
            assertTrue(result.syncResult.conflicts.isEmpty())
        }
    }

    @Test
    fun `deleted playlist resolves remote system names and preserves earliest positive creation time`() {
        val local = playlist(42L, name = "", modified = 200L).copy(isDeleted = true, createdAt = 0L)
        val remote = local.copy(name = "system alias", createdAt = 10L)
        val host = object : TestSyncMergeHost() {
            override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? =
                if (name == "system alias") SyncSystemPlaylist(-1L, "favorites") else null
        }
        val result = SyncDataMerger(host).merge(snapshot(local), snapshot(remote), 100L)
        assertEquals(-1L, result.mergedData.playlists.single().id)
        assertEquals("favorites", result.mergedData.playlists.single().name)
        assertEquals(10L, result.mergedData.playlists.single().createdAt)
        val ordinary = merger.merge(snapshot(local), snapshot(remote.copy(name = "remote")), 100L)
        assertEquals("remote", ordinary.mergedData.playlists.single().name)
    }

    @Test
    fun `concurrent additions and explicit deletions are counted relative to local contents`() {
        val first = song(1L)
        val second = song(2L)
        val identity = first.identity()
        val local = snapshot(playlist(1L).copy(songs = listOf(first)))
        val remote = snapshot(playlist(1L).copy(songs = listOf(first, second))).copy(
            playlistSongDeletions = listOf(SyncPlaylistSongDeletion(
                playlistId = 1L, songId = identity.id, album = identity.album,
                mediaUri = identity.mediaUri, deletedAt = 500L, deviceId = "remote"
            ))
        )
        val result = merger.merge(local, remote, 100L)
        assertEquals(listOf(2L), result.mergedData.playlists.single().songs.map { it.id })
        assertEquals(1, result.syncResult.songsAdded)
        assertEquals(1, result.syncResult.songsRemoved)
        assertEquals(1, result.syncResult.playlistsUpdated)
        assertEquals(1, result.mergedData.playlistSongDeletions.size)
        assertEquals(listOf(1L), local.playlists.single().songs.map { it.id })
    }

    @Test
    fun `deletions alone mark an otherwise unchanged playlist as updated`() {
        val first = song(1L)
        val identity = first.identity()
        val data = snapshot(playlist(1L, modified = 200L).copy(songs = listOf(first))).copy(
            playlistSongDeletions = listOf(SyncPlaylistSongDeletion(
                playlistId = 1L, songId = identity.id, album = identity.album,
                mediaUri = identity.mediaUri, deletedAt = 500L, deviceId = "remote"
            ))
        )
        val remote = data.copy(playlists = data.playlists.map { it.copy(modifiedAt = 50L) })
        val result = merger.merge(data, remote, 100L)
        assertTrue(result.mergedData.playlists.single().songs.isEmpty())
        assertEquals(1, result.syncResult.playlistsUpdated)
        assertEquals(0, result.syncResult.songsAdded)
        assertEquals(1, result.syncResult.songsRemoved)
    }

    @Test
    fun `favorite playlists merge by platform and retain sort order`() {
        val local = snapshot().copy(favoritePlaylists = listOf(
            SyncFavoritePlaylist(id = 1L, source = "netease", name = "old", modifiedAt = 100L, sortOrder = 2L),
            SyncFavoritePlaylist(id = 1L, source = "bilibili", name = "bili", sortOrder = 3L)
        ))
        val remote = snapshot().copy(favoritePlaylists = listOf(
            SyncFavoritePlaylist(id = 1L, source = "netease", name = "new", modifiedAt = 200L, sortOrder = 4L)
        ))
        val merged = merger.merge(local, remote, 100L).mergedData.favoritePlaylists
        assertEquals(listOf("new", "bili"), merged.map { it.name })
    }

    @Test
    fun `recent plays respect resume and device tie breaks and newer plays retire tombstones`() {
        val first = song(1L)
        val identity = first.identity()
        val local = snapshot().copy(recentPlays = listOf(SyncRecentPlay(1L, first, 100L, "a", 10L)))
        val deletion = SyncRecentPlayDeletion(identity.id, identity.album, identity.mediaUri, 100L, "a")
        val deleted = snapshot().copy(recentPlayDeletions = listOf(deletion))
        assertTrue(merger.merge(local, deleted, 50L).mergedData.recentPlays.isEmpty())
        val remote = deleted.copy(recentPlays = listOf(
            SyncRecentPlay(1L, first, 200L, "a", 20L),
            SyncRecentPlay(1L, first, 200L, "z", 20L),
            SyncRecentPlay(1L, first, 200L, "z", 10L)
        ))
        val merged = merger.merge(local, remote, 50L).mergedData
        assertEquals("z", merged.recentPlays.single().deviceId)
        assertEquals(20L, merged.recentPlays.single().resumePositionMs)
        assertTrue(merged.recentPlayDeletions.isEmpty())
    }

    @Test
    fun `recent history keeps all plays and every unresolved deletion beyond former capacity`() {
        val plays = (1L..501L).map { SyncRecentPlay(it, song(it), it, "device") }
        val deleted = plays.map { play ->
            val identity = play.song.identity()
            SyncRecentPlayDeletion(identity.id, identity.album, identity.mediaUri, play.playedAt + 1000L, "a")
        }
        val local = snapshot().copy(recentPlays = plays, recentPlayDeletions = deleted)
        val remote = snapshot().copy(recentPlayDeletions = deleted.map { it.copy(deviceId = "z") })
        val merged = merger.merge(local, remote, 0L).mergedData
        assertEquals(501, merged.recentPlayDeletions.size)
        assertTrue(merged.recentPlayDeletions.all { it.deviceId == "z" })
        assertEquals(1501L, merged.recentPlayDeletions.first().deletedAt)
        assertEquals(501, merger.merge(snapshot().copy(recentPlays = plays), snapshot(), 0L).mergedData.recentPlays.size)
    }

    @Test
    fun `large recent history merge keeps newest per song and prevents deleted songs returning`() {
        val localPlays = (1L..1500L).map { SyncRecentPlay(it, song(it), it, "local") }
        val remotePlays = (1001L..3000L).map { SyncRecentPlay(it, song(it), it + 10_000L, "remote") }
        val removed = SyncRecentPlayDeletion(songId = 1L, album = song(1L).album, deletedAt = 50_000L, deviceId = "local")
        val replayed = removed.copy(songId = 2001L, deletedAt = 1L)
        val absent = removed.copy(songId = 4000L)
        val local = snapshot().copy(recentPlays = localPlays, recentPlayDeletions = listOf(removed, replayed, absent))
        val remote = snapshot().copy(recentPlays = remotePlays)

        val merged = merger.merge(local, remote, 0L).mergedData

        assertEquals(2999, merged.recentPlays.size)
        assertFalse(merged.recentPlays.any { it.songId == 1L })
        assertEquals(11_001L, merged.recentPlays.single { it.songId == 1001L }.playedAt)
        assertEquals(12_001L, merged.recentPlays.single { it.songId == 2001L }.playedAt)
        assertEquals(setOf(1L, 4000L), merged.recentPlayDeletions.map { it.songId }.toSet())
        assertEquals(merged.recentPlays, merger.merge(merged, snapshot().copy(recentPlays = localPlays), 0L).mergedData.recentPlays)
    }

    @Test
    fun `sync log is deduplicated by timestamp ordered and bounded`() {
        val local = snapshot().copy(syncLog = (1L..101L).map { SyncLogEntry(timestamp = it, deviceId = "local") })
        val remote = snapshot().copy(syncLog = listOf(SyncLogEntry(timestamp = 101L, deviceId = "remote")))
        val log = merger.merge(local, remote, 0L).mergedData.syncLog
        assertEquals(100, log.size)
        assertEquals(101L, log.first().timestamp)
        assertEquals("local", log.first().deviceId)
        assertEquals(2L, log.last().timestamp)
    }

    @Test
    fun `initial upload and merged upload both lift aggregate statistics from untrimmed buckets`() {
        val local = snapshot(playlist(1L).copy(songs = listOf(song(1L))), playlist(2L).copy(isDeleted = true)).copy(
            playbackStats = listOf(SyncTrackStat(identityKey = "track", totalListenMs = 10L, playCount = 1, lastPlayedAt = 200L)),
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(
                identityKey = "track", dayStartAt = 100L, totalListenMs = 30L, playCount = 3, lastPlayedAt = 200L
            ))
        )
        val initial = merger.initial(local)
        assertEquals("test initial", initial.syncResult.message)
        assertEquals(1, initial.syncResult.playlistsAdded)
        assertEquals(1, initial.syncResult.playlistsDeleted)
        assertEquals(1, initial.syncResult.songsAdded)
        assertEquals(999L, initial.mergedData.lastModified)
        assertEquals(30L, initial.mergedData.playbackStats.single().totalListenMs)
        val merged = merger.merge(local, snapshot(), 100L)
        assertEquals(30L, merged.mergedData.playbackStats.single().totalListenMs)
        val cleared = merger.merge(local, snapshot().copy(playbackStatsClearedAt = 300L), 100L).mergedData
        assertEquals(300L, cleared.playbackStatsClearedAt)
        assertTrue(cleared.playbackStats.isEmpty())
    }

    @Test
    fun `reusing the merger does not retain prior playlists or conflicts`() {
        merger.merge(snapshot(playlist(1L, name = "a")), snapshot(playlist(1L, name = "b")), 100L)
        val next = merger.merge(snapshot(), snapshot(), 100L)
        assertTrue(next.mergedData.playlists.isEmpty())
        assertTrue(next.syncResult.conflicts.isEmpty())
        assertEquals(0, next.syncResult.playlistsUpdated)
    }

    private fun snapshot(vararg playlists: SyncPlaylist) = SyncData(lastModified = 0L, playlists = playlists.toList())

    private fun playlist(id: Long, name: String = "playlist $id", modified: Long = 200L) =
        SyncPlaylist(id = id, name = name, createdAt = 10L, modifiedAt = modified, songOrderVersion = 1)

    private fun song(id: Long) = SyncSong(id = id, name = "song $id", album = "album", addedAt = 10L)
}

internal open class TestSyncMergeHost(private val prefix: String = "test") : SyncMergeHost {
    override val favoritesPlaylistId = -1L
    override val mergeSuccessMessage = "$prefix merged"
    override val initialUploadMessage = "$prefix initial"
    override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? =
        if (id == favoritesPlaylistId) SyncSystemPlaylist(id, "favorites") else null
    override fun localRenameMessage(name: String) = "local $name"
    override fun remoteRenameMessage(name: String) = "remote $name"
}
