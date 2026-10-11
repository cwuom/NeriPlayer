package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerCurrentSongCommandsTest {
    private val manager = PlayerManager
    private val pausedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val previousQueue = manager.currentQueueSnapshot()
    private val previousIoScope = manager.ioScope
    private val previousPosition = manager._playbackPositionMs.value
    private val previousSuppressAutoResume = manager.suppressAutoResumeForCurrentSession

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
        assertFalse(manager.isApplicationInitialized())
        manager.ioScope = pausedScope
    }

    @After
    fun tearDown() {
        manager.initialized = false
        manager._currentSongFlow.value = null
        manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        manager._playbackPositionMs.value = previousPosition
        manager.suppressAutoResumeForCurrentSession = previousSuppressAutoResume
        manager.ioScope = previousIoScope
        pausedScope.cancel()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `current song commands are ignored before initialization or without a current song`() {
        runCurrentSongCommands()
        assertEquals(0, launchedJobs())

        manager.initialized = true
        runCurrentSongCommands()
        assertEquals(0, launchedJobs())
    }

    @Test
    fun `current song commands queue their repository work`() {
        manager.initialized = true
        manager._currentSongFlow.value = song

        runCurrentSongCommands()

        assertEquals(4, launchedJobs())
    }

    @Test
    fun `bili audio playback requires initialization and ignores an empty selection`() {
        assertThrows(IllegalStateException::class.java) {
            manager.playBiliVideoAsAudioImpl(emptyList(), startIndex = 0)
        }

        manager.initialized = true
        manager.playBiliVideoAsAudioImpl(emptyList(), startIndex = 0)

        assertEquals(0, launchedJobs())
    }

    @Test
    fun `auto resume suppression needs an initialized non empty queue`() {
        manager.publishCurrentQueue(listOf(song), 0)
        manager.suppressFutureAutoResumeForCurrentSessionImpl()
        assertFalse(manager.suppressAutoResumeForCurrentSession)

        manager.initialized = true
        manager.publishCurrentQueue(emptyList(), -1)
        manager.suppressFutureAutoResumeForCurrentSessionImpl()
        assertFalse(manager.suppressAutoResumeForCurrentSession)
    }

    @Test
    fun `auto resume suppression keeps the last known position without a player`() {
        manager.initialized = true
        manager.publishCurrentQueue(listOf(song), 0)
        manager._playbackPositionMs.value = -5L

        manager.suppressFutureAutoResumeForCurrentSessionImpl()

        assertTrue(manager.suppressAutoResumeForCurrentSession)
        assertEquals(0L, manager._playbackPositionMs.value)
    }

    @Test
    fun `auto resume suppression captures the player position`() {
        val fake = FakeExoPlayer().apply { positionMs = 42_000L }
        fake.installInto(manager) {
            manager.initialized = true
            manager.publishCurrentQueue(listOf(song), 0)

            manager.suppressFutureAutoResumeForCurrentSessionImpl(forcePersist = true)

            assertTrue(manager.suppressAutoResumeForCurrentSession)
            assertEquals(42_000L, manager._playbackPositionMs.value)
        }
    }

    private fun runCurrentSongCommands() {
        manager.addCurrentToFavoritesImpl()
        manager.removeCurrentFromFavoritesImpl()
        manager.toggleCurrentFavoriteImpl()
        manager.addCurrentToPlaylistImpl(playlistId = 7L)
    }

    private fun launchedJobs(): Int = requireNotNull(pausedScope.coroutineContext[Job]).children.count()

    private val song = SongItem(
        id = 7_200_001L,
        name = "Current",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
