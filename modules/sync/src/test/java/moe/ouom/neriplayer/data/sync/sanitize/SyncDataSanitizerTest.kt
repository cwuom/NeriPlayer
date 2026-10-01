package moe.ouom.neriplayer.data.sync.sanitize

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
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDataSanitizerTest {
    private val sanitizer = SyncDataSanitizer(Host)
    private val song = SyncSong(id = 42L, album = "remote", addedAt = 100L)

    @Test
    fun `local files playlist is excluded and system aliases are canonicalized`() {
        val data = SyncData(playlists = listOf(
            SyncPlaylist(id = -1002L, name = "local"),
            SyncPlaylist(id = 2L, name = "favorites alias", songs = listOf(song), songOrderVersion = 1),
            SyncPlaylist(id = 3L, name = "regular", songs = listOf(song), songOrderVersion = 1)
        ))
        val playlists = sanitizer.sanitize(data).playlists
        assertEquals(listOf(-1001L, 3L), playlists.map { it.id })
        assertEquals(listOf("favorites", "regular"), playlists.map { it.name })
    }

    @Test
    fun `deleted playlists preserve tombstones and clear songs`() {
        val data = SyncData(playlists = listOf(
            SyncPlaylist(id = 2L, name = "favorites alias", songs = listOf(song), isDeleted = true),
            SyncPlaylist(id = 3L, name = "regular", songs = listOf(song), isDeleted = true)
        ))
        val playlists = sanitizer.sanitize(data).playlists
        assertEquals(-1001L, playlists.first().id)
        assertTrue(playlists.all { it.isDeleted && it.songs.isEmpty() })
    }

    @Test
    fun `songs without remote identity or with local references never sync`() {
        val invalid = listOf(SyncSong(), song.copy(mediaUri = "content://audio/42"), song.copy(album = "local"))
        val songs = invalid + listOf(song, song.copy(id = 43L, coverUrl = "file:///cover.jpg"))
        val data = SyncData(
            playlists = listOf(SyncPlaylist(id = 1L, songs = songs, songOrderVersion = 1)),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2L, songs = songs, coverUrl = "content://cover", trackCount = 0)),
            recentPlays = songs.map { SyncRecentPlay(songId = 999L, song = it) }
        )
        val clean = sanitizer.sanitize(data)
        assertEquals(listOf(42L, 43L), clean.playlists.single().songs.map { it.id })
        assertEquals(2, clean.favoritePlaylists.single().trackCount)
        assertEquals(null, clean.favoritePlaylists.single().coverUrl)
        assertEquals(listOf(42L, 43L), clean.recentPlays.map { it.songId })
        assertEquals(null, clean.recentPlays.last().song.coverUrl)
    }

    @Test
    fun `favorite tombstones clear songs and track count`() {
        val data = SyncData(favoritePlaylists = listOf(
            SyncFavoritePlaylist(id = 2L, songs = listOf(song), trackCount = 50, isDeleted = true)
        ))
        val favorite = sanitizer.sanitize(data).favoritePlaylists.single()
        assertTrue(favorite.isDeleted)
        assertTrue(favorite.songs.isEmpty())
        assertEquals(0, favorite.trackCount)
    }

    @Test
    fun `invalid deletion records are filtered without losing valid observed tokens`() {
        val recent = SyncRecentPlayDeletion(songId = 42L, deletedAt = 100L, album = "remote")
        val deletion = SyncPlaylistSongDeletion(
            playlistId = 7L, songId = 42L, deletedAt = 100L, album = "remote",
            removedMembershipTokens = listOf(SyncCausalToken("device", 2L), SyncCausalToken("device", 2L))
        )
        val data = SyncData(
            recentPlayDeletions = listOf(recent, recent.copy(songId = 0L), recent.copy(deletedAt = 0L), recent.copy(album = "local")),
            playlistSongDeletions = listOf(
                deletion, deletion.copy(playlistId = 0L), deletion.copy(playlistId = -1002L),
                deletion.copy(songId = 0L), deletion.copy(deletedAt = 0L), deletion.copy(mediaUri = "file:///local")
            )
        )
        val clean = sanitizer.sanitize(data)
        assertEquals(listOf(recent), clean.recentPlayDeletions)
        assertEquals(listOf(SyncCausalToken("device", 2L)), clean.playlistSongDeletions.single().removedMembershipTokens)
    }

    @Test
    fun `statistics normalize negative values and missing first play time`() {
        val stat = SyncTrackStat(identityKey = "track", totalListenMs = -1L, playCount = -1, lastPlayedAt = 100L, firstPlayedAt = 0L)
        val bucket = SyncPlaybackStatBucket(identityKey = "track", dayStartAt = -1L, lastPlayedAt = 100L, firstPlayedAt = 200L)
        val data = SyncData(
            playbackStats = listOf(stat, stat.copy(identityKey = ""), stat.copy(album = "local")),
            playbackStatBuckets = listOf(bucket, bucket.copy(identityKey = ""), bucket.copy(mediaUri = "content://audio")),
            playbackStatsClearedAt = -1L,
            playlistUsageStats = listOf(SyncPlaylistUsageStat(playlistKey = " list "), SyncPlaylistUsageStat()),
            localPlaylistPlaybackStats = listOf(SyncLocalPlaylistPlaybackStat(playlistId = 7L), SyncLocalPlaylistPlaybackStat()),
            localPlaylistPlaybackBuckets = listOf(SyncLocalPlaylistPlaybackBucket(playlistId = 7L), SyncLocalPlaylistPlaybackBucket())
        )
        val clean = sanitizer.sanitize(data)
        assertEquals(0L, clean.playbackStats.single().totalListenMs)
        assertEquals(0, clean.playbackStats.single().playCount)
        assertEquals(100L, clean.playbackStats.single().firstPlayedAt)
        assertEquals(100L, clean.playbackStatBuckets.single().firstPlayedAt)
        assertEquals(0L, clean.playbackStatBuckets.single().dayStartAt)
        assertEquals(0L, clean.playbackStatsClearedAt)
        assertEquals("list", clean.playlistUsageStats.single().playlistKey)
        assertEquals(7L, clean.localPlaylistPlaybackStats.single().playlistId)
        assertEquals(7L, clean.localPlaylistPlaybackBuckets.single().playlistId)
    }

    @Test
    fun `earlier first play and zero timestamps are preserved`() {
        val data = SyncData(
            playbackStats = listOf(SyncTrackStat(identityKey = "track", lastPlayedAt = 100L, firstPlayedAt = 50L)),
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(identityKey = "bucket", lastPlayedAt = 0L, firstPlayedAt = 0L))
        )
        val clean = sanitizer.sanitize(data)
        assertEquals(50L, clean.playbackStats.single().firstPlayedAt)
        assertEquals(0L, clean.playbackStatBuckets.single().firstPlayedAt)
    }

    private object Host : SyncSanitizationHost {
        override val localFilesPlaylistId = -1002L
        override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = when {
            id == -1002L -> SyncSystemPlaylist(-1002L, "local")
            name == "favorites alias" -> SyncSystemPlaylist(-1001L, "favorites")
            else -> null
        }
        override fun isLocalSong(album: String?, mediaUri: String?, albumId: Long) =
            isLocalMediaUri(mediaUri) || album == "local"
        override fun sanitizeMediaUri(mediaUri: String?) = mediaUri?.takeUnless(::isLocalMediaUri)
    }
}
