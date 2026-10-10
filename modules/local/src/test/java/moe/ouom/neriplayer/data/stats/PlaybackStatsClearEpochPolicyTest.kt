package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsClearEpochPolicyTest {

    @Test
    fun `track stats survive only when played at or after the clear marker`() {
        assertTrue(shouldKeepTrackStatAfterClear(track(lastPlayedAt = 10L), playbackStatsClearedAt = 0L))
        assertTrue(shouldKeepTrackStatAfterClear(track(lastPlayedAt = 10L), playbackStatsClearedAt = -5L))
        assertTrue(shouldKeepTrackStatAfterClear(track(lastPlayedAt = 500L), playbackStatsClearedAt = 500L))
        assertFalse(shouldKeepTrackStatAfterClear(track(lastPlayedAt = 499L), playbackStatsClearedAt = 500L))
    }

    @Test
    fun `daily buckets survive only when played at or after the clear marker`() {
        assertTrue(shouldKeepDailyBucketAfterClear(bucket(lastPlayedAt = 1L), playbackStatsClearedAt = 0L))
        assertTrue(shouldKeepDailyBucketAfterClear(bucket(lastPlayedAt = 900L), playbackStatsClearedAt = 800L))
        assertFalse(shouldKeepDailyBucketAfterClear(bucket(lastPlayedAt = 799L), playbackStatsClearedAt = 800L))
    }

    @Test
    fun `a new epoch starts when any part of the stat predates the clear marker`() {
        assertFalse(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 1L, lastPlayedAt = 2L), playbackStatsClearedAt = 0L)
        )
        assertFalse(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 700L, lastPlayedAt = 900L), playbackStatsClearedAt = 600L)
        )
        assertTrue(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 100L, lastPlayedAt = 900L), playbackStatsClearedAt = 600L)
        )
        assertTrue(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 700L, lastPlayedAt = 500L), playbackStatsClearedAt = 600L)
        )
    }

    @Test
    fun `missing first play time falls back to the last play time`() {
        assertFalse(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 0L, lastPlayedAt = 900L), playbackStatsClearedAt = 600L)
        )
        assertTrue(
            shouldStartNewStatsEpoch(track(firstPlayedAt = 0L, lastPlayedAt = 300L), playbackStatsClearedAt = 600L)
        )
    }

    @Test
    fun `newer remote bucket replaces metadata and counters`() {
        val local = bucket(lastPlayedAt = 100L).copy(localFilePath = "/music/a.flac", customName = "Mine")
        val remote = SyncPlaybackStatBucket(
            dayStartAt = local.dayStartAt,
            identityKey = local.identityKey,
            name = "Remote name",
            artist = "Remote artist",
            album = "Remote album",
            totalListenMs = 9_000L,
            playCount = 7,
            lastPlayedAt = 200L,
            firstPlayedAt = 50L,
            coverUrl = "https://example.com/remote.jpg",
            durationMs = 180_000L,
            mediaUri = "content://media/remote",
            id = 77L,
            albumId = 88L
        )

        val merged = mergeDailyBucket(local, remote)

        assertEquals(77L, merged.id)
        assertEquals("Remote name", merged.name)
        assertEquals("Remote artist", merged.artist)
        assertEquals("Remote album", merged.album)
        assertEquals(88L, merged.albumId)
        assertEquals("https://example.com/remote.jpg", merged.coverUrl)
        assertEquals(180_000L, merged.durationMs)
        assertEquals("content://media/remote", merged.mediaUri)
        assertEquals(9_000L, merged.totalListenMs)
        assertEquals(7, merged.playCount)
        assertEquals(200L, merged.lastPlayedAt)
        assertEquals(50L, merged.firstPlayedAt)
        assertEquals("/music/a.flac", merged.localFilePath)
        assertEquals("Mine", merged.customName)
    }

    @Test
    fun `older remote bucket keeps local metadata but adopts remote counters`() {
        val local = bucket(lastPlayedAt = 500L)
        val remote = SyncPlaybackStatBucket(
            dayStartAt = local.dayStartAt,
            identityKey = local.identityKey,
            name = "Stale remote",
            totalListenMs = 1_234L,
            playCount = 3,
            lastPlayedAt = 400L,
            firstPlayedAt = 10L,
            id = 99L
        )

        val merged = mergeDailyBucket(local, remote)

        assertEquals(local.id, merged.id)
        assertEquals(local.name, merged.name)
        assertEquals(local.coverUrl, merged.coverUrl)
        assertEquals(local.mediaUri, merged.mediaUri)
        assertEquals(1_234L, merged.totalListenMs)
        assertEquals(3, merged.playCount)
        assertEquals(400L, merged.lastPlayedAt)
        assertEquals(10L, merged.firstPlayedAt)
    }

    @Test
    fun `sync bucket conversion leaves local only fields empty`() {
        val converted = SyncPlaybackStatBucket(
            dayStartAt = 86_400_000L,
            identityKey = "netease:1",
            name = "Song",
            artist = "Artist",
            album = "Album",
            totalListenMs = 42L,
            playCount = 2,
            lastPlayedAt = 3L,
            firstPlayedAt = 1L,
            mediaUri = "https://example.com/a.mp3",
            id = 1L
        ).toPlaybackStatBucket()

        assertEquals(86_400_000L, converted.dayStartAt)
        assertEquals("netease:1", converted.identityKey)
        assertEquals(42L, converted.totalListenMs)
        assertNull(converted.localFilePath)
        assertNull(converted.localFileName)
        assertNull(converted.customName)
        assertNull(converted.customArtist)
        assertNull(converted.customCoverUrl)
    }

    private fun track(firstPlayedAt: Long = 0L, lastPlayedAt: Long) = TrackStat(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        coverUrl = null,
        durationMs = 1_000L,
        totalListenMs = 1_000L,
        playCount = 1,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = null,
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = "netease:1"
    )

    private fun bucket(lastPlayedAt: Long) = PlaybackStatBucket(
        dayStartAt = 0L,
        id = 1L,
        name = "Local name",
        artist = "Local artist",
        album = "Local album",
        albumId = 2L,
        coverUrl = "https://example.com/local.jpg",
        durationMs = 120_000L,
        totalListenMs = 5_000L,
        playCount = 4,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = 1L,
        mediaUri = "content://media/local",
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = "netease:1"
    )
}
