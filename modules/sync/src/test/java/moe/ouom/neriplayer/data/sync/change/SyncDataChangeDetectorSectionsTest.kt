package moe.ouom.neriplayer.data.sync.change

import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipRule
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDataChangeDetectorSectionsTest {
    @Test
    fun `lyric reset without any remaining song copy requires upload`() {
        val original = SyncData()
        val reset = original.copy(lyricOverrides = listOf(SyncSong(id = 1L, lyricSyncEdited = false, lyricSyncRevision = 20L)))
        assertTrue(SyncDataChangeDetector.hasDataChanged(original, reset))
        assertFalse(SyncDataChangeDetector.hasDataChanged(reset, reset))
    }
    private val song = SyncSong(id = 42L, album = "remote")
    private val playlist = SyncPlaylist(id = 7L, songs = listOf(song), songOrderVersion = 1)
    private val favorite = SyncFavoritePlaylist(id = 8L, songs = listOf(song))
    private val recent = SyncRecentPlay(song = song)
    private val deletion = SyncRecentPlayDeletion(songId = 42L)
    private val songDeletion = SyncPlaylistSongDeletion(playlistId = 7L, songId = 42L)
    private val track = SyncTrackStat(identityKey = "track")
    private val bucket = SyncPlaybackStatBucket(identityKey = "track")
    private val usage = SyncPlaylistUsageStat(playlistKey = "netease:7")
    private val localStat = SyncLocalPlaylistPlaybackStat(playlistId = 7L)
    private val localBucket = SyncLocalPlaylistPlaybackBucket(playlistId = 7L)
    private val data = SyncData(
        playlists = listOf(playlist), favoritePlaylists = listOf(favorite), recentPlays = listOf(recent),
        recentPlayDeletions = listOf(deletion), playlistSongDeletions = listOf(songDeletion),
        playbackStats = listOf(track), playbackStatBuckets = listOf(bucket), playlistUsageStats = listOf(usage),
        localPlaylistPlaybackStats = listOf(localStat), localPlaylistPlaybackBuckets = listOf(localBucket)
    )

    @Test
    fun `playlist identity membership order and state changes require upload`() {
        for (changed in listOf(
            playlist.copy(id = 9L), playlist.copy(name = "renamed"), playlist.copy(isDeleted = true),
            playlist.copy(songOrderVersion = 0), playlist.copy(songs = emptyList()),
            playlist.copy(songs = listOf(song.copy(id = 43L)))
        )) assertChanged(data.copy(playlists = listOf(changed)))
        assertChanged(data.copy(playlists = emptyList()))
        val two = data.copy(playlists = listOf(playlist, playlist.copy(id = 9L)))
        assertTrue(SyncDataChangeDetector.hasDataChanged(two, two.copy(playlists = two.playlists.reversed())))
    }

    @Test
    fun `all synced song metadata fields participate in change detection`() {
        val changes = listOf(
            song.copy(name = "name"), song.copy(artist = "artist"), song.copy(album = "album"),
            song.copy(albumId = 3L), song.copy(durationMs = 4L), song.copy(coverUrl = "cover"),
            song.copy(mediaUri = "https://audio"), song.copy(addedAt = 5L), song.copy(matchedLyric = "lyric"),
            song.copy(matchedTranslatedLyric = "translation"), song.copy(matchedRomanizedLyric = "romanized"),
            song.copy(lyricSyncRevision = 20L), song.copy(lyricSyncEdited = true), song.copy(matchedLyricSource = "NETEASE"),
            song.copy(matchedSongId = "matched"), song.copy(userLyricOffsetMs = 6L),
            song.copy(customCoverUrl = "custom cover"), song.copy(customName = "custom name"),
            song.copy(customArtist = "custom artist"), song.copy(originalName = "original name"),
            song.copy(originalArtist = "original artist"), song.copy(originalCoverUrl = "original cover"),
            song.copy(originalLyric = "original lyric"), song.copy(originalTranslatedLyric = "original translation"),
            song.copy(channelId = "channel"), song.copy(audioId = "audio"), song.copy(subAudioId = "part"),
            song.copy(playlistContextId = "context"), song.copy(syncMetadataVersion = 1),
            song.copy(syncMembershipTokens = listOf(SyncCausalToken("device", 1L)))
        )
        for (changed in changes) {
            assertFalse(SyncSongMetadataComparison.same(song, changed))
            assertChanged(data.copy(playlists = listOf(playlist.copy(songs = listOf(changed)))))
        }
    }

    @Test
    fun `favorite keys counts order and song metadata changes require upload`() {
        for (changed in listOf(
            favorite.copy(id = 9L), favorite.copy(source = "source"), favorite.copy(isDeleted = true),
            favorite.copy(modifiedAt = 1L), favorite.copy(sortOrder = 1L), favorite.copy(trackCount = 1),
            favorite.copy(songs = emptyList()), favorite.copy(songs = listOf(song.copy(name = "new")))
        )) assertChanged(data.copy(favoritePlaylists = listOf(changed)))
        assertChanged(data.copy(favoritePlaylists = emptyList()))
    }

    @Test
    fun `history and tombstones retain complete comparison`() {
        for (changed in listOf(
            recent.copy(song = song.copy(id = 43L)), recent.copy(song = song.copy(name = "new")),
            recent.copy(playedAt = 1L), recent.copy(resumePositionMs = 1L)
        )) assertChanged(data.copy(recentPlays = listOf(changed)))
        assertChanged(data.copy(recentPlays = emptyList()))
        for (changed in listOf(deletion.copy(songId = 43L), deletion.copy(deletedAt = 1L), deletion.copy(deviceId = "new"))) {
            assertChanged(data.copy(recentPlayDeletions = listOf(changed)))
        }
        assertChanged(data.copy(recentPlayDeletions = emptyList()))
        for (changed in listOf(
            songDeletion.copy(playlistId = 8L), songDeletion.copy(songId = 43L), songDeletion.copy(deletedAt = 1L),
            songDeletion.copy(deviceId = "new"), songDeletion.copy(removedMembershipTokens = listOf(SyncCausalToken("device", 1L)))
        )) assertChanged(data.copy(playlistSongDeletions = listOf(changed)))
        assertChanged(data.copy(playlistSongDeletions = emptyList()))
    }

    @Test
    fun `playback aggregate and daily metadata changes require upload`() {
        for (changed in listOf(
            track.copy(identityKey = "other"), track.copy(name = "new"), track.copy(artist = "new"),
            track.copy(album = "new"), track.copy(coverUrl = "new"), track.copy(mediaUri = "new"),
            track.copy(id = 1L), track.copy(albumId = 1L), track.copy(totalListenMs = 1L), track.copy(playCount = 1),
            track.copy(lastPlayedAt = 1L), track.copy(firstPlayedAt = 1L), track.copy(durationMs = 1L),
            track.copy(counterBaseListenMs = 1L), track.copy(counterBasePlayCount = 1)
        )) assertChanged(data.copy(playbackStats = listOf(changed)))
        for (changed in listOf(
            bucket.copy(dayStartAt = 1L), bucket.copy(identityKey = "other"), bucket.copy(name = "new"),
            bucket.copy(artist = "new"), bucket.copy(album = "new"), bucket.copy(coverUrl = "new"),
            bucket.copy(mediaUri = "new"), bucket.copy(id = 1L), bucket.copy(albumId = 1L),
            bucket.copy(totalListenMs = 1L), bucket.copy(playCount = 1), bucket.copy(lastPlayedAt = 1L),
            bucket.copy(firstPlayedAt = 1L), bucket.copy(durationMs = 1L), bucket.copy(counterBaseListenMs = 1L),
            bucket.copy(counterBasePlayCount = 1)
        )) assertChanged(data.copy(playbackStatBuckets = listOf(changed)))
        assertChanged(data.copy(playbackStats = emptyList()))
        assertChanged(data.copy(playbackStatBuckets = emptyList()))
        assertChanged(data.copy(playbackStatsClearedAt = 1L))
    }

    @Test
    fun `usage local playlist counters and video skip rules participate in change detection`() {
        assertChanged(data.copy(playlistUsageStats = listOf(usage.copy(openCount = 1))))
        assertChanged(data.copy(playlistUsageStats = emptyList()))
        assertChanged(data.copy(localPlaylistPlaybackStats = listOf(localStat.copy(totalPlayCount = 1L))))
        assertChanged(data.copy(localPlaylistPlaybackStats = emptyList()))
        assertChanged(data.copy(localPlaylistPlaybackBuckets = listOf(localBucket.copy(playCount = 1L))))
        assertChanged(data.copy(localPlaylistPlaybackBuckets = emptyList()))
        assertChanged(data.copy(biliVideoSkipRules = listOf(
            SyncBiliVideoSkipRule("BV1test", 42L, listOf(SyncBiliVideoSkipInterval(0L, 1000L)), 1L)
        )))
    }

    @Test
    fun `transport timestamps and duplicate membership tokens do not cause upload loops`() {
        assertFalse(SyncDataChangeDetector.hasDataChanged(data, data.copy(deviceId = "new", deviceName = "new", lastModified = 1L)))
        val token = SyncCausalToken("device", 1L)
        val withToken = data.copy(playlists = listOf(playlist.copy(songs = listOf(song.copy(syncMembershipTokens = listOf(token))))))
        val duplicateToken = data.copy(playlists = listOf(playlist.copy(songs = listOf(song.copy(syncMembershipTokens = listOf(token, token))))))
        assertFalse(SyncDataChangeDetector.hasDataChanged(withToken, duplicateToken))
        assertFalse(SyncDataChangeDetector.hasDataChanged(data, data.copy()))
    }

    private fun assertChanged(changed: SyncData) {
        assertTrue(SyncDataChangeDetector.hasDataChanged(data, changed))
        assertTrue(SyncDataChangeDetector.hasDataChanged(changed, data))
    }
}
