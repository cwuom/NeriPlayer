package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerPlaybackRecoveryTest {
    private val manager = PlayerManager
    private val previousQueue = manager.currentQueueSnapshot()
    private val previousSong = manager._currentSongFlow.value
    private val previousPosition = manager._playbackPositionMs.value
    private val previousToken = manager.playbackRequestToken
    private val previousRecovery = manager.serverRecoveryPosition
    private val previousRestoredPosition = manager.restoredResumePositionMs
    private val previousRestoredResume = manager.restoredShouldResumePlayback
    private val previousRememberProgress = manager.keepLastPlaybackProgressEnabled
    private val previousPendingSeek = manager.pendingSeekPositionOrNull()
    private val previousPendingLoad = manager.pendingMediaLoadActive
    private val previousInitialized = manager.initialized
    private val previousMainScope = manager.mainScope
    private val previousIoScope = manager.ioScope
    private val ref = ServerSongRef("00000000-0000-0000-0000-000000000001", "song-1")
    private val song = SongItem(
        1L, "Server song", "Artist", "Album", 0L, 180_000L, null,
        channelId = ServerSongRef.CHANNEL, audioId = ref.audioId, mediaUri = ref.mediaUri
    )

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
        manager.publishCurrentQueue(listOf(song), 0)
        manager._currentSongFlow.value = song
        manager._playbackPositionMs.value = 30_000L
        manager.playbackRequestToken = 10L
        manager.serverRecoveryPosition = null
        manager.keepLastPlaybackProgressEnabled = false
        manager.pendingMediaLoadActive = true
        manager.clearPendingSeekPosition()
    }

    @After
    fun tearDown() {
        manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        manager._currentSongFlow.value = previousSong
        manager._playbackPositionMs.value = previousPosition
        manager.playbackRequestToken = previousToken
        manager.serverRecoveryPosition = previousRecovery
        manager.setRestoredPlayback(previousRestoredPosition, previousRestoredResume)
        manager.keepLastPlaybackProgressEnabled = previousRememberProgress
        manager.clearPendingSeekPosition()
        previousPendingSeek?.let(manager::rememberPendingSeekPosition)
        manager.pendingMediaLoadActive = previousPendingLoad
        manager.initialized = previousInitialized
        manager.mainScope = previousMainScope
        manager.ioScope = previousIoScope
        Dispatchers.resetMain()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `URL failure preserves a seek made while the request was pending`() = preservesPosition(75_000L)

    @Test
    fun `URL failure preserves an explicit seek to zero`() = preservesPosition(0L)

    @Test
    fun `URL failure without a seek preserves the original resume position`() = preservesPosition(null)

    private fun preservesPosition(pendingSeek: Long?) {
        FakeExoPlayer().installInto(manager) {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                manager.mainScope = backgroundScope
                manager.ioScope = backgroundScope
                pendingSeek?.let(manager::rememberPendingSeekPosition)

                assertTrue(manager.preserveServerPlaybackForRetry(song, 30_000L, 10L))

                val expected = pendingSeek ?: 30_000L
                assertEquals(expected, manager.playbackPositionFlow.value)
                assertEquals(expected, manager.restoredResumePositionMs)
                assertFalse(manager.restoredShouldResumePlayback)
                assertEquals(AppQueueSongIdentity.stableKey(song) to expected, manager.serverRecoveryPosition)
                assertEquals(expected, manager.resolveManualResumeWithServerRecovery(song, false).resumePositionMs)
                assertEquals(song, manager.currentSongFlow.value)
                assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
                assertNull(manager.pendingSeekPositionOrNull())
                assertFalse(manager.keepLastPlaybackProgressEnabled)
            }
        }
    }

    @Test
    fun `a stale failure does not consume the newer request's seek or recovery position`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        manager.rememberPendingSeekPosition(75_000L)
        val recovery = AppQueueSongIdentity.stableKey(song) to 60_000L
        manager.serverRecoveryPosition = recovery

        assertTrue(manager.preserveServerPlaybackForRetry(song, 30_000L, 9L))

        assertEquals(10L, manager.playbackRequestToken)
        assertEquals(30_000L, manager.playbackPositionFlow.value)
        assertEquals(75_000L, manager.pendingSeekPositionOrNull())
        assertEquals(recovery, manager.serverRecoveryPosition)
    }

    @Test
    fun `ordinary sources keep their existing URL failure path`() = runTest {
        val ordinarySong = song.copy(channelId = null, audioId = null, mediaUri = null)
        assertFalse(manager.preserveServerPlaybackForRetry(ordinarySong, 30_000L, 10L))
        assertNull(manager.serverRecoveryPosition)
        assertEquals(10L, manager.playbackRequestToken)
    }

    @Test
    fun `release clears session recovery even when the player is already released`() {
        manager.serverRecoveryPosition = AppQueueSongIdentity.stableKey(song) to 60_000L
        manager.initialized = false
        assertEquals(60_000L, manager.resolveManualResumeWithServerRecovery(song, false).resumePositionMs)

        manager.release()

        assertNull(manager.serverRecoveryPosition)
        assertEquals(0L, manager.resolveManualResumeWithServerRecovery(song, false).resumePositionMs)
    }
}
