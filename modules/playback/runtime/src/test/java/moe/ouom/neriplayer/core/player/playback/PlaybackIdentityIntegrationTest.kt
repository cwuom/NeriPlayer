package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.runtime.progress.PlaybackProgressOwner
import moe.ouom.neriplayer.core.player.runtime.progress.PlaybackProgressPort
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackIdentityIntegrationTest {
    @Test
    fun `app identity preserves listened time when a downloaded copy replaces the remote song`() {
        val remote = remoteSong()
        val local = downloadedCopy(remote)
        var now = 0L
        val tracker = PlaybackStatsTracker(AppQueueSongIdentity::stableKey, nowElapsedMs = { now })
        tracker.onSongChanged(remote)
        tracker.onPlayingChanged(true)
        now = 40_000L

        assertNull(tracker.onSongChanged(local))
        val snapshot = tracker.onPlayingChanged(false)!!
        assertEquals(local, snapshot.song)
        assertEquals(40_000L, snapshot.listenedMs)
        assertEquals(1, snapshot.playCountIncrement)
    }

    @Test
    fun `app identity backfills the current representation from a downloaded copy`() {
        val remote = remoteSong().copy(durationMs = 0L)
        val port = DurationPort(remote)
        val owner = PlaybackProgressOwner(port, AppQueueSongIdentity::sameIdentity) { 0L }

        owner.maybeUpdateSongDuration(downloadedCopy(remote), 60_000L)

        assertEquals(remote.copy(durationMs = 60_000L), port.current)
        assertEquals(60_000L, owner.duration.value)
    }

    private fun remoteSong() = SongItem(
        id = 1L,
        name = "song",
        artist = "artist",
        album = "Netease",
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null,
        sourceStableKey = SongIdentity(1L, "netease", null).stableKey()
    )

    private fun downloadedCopy(song: SongItem) = song.copy(
        id = 2L,
        album = "本地",
        mediaUri = "content://example/document/song",
        localFilePath = "content://example/document/song"
    )

    private class DurationPort(var current: SongItem) : PlaybackProgressPort {
        override fun rememberLongFormEnabled() = false
        override fun rememberedPosition(song: SongItem) = 0L
        override fun writeRememberedPosition(song: SongItem, positionMs: Long) = Unit
        override fun currentSong() = current
        override fun reportedPositionMs() = 0L
        override fun playerPositionMs(): Long? = null
        override fun playerDurationMs(): Long? = null
        override fun updateQueuedDurationIfUnknown(song: SongItem, durationMs: Long) = false
        override fun replaceCurrentSong(song: SongItem) { current = song }
        override fun scheduleImmediateStatePersist() = Unit
        override fun pendingMediaLoadActive() = false
        override fun pendingMediaLoadPositionMs() = 0L
    }
}
