package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PlayerManagerStatePersistenceGuardTest {
    private val previousQueue = PlayerManager.currentQueueSnapshot()
    private val previousPersistAtMs = PlayerManager.lastStatePersistAtMs

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
    }

    @After
    fun tearDown() {
        PlayerManager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        PlayerManager.lastStatePersistAtMs = previousPersistAtMs
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `state is not persisted before the player is initialized`() = runTest {
        assertFalse(PlayerManager.initialized)

        PlayerManager.publishCurrentQueue(emptyList(), -1)
        assertFalse(PlayerManager.persistStateNow(reason = "empty_queue"))

        PlayerManager.publishCurrentQueue(listOf(song(7L)), 0)
        assertFalse(PlayerManager.persistStateNow(reason = "queued"))
        assertFalse(
            PlayerManager.persistStateNow(
                positionMs = 1_000L,
                shouldResumePlayback = true,
                reason = "explicit"
            )
        )
    }

    @Test
    fun `failed state writes are contained and not recorded as persisted`() = runTest {
        assertFalse(PlayerManager.isApplicationInitialized())
        PlayerManager.lastStatePersistAtMs = 123L

        PlayerManager.publishCurrentQueue(emptyList(), -1)
        PlayerManager.persistState()
        PlayerManager.publishCurrentQueue(listOf(song(8L)), 0)
        PlayerManager.persistState()
        PlayerManager.persistState(positionMs = 5_000L, shouldResumePlayback = false)

        assertEquals(123L, PlayerManager.lastStatePersistAtMs)
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
