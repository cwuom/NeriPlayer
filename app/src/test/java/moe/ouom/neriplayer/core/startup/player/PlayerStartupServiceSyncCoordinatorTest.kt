package moe.ouom.neriplayer.core.startup.player

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStartupServiceSyncCoordinatorTest {
    @Test
    fun `foreground service start completes while the UI frame clock is paused`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        val coordinator = coordinator(starts = starts)
        val job = launch(BroadcastFrameClock()) {
            coordinator.requestServiceStart("play_songs_and_open_now_playing", true)
        }

        try {
            runCurrent()

            assertEquals(listOf("play_songs_and_open_now_playing" to true), starts)
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `local playback command repairs the service while the UI frame clock is paused`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        val commands = MutableSharedFlow<PlaybackCommand>()
        val coordinator = coordinator(starts = starts, commands = commands)
        backgroundScope.launch(BroadcastFrameClock()) {
            coordinator.collectLocalPlaybackCommands()
        }
        runCurrent()

        commands.emit(PlaybackCommand(type = "PLAY", source = PlaybackCommandSource.LOCAL))
        runCurrent()

        assertEquals(listOf("local_playback_command_play" to true), starts)
    }

    @Test
    fun `ready service finishes passive local sync while the UI frame clock is paused`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        val coordinator = coordinator(starts = starts, serviceReady = { true })
        val job = launch(BroadcastFrameClock()) {
            coordinator.requestServiceStart("local_playback_command_play", false)
        }

        try {
            advanceTimeBy(500L)
            runCurrent()

            assertTrue(job.isCompleted)
            assertTrue(starts.isEmpty())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `passive local sync skips a service that becomes ready during its settling delay`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var serviceReady = false
        val coordinator = coordinator(starts = starts, serviceReady = { serviceReady })
        val job = launch(BroadcastFrameClock()) {
            coordinator.requestServiceStart("local_playback_command_pause", false)
        }

        try {
            runCurrent()
            assertTrue(starts.isEmpty())

            serviceReady = true
            advanceTimeBy(500L)
            runCurrent()

            assertTrue(job.isCompleted)
            assertTrue(starts.isEmpty())
        } finally {
            job.cancel()
        }
    }

    private fun coordinator(
        starts: MutableList<Pair<String, Boolean>>,
        serviceReady: () -> Boolean = { false },
        commands: MutableSharedFlow<PlaybackCommand>? = null
    ): PlayerStartupServiceSyncCoordinator = PlayerStartupServiceSyncCoordinator(
        isServiceReadyForPassiveLocalPlaybackSync = serviceReady,
        hasItems = { true },
        hasLocalCurrentSong = { false },
        isUsbExclusivePlaybackActiveForForegroundService = { false },
        shouldRunPlaybackServiceInForeground = { true },
        startService = { source, foreground -> starts.add(source to foreground) },
        playbackCommandFlow = commands
    )
}
