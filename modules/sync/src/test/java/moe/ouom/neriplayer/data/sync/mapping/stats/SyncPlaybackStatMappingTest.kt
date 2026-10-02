package moe.ouom.neriplayer.data.sync.mapping.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri
import moe.ouom.neriplayer.data.sync.sanitize.SyncSanitizationHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackStatMappingTest {
    private val stat = TrackStat(
        id = 42L, name = "song", artist = "artist", album = "remote", coverUrl = "https://cover",
        durationMs = 300L, totalListenMs = 1000L, playCount = 5, lastPlayedAt = 200L,
        firstPlayedAt = 100L, mediaUri = "https://audio", localFilePath = null,
        localFileName = null, customName = null, customArtist = null, customCoverUrl = null,
        identityKey = "remote:42"
    )
    private val bucket = PlaybackStatBucket(
        dayStartAt = 10L, id = 42L, name = "song", artist = "artist", album = "remote",
        coverUrl = "https://cover", durationMs = 300L, totalListenMs = 1000L, playCount = 5,
        lastPlayedAt = 200L, firstPlayedAt = 100L, mediaUri = "https://audio",
        localFilePath = null, localFileName = null, customName = null, customArtist = null,
        customCoverUrl = null, identityKey = "remote:42"
    )

    @Test
    fun `overflowing shard sums cannot invent a legacy counter base`() {
        val shards = listOf("a", "b").map { device ->
            SyncPlaybackCounterShard(deviceId = device, totalListenMs = Long.MAX_VALUE, playCount = Int.MAX_VALUE)
        }
        val mapped = SyncPlaybackStatMapping.fromTrackStat(stat, shards)
        val mappedBucket = SyncPlaybackStatMapping.fromPlaybackStatBucket(bucket, shards)
        assertEquals(0L, mapped.counterBaseListenMs)
        assertEquals(0, mapped.counterBasePlayCount)
        assertEquals(0L, mappedBucket.counterBaseListenMs)
        assertEquals(0, mappedBucket.counterBasePlayCount)
    }

    @Test
    fun `local file paths and provider references exclude statistics from sync`() {
        assertTrue(SyncPlaybackStatMapping.shouldSync(stat, Host))
        assertTrue(SyncPlaybackStatMapping.shouldSync(stat.copy(localFilePath = " "), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(stat.copy(localFilePath = "/audio.mp3"), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(stat.copy(album = "local"), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(stat.copy(mediaUri = "content://audio/42"), Host))
        assertTrue(SyncPlaybackStatMapping.shouldSync(bucket, Host))
        assertTrue(SyncPlaybackStatMapping.shouldSync(bucket.copy(localFilePath = " "), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(bucket.copy(localFilePath = "/audio.mp3"), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(bucket.copy(album = "local"), Host))
        assertFalse(SyncPlaybackStatMapping.shouldSync(bucket.copy(mediaUri = "file:///audio.mp3"), Host))
    }

    @Test
    fun `mapping preserves remote metadata and separates legacy totals from device shards`() {
        val shards = listOf(SyncPlaybackCounterShard(deviceId = "device", totalListenMs = 200L, playCount = 2))
        val mapped = SyncPlaybackStatMapping.fromTrackStat(stat, shards)
        assertEquals("remote:42", mapped.identityKey)
        assertEquals("https://audio", mapped.mediaUri)
        assertEquals("https://cover", mapped.coverUrl)
        assertEquals(1000L, mapped.totalListenMs)
        assertEquals(800L, mapped.counterBaseListenMs)
        assertEquals(3, mapped.counterBasePlayCount)
        assertEquals(shards, mapped.counterShards)

        val mappedBucket = SyncPlaybackStatMapping.fromPlaybackStatBucket(bucket, shards)
        assertEquals(10L, mappedBucket.dayStartAt)
        assertEquals("https://audio", mappedBucket.mediaUri)
        assertEquals(800L, mappedBucket.counterBaseListenMs)
        assertEquals(3, mappedBucket.counterBasePlayCount)
        assertEquals(shards, mappedBucket.counterShards)
    }

    @Test
    fun `mapping removes local references without producing negative counter bases`() {
        val mapped = SyncPlaybackStatMapping.fromTrackStat(
            stat.copy(mediaUri = "file:///audio.mp3", coverUrl = "content://cover", totalListenMs = -1L, playCount = -1)
        )
        assertNull(mapped.mediaUri)
        assertNull(mapped.coverUrl)
        assertEquals(0L, mapped.counterBaseListenMs)
        assertEquals(0, mapped.counterBasePlayCount)
        assertNull(SyncPlaybackStatMapping.fromTrackStat(stat.copy(mediaUri = null)).mediaUri)

        val mappedBucket = SyncPlaybackStatMapping.fromPlaybackStatBucket(
            bucket.copy(mediaUri = "content://audio/42", coverUrl = "file:///cover", totalListenMs = -1L, playCount = -1)
        )
        assertNull(mappedBucket.mediaUri)
        assertNull(mappedBucket.coverUrl)
        assertEquals(0L, mappedBucket.counterBaseListenMs)
        assertEquals(0, mappedBucket.counterBasePlayCount)
        assertNull(SyncPlaybackStatMapping.fromPlaybackStatBucket(bucket.copy(mediaUri = null)).mediaUri)
    }

    @Test
    fun `remote statistics repair invalid times and preserve valid first plays`() {
        val track = SyncTrackStat(identityKey = "remote:42", mediaUri = "https://audio", lastPlayedAt = 200L)
        val remoteBucket = SyncPlaybackStatBucket(identityKey = "remote:42", mediaUri = "https://audio", lastPlayedAt = 200L)
        assertEquals(200L, SyncPlaybackStatMapping.sanitize(track, Host)?.firstPlayedAt)
        assertEquals(200L, SyncPlaybackStatMapping.sanitize(track.copy(firstPlayedAt = 300L), Host)?.firstPlayedAt)
        assertEquals(100L, SyncPlaybackStatMapping.sanitize(track.copy(firstPlayedAt = 100L), Host)?.firstPlayedAt)
        assertEquals(0L, SyncPlaybackStatMapping.sanitize(track.copy(lastPlayedAt = -1L, firstPlayedAt = -1L), Host)?.firstPlayedAt)
        assertEquals(200L, SyncPlaybackStatMapping.sanitize(remoteBucket, Host)?.firstPlayedAt)
        assertEquals(200L, SyncPlaybackStatMapping.sanitize(remoteBucket.copy(firstPlayedAt = 300L), Host)?.firstPlayedAt)
        assertEquals(100L, SyncPlaybackStatMapping.sanitize(remoteBucket.copy(firstPlayedAt = 100L), Host)?.firstPlayedAt)
        assertEquals(0L, SyncPlaybackStatMapping.sanitize(remoteBucket.copy(lastPlayedAt = -1L, firstPlayedAt = -1L), Host)?.firstPlayedAt)
        assertNull(SyncPlaybackStatMapping.sanitize(track.copy(identityKey = ""), Host))
        assertNull(SyncPlaybackStatMapping.sanitize(track.copy(album = "local"), Host))
        assertNull(SyncPlaybackStatMapping.sanitize(remoteBucket.copy(identityKey = ""), Host))
        assertNull(SyncPlaybackStatMapping.sanitize(remoteBucket.copy(album = "local"), Host))
    }

    private object Host : SyncSanitizationHost {
        override val localFilesPlaylistId = -1002L
        override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = null
        override fun isLocalSong(album: String?, mediaUri: String?, albumId: Long) =
            album == "local" || isLocalMediaUri(mediaUri)
        override fun sanitizeMediaUri(mediaUri: String?) = mediaUri?.takeUnless(::isLocalMediaUri)
    }
}
