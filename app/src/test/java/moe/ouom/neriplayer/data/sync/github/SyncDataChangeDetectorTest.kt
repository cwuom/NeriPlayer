package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.LEGACY_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDataChangeDetectorTest {
    @Test
    fun `detects recent play change after previous fifty item window`() {
        val remoteRecent = (1..51).map { recentPlay(it) }
        val mergedRecent = (1..50).map { recentPlay(it) } + recentPlay(9_001)
        val remote = syncData(recentPlays = remoteRecent)
        val merged = syncData(recentPlays = mergedRecent)

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    @Test
    fun `detects recent play tombstone change after previous hundred item window`() {
        val remoteDeletions = (1..101).map { recentPlayDeletion(it) }
        val mergedDeletions = (1..100).map { recentPlayDeletion(it) } + recentPlayDeletion(9_101)
        val remote = syncData(recentPlayDeletions = remoteDeletions)
        val merged = syncData(recentPlayDeletions = mergedDeletions)

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    @Test
    fun `detects playlist song tombstone change after previous five hundred item window`() {
        val remoteDeletions = (1..501).map { playlistSongDeletion(it) }
        val mergedDeletions = (1..500).map { playlistSongDeletion(it) } + playlistSongDeletion(9_501)
        val remote = syncData(playlistSongDeletions = remoteDeletions)
        val merged = syncData(playlistSongDeletions = mergedDeletions)

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    @Test
    fun `large identical sections stay stable after full comparison`() {
        val data = syncData(
            recentPlays = (1..51).map { recentPlay(it) },
            recentPlayDeletions = (1..101).map { recentPlayDeletion(it) },
            playlistSongDeletions = (1..501).map { playlistSongDeletion(it) }
        )

        assertFalse(SyncDataChangeDetector.hasDataChanged(data, data.copy()))
    }

    @Test
    fun `detects playlist membership token change`() {
        val remote = syncData(
            playlists = listOf(playlist(song(1).copy(syncMembershipTokens = listOf(token(1L)))))
        )
        val merged = syncData(
            playlists = listOf(playlist(song(1).copy(syncMembershipTokens = listOf(token(2L)))))
        )

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    @Test
    fun `detects sync metadata version upgrade`() {
        val remote = syncData(
            playlists = listOf(
                playlist(song(1).copy(syncMetadataVersion = LEGACY_SYNC_METADATA_VERSION))
            )
        )
        val merged = syncData(
            playlists = listOf(
                playlist(song(1).copy(syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION))
            )
        )

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    @Test
    fun `detects observed token change in playlist deletion`() {
        val remote = syncData(
            playlistSongDeletions = listOf(
                playlistSongDeletion(1).copy(removedMembershipTokens = listOf(token(1L)))
            )
        )
        val merged = syncData(
            playlistSongDeletions = listOf(
                playlistSongDeletion(1).copy(removedMembershipTokens = listOf(token(2L)))
            )
        )

        assertTrue(SyncDataChangeDetector.hasDataChanged(remote, merged))
    }

    private fun syncData(
        playlists: List<SyncPlaylist> = emptyList(),
        recentPlays: List<SyncRecentPlay> = emptyList(),
        recentPlayDeletions: List<SyncRecentPlayDeletion> = emptyList(),
        playlistSongDeletions: List<SyncPlaylistSongDeletion> = emptyList()
    ): SyncData {
        return SyncData(
            deviceId = "device",
            deviceName = "test-device",
            playlists = playlists,
            recentPlays = recentPlays,
            recentPlayDeletions = recentPlayDeletions,
            playlistSongDeletions = playlistSongDeletions
        )
    }

    private fun recentPlay(index: Int): SyncRecentPlay {
        val song = song(index)
        return SyncRecentPlay(
            songId = song.id,
            song = song,
            playedAt = 10_000L - index,
            deviceId = "device"
        )
    }

    private fun recentPlayDeletion(index: Int): SyncRecentPlayDeletion {
        return SyncRecentPlayDeletion(
            songId = index.toLong(),
            album = "album-$index",
            mediaUri = "https://media.example/$index",
            deletedAt = 20_000L - index,
            deviceId = "device"
        )
    }

    private fun playlistSongDeletion(index: Int): SyncPlaylistSongDeletion {
        return SyncPlaylistSongDeletion(
            playlistId = 7L,
            songId = index.toLong(),
            album = "album-$index",
            mediaUri = "https://media.example/$index",
            deletedAt = 30_000L - index,
            deviceId = "device"
        )
    }

    private fun song(index: Int): SyncSong {
        return SyncSong(
            id = index.toLong(),
            name = "song-$index",
            artist = "artist",
            album = "album-$index",
            mediaUri = "https://media.example/$index",
            addedAt = index.toLong()
        )
    }

    private fun playlist(song: SyncSong): SyncPlaylist {
        return SyncPlaylist(
            id = 7L,
            name = "playlist",
            songs = listOf(song),
            createdAt = 1L,
            modifiedAt = 1L
        )
    }

    private fun token(counter: Long): SyncCausalToken {
        return SyncCausalToken("device", counter)
    }
}
