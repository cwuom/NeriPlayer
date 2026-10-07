package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.runtime.stats.PENDING_TRACK_END_DEDUPLICATION_KEY
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerManagerPlaybackIntentTest {
    private val previousUsbExclusive = PlayerManager.usbExclusivePlaybackEnabled
    private val previousResumeRequested = PlayerManager.resumePlaybackRequested
    private val previousPlayWhenReady = PlayerManager._playWhenReadyFlow.value
    private val previousControlPlaying = PlayerManager._playbackControlPlayingFlow.value
    private val previousPlayJob = PlayerManager.playJob
    private val previousSong = PlayerManager._currentSongFlow.value
    private val previousQueue = PlayerManager.currentQueueSnapshot()
    private val previousQueueRevision = PlayerManager._currentQueueDisplayRevisionFlow.value
    private val previousTrackEndKey = PlayerManager.lastHandledTrackEndKey
    private val previousTrackEndAtMs = PlayerManager.lastTrackEndHandledAtMs
    private val activeJob = Job()

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
    }

    @After
    fun tearDown() {
        activeJob.cancel()
        PlayerManager.usbExclusivePlaybackEnabled = previousUsbExclusive
        PlayerManager.updateResumePlaybackRequested(previousResumeRequested)
        PlayerManager._playWhenReadyFlow.value = previousPlayWhenReady
        PlayerManager._playbackControlPlayingFlow.value = previousControlPlaying
        PlayerManager.playJob = previousPlayJob
        PlayerManager._currentSongFlow.value = previousSong
        PlayerManager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        PlayerManager._currentQueueDisplayRevisionFlow.value = previousQueueRevision
        PlayerManager.lastHandledTrackEndKey = previousTrackEndKey
        PlayerManager.lastTrackEndHandledAtMs = previousTrackEndAtMs
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `usb exclusive transport starts from the sink only while playback is wanted`() {
        PlayerManager.updateResumePlaybackRequested(false)
        PlayerManager._playWhenReadyFlow.value = true
        PlayerManager.usbExclusivePlaybackEnabled = false
        assertFalse(PlayerManager.shouldStartUsbExclusiveTransportFromSink())

        PlayerManager.usbExclusivePlaybackEnabled = true
        PlayerManager._playWhenReadyFlow.value = false
        PlayerManager._playbackControlPlayingFlow.value = false
        assertFalse(PlayerManager.shouldStartUsbExclusiveTransportFromSink())

        PlayerManager._playbackControlPlayingFlow.value = true
        assertTrue(PlayerManager.shouldStartUsbExclusiveTransportFromSink())

        PlayerManager._playbackControlPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = true
        assertTrue(PlayerManager.shouldStartUsbExclusiveTransportFromSink())

        PlayerManager._playWhenReadyFlow.value = false
        PlayerManager.updateResumePlaybackRequested(true)
        PlayerManager._playbackControlPlayingFlow.value = false
        assertTrue(PlayerManager.shouldStartUsbExclusiveTransportFromSink())
    }

    @Test
    fun `playback snapshot resumes while a resume request or play job is pending`() {
        PlayerManager.updateResumePlaybackRequested(false)
        PlayerManager.playJob = null
        assertFalse(PlayerManager.shouldResumePlaybackSnapshot())

        PlayerManager.playJob = Job().apply { complete() }
        assertFalse(PlayerManager.shouldResumePlaybackSnapshot())

        PlayerManager.playJob = activeJob
        assertTrue(PlayerManager.shouldResumePlaybackSnapshot())

        PlayerManager.playJob = null
        PlayerManager.updateResumePlaybackRequested(true)
        assertTrue(PlayerManager.shouldResumePlaybackSnapshot())
    }

    @Test
    fun `queue updates publish the transformed snapshot and bump the display revision on request`() {
        val first = song(1L)
        val second = song(2L)
        PlayerManager.publishCurrentQueue(listOf(first, second), 0)
        val revision = PlayerManager.currentQueueDisplayRevisionFlow.value

        val selected = PlayerManager.updateCurrentQueue { it.selecting(1) }
        assertEquals(1, selected?.currentIndex)
        assertSame(second, PlayerManager.currentPlaylist[PlayerManager.currentIndex])
        assertEquals(revision, PlayerManager.currentQueueDisplayRevisionFlow.value)

        PlayerManager.updateCurrentQueue(bumpDisplayRevision = true) { it.selecting(0) }
        assertEquals(0, PlayerManager.currentIndex)
        assertEquals(revision + 1, PlayerManager.currentQueueDisplayRevisionFlow.value)

        assertNull(PlayerManager.updateCurrentQueue(bumpDisplayRevision = true) { null })
        assertEquals(0, PlayerManager.currentIndex)
        assertEquals(revision + 1, PlayerManager.currentQueueDisplayRevisionFlow.value)
    }

    @Test
    fun `track end fallback records the current song key for deduplication`() {
        PlayerManager._currentSongFlow.value = null
        PlayerManager.markTrackEndHandledForStatsFallback()
        assertEquals(PENDING_TRACK_END_DEDUPLICATION_KEY, PlayerManager.lastHandledTrackEndKey)

        val current = song(3L)
        PlayerManager._currentSongFlow.value = current
        PlayerManager.markTrackEndHandledForStatsFallback()
        assertEquals(current.stableKey(), PlayerManager.lastHandledTrackEndKey)
    }

    @Test
    fun `current song identity ignores display-only edits`() {
        val current = song(4L)
        PlayerManager._currentSongFlow.value = null
        assertFalse(PlayerManager.isCurrentSong(current))

        PlayerManager._currentSongFlow.value = current
        assertTrue(PlayerManager.isCurrentSong(current.copy(customName = "Renamed")))
        assertFalse(PlayerManager.isCurrentSong(song(5L)))
    }

    @Test
    fun `player actions are dropped until the player exists`() {
        assertFalse(PlayerManager.isPlayerInitialized())
        var ran = false

        PlayerManager.runPlayerActionOnMainThread { ran = true }

        assertFalse(ran)
    }

    @Test
    fun `local song switches are not blocked outside a listen together room`() {
        val song = song(6L)

        assertFalse(PlayerManager.shouldBlockLocalSongSwitch(song, PlaybackCommandSource.REMOTE_SYNC))
        assertFalse(PlayerManager.shouldBlockLocalSongSwitch(song, PlaybackCommandSource.LOCAL))
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
