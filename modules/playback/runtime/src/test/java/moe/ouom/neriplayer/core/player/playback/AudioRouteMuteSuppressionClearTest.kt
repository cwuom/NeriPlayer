package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioRouteMuteSuppressionClearTest {

    private val manager = PlayerManager
    private val previousRestoreVolume = manager.audioRouteMuteRestoreVolume
    private val previousRequiresExplicitRestore = manager.audioRouteMuteRequiresExplicitRestore
    private val previousSuppressed = manager._audioRouteMuteSuppressedFlow.value
    private val previousSong = manager._currentSongFlow.value
    private val previousWatchdogJob = manager.playbackRuntimeWatchdogJob
    private val previousWatchdogToken = manager.playbackRuntimeWatchdogToken
    private val previousStallAttempts = manager.playbackRuntimeStallRecoveryAttempts
    private val previousProgressPositionMs = manager.playbackRuntimeLastProgressPositionMs
    private val previousProgressAtMs = manager.playbackRuntimeLastProgressAtElapsedRealtimeMs

    @Before
    fun setUp() {
        manager.playbackRuntimeWatchdogJob = null
        manager.playbackRuntimeWatchdogToken = 20L
        manager._currentSongFlow.value = null
    }

    @After
    fun tearDown() {
        manager.audioRouteMuteRestoreVolume = previousRestoreVolume
        manager.audioRouteMuteRequiresExplicitRestore = previousRequiresExplicitRestore
        manager._audioRouteMuteSuppressedFlow.value = previousSuppressed
        manager._currentSongFlow.value = previousSong
        manager.playbackRuntimeWatchdogJob = previousWatchdogJob
        manager.playbackRuntimeWatchdogToken = previousWatchdogToken
        manager.playbackRuntimeStallRecoveryAttempts = previousStallAttempts
        manager.playbackRuntimeLastProgressPositionMs = previousProgressPositionMs
        manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = previousProgressAtMs
    }

    @Test
    fun `clearing an active route mute restarts runtime stall tracking`() {
        val watchdog = Job()
        manager.playbackRuntimeWatchdogJob = watchdog
        manager._currentSongFlow.value = SONG
        suppress(restoreVolume = 0.7f, requiresExplicitRestore = true)

        manager.clearAudioRouteMuteSuppression("route_restored", preserveExplicitRestore = false)

        assertNotSuppressed()
        assertTrue(watchdog.isCancelled)
        assertEquals(21L, manager.playbackRuntimeWatchdogToken)
    }

    @Test
    fun `clearing without a remembered volume only drops the suppression flags`() {
        suppress(restoreVolume = null, requiresExplicitRestore = true)

        manager.clearAudioRouteMuteSuppression("route_restored", preserveExplicitRestore = false)

        assertNotSuppressed()
        assertEquals(20L, manager.playbackRuntimeWatchdogToken)
    }

    @Test
    fun `explicit listener mute is kept while it must be restored explicitly`() {
        manager._currentSongFlow.value = SONG
        suppress(restoreVolume = 0.7f, requiresExplicitRestore = true)

        manager.clearAudioRouteMuteSuppression("route_restored", preserveExplicitRestore = true)

        assertEquals(0.7f, manager.audioRouteMuteRestoreVolume)
        assertTrue(manager.audioRouteMuteRequiresExplicitRestore)
        assertTrue(manager._audioRouteMuteSuppressedFlow.value)
        assertEquals(20L, manager.playbackRuntimeWatchdogToken)
    }

    @Test
    fun `preserving restore does not keep a mute that needs no explicit restore`() {
        suppress(restoreVolume = 0.4f, requiresExplicitRestore = false)

        manager.clearAudioRouteMuteSuppression("route_restored", preserveExplicitRestore = true)

        assertNotSuppressed()
        assertEquals(21L, manager.playbackRuntimeWatchdogToken)
    }

    private fun suppress(restoreVolume: Float?, requiresExplicitRestore: Boolean) {
        manager.audioRouteMuteRestoreVolume = restoreVolume
        manager.audioRouteMuteRequiresExplicitRestore = requiresExplicitRestore
        manager._audioRouteMuteSuppressedFlow.value = true
    }

    private fun assertNotSuppressed() {
        assertNull(manager.audioRouteMuteRestoreVolume)
        assertFalse(manager.audioRouteMuteRequiresExplicitRestore)
        assertFalse(manager._audioRouteMuteSuppressedFlow.value)
    }

    private companion object {
        val SONG = SongItem(
            id = 7L,
            name = "Song 7",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 200_000L,
            coverUrl = null
        )
    }
}
