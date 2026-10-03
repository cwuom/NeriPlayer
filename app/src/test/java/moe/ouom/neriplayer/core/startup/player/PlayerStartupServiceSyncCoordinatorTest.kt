package moe.ouom.neriplayer.core.startup.player

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStartupServiceSyncCoordinatorTest {
    @Test
    fun `foreground recovery retries app bootstrap before transport is active`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var accepted = false
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            bootstrapRequired = { true },
            serviceStartAccepted = { accepted }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE, true)

        accepted = true
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf(
                PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE to true,
                PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE to true
            ),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `foreground recovery retries preempt focus bootstrap without playback`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var accepted = false
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            preemptAudioFocus = { true },
            serviceStartAccepted = { accepted }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE, false)

        accepted = true
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf(
                PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE to false,
                PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE to false
            ),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `cancelled restored playback discards app bootstrap without starting playback`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var bootstrapRequired = true
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            bootstrapRequired = { bootstrapRequired },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE, true)

        bootstrapRequired = false
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `disabled preempt focus discards its pending bootstrap`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var preemptAudioFocus = true
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            preemptAudioFocus = { preemptAudioFocus },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE, false)

        preemptAudioFocus = false
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `enabled mixed playback discards pending preempt focus bootstrap`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var allowMixedPlayback = false
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            preemptAudioFocus = { true },
            allowMixedPlayback = { allowMixedPlayback },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE, false)

        allowMixedPlayback = true
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `cleared queue discards both bootstrap sources`() = runTest {
        for (source in listOf(
            PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE,
            PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE
        )) {
            val starts = mutableListOf<Pair<String, Boolean>>()
            var hasItems = true
            val coordinator = coordinator(
                starts = starts,
                hasItems = { hasItems },
                playbackActive = { false },
                bootstrapRequired = { source == PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE },
                preemptAudioFocus = { true },
                serviceStartAccepted = { false }
            )
            coordinator.requestServiceStart(source, source == PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE)

            hasItems = false
            coordinator.retryPendingServiceStart()

            assertEquals(1, starts.size)
            assertNull(coordinator.pendingServiceStartFlow.value)
        }
    }

    @Test
    fun `new restored intent upgrades pending preempt bootstrap to foreground bootstrap`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var bootstrapRequired = false
        var accepted = false
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            bootstrapRequired = { bootstrapRequired },
            preemptAudioFocus = { true },
            serviceStartAccepted = { accepted }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE, false)

        bootstrapRequired = true
        accepted = true
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf(
                PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE to false,
                PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE to true
            ),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `cancelled restoration replans bootstrap as passive preempt focus session`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var bootstrapRequired = true
        var accepted = false
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { false },
            bootstrapRequired = { bootstrapRequired },
            preemptAudioFocus = { true },
            serviceStartAccepted = { accepted }
        )
        coordinator.requestServiceStart(PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE, true)

        bootstrapRequired = false
        accepted = true
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf(
                PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE to true,
                PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE to false
            ),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `rejected bootstrap recovery remains pending without retry looping`() = runTest {
        for (bootstrapRequired in listOf(true, false)) {
            val starts = mutableListOf<Pair<String, Boolean>>()
            val source = if (bootstrapRequired) {
                PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE
            } else {
                PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE
            }
            val coordinator = coordinator(
                starts = starts,
                playbackActive = { false },
                bootstrapRequired = { bootstrapRequired },
                preemptAudioFocus = { true },
                serviceStartAccepted = { false }
            )
            coordinator.requestServiceStart(source, bootstrapRequired)
            val observer = backgroundScope.launch {
                coordinator.pendingServiceStartFlow.filterNotNull().collect {
                    coordinator.retryPendingServiceStart()
                }
            }
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(2, starts.size)
            assertEquals(
                PlayerStartupServiceStart(source, bootstrapRequired),
                coordinator.pendingServiceStartFlow.value
            )
            observer.cancel()
        }
    }

    @Test
    fun `rejected service start reports failure`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        val coordinator = coordinator(starts = starts, serviceStartAccepted = { false })

        val accepted = coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        assertEquals(false, accepted)
        assertEquals(listOf("play_songs_and_open_now_playing" to true), starts)
        assertEquals(
            PlayerStartupServiceStart("play_songs_and_open_now_playing", true),
            coordinator.pendingServiceStartFlow.value
        )
    }

    @Test
    fun `foreground recovery retries a rejected start and clears it after success`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var accepted = false
        val coordinator = coordinator(starts = starts, serviceStartAccepted = { accepted })
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        accepted = true
        coordinator.retryPendingServiceStart()
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf("play_songs_and_open_now_playing" to true, "play_songs_and_open_now_playing" to true),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `foreground observer repairs a request rejected after observation started`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        val coordinator = coordinator(starts = starts, serviceStartAccepted = { starts.size > 1 })
        backgroundScope.launch {
            coordinator.pendingServiceStartFlow.filterNotNull().collect {
                coordinator.retryPendingServiceStart()
            }
        }
        runCurrent()

        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)
        runCurrent()

        assertEquals(2, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `another rejection stays pending without looping until foreground observation restarts`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var accepted = false
        val coordinator = coordinator(starts = starts, serviceStartAccepted = { accepted })
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)
        val firstObserver = backgroundScope.launch {
            coordinator.pendingServiceStartFlow.filterNotNull().collect {
                coordinator.retryPendingServiceStart()
            }
        }
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, starts.size)
        assertEquals(
            PlayerStartupServiceStart("play_songs_and_open_now_playing", true),
            coordinator.pendingServiceStartFlow.value
        )

        firstObserver.cancel()
        accepted = true
        backgroundScope.launch {
            coordinator.pendingServiceStartFlow.filterNotNull().collect {
                coordinator.retryPendingServiceStart()
            }
        }
        runCurrent()

        assertEquals(3, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `paused playback discards the rejected request`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var playbackActive = true
        val coordinator = coordinator(
            starts = starts,
            playbackActive = { playbackActive },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        playbackActive = false
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `cleared queue discards the rejected request`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var hasItems = true
        val coordinator = coordinator(
            starts = starts,
            hasItems = { hasItems },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        hasItems = false
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `foreground recovery replans a local request against the now ready service`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var serviceReady = false
        val coordinator = coordinator(
            starts = starts,
            serviceReady = { serviceReady },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart("local_playback_command_play", true)

        serviceReady = true
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `foreground recovery preserves the USB exception to passive sync deduplication`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var serviceReady = false
        var accepted = false
        val coordinator = coordinator(
            starts = starts,
            serviceReady = { serviceReady },
            usbExclusiveActive = { true },
            serviceStartAccepted = { accepted }
        )
        coordinator.requestServiceStart("local_playback_command_play", true)

        serviceReady = true
        accepted = true
        coordinator.retryPendingServiceStart()

        assertEquals(2, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `successful newer request replaces the rejected request`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var accepted = false
        val coordinator = coordinator(starts = starts, serviceStartAccepted = { accepted })
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        accepted = true
        assertTrue(coordinator.requestServiceStart("play_search_result_preserve_queue", true))
        coordinator.retryPendingServiceStart()

        assertEquals(
            listOf("play_songs_and_open_now_playing" to true, "play_search_result_preserve_queue" to true),
            starts
        )
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

    @Test
    fun `newer request tracked by the service clears the rejected request`() = runTest {
        val starts = mutableListOf<Pair<String, Boolean>>()
        var serviceReady = false
        val coordinator = coordinator(
            starts = starts,
            serviceReady = { serviceReady },
            serviceStartAccepted = { false }
        )
        coordinator.requestServiceStart("play_songs_and_open_now_playing", true)

        serviceReady = true
        assertTrue(coordinator.requestServiceStart("local_playback_command_play", true))
        coordinator.retryPendingServiceStart()

        assertEquals(1, starts.size)
        assertNull(coordinator.pendingServiceStartFlow.value)
    }

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
        commands: MutableSharedFlow<PlaybackCommand>? = null,
        hasItems: () -> Boolean = { true },
        playbackActive: () -> Boolean = { true },
        bootstrapRequired: () -> Boolean = playbackActive,
        preemptAudioFocus: () -> Boolean = { false },
        allowMixedPlayback: () -> Boolean = { false },
        usbExclusiveActive: () -> Boolean = { false },
        serviceStartAccepted: () -> Boolean = { true }
    ): PlayerStartupServiceSyncCoordinator = PlayerStartupServiceSyncCoordinator(
        isServiceReadyForPassiveLocalPlaybackSync = serviceReady,
        hasItems = hasItems,
        hasLocalCurrentSong = { false },
        isUsbExclusivePlaybackActiveForForegroundService = usbExclusiveActive,
        shouldRunPlaybackServiceInForeground = playbackActive,
        currentBootstrapServiceStart = {
            PlayerStartupServicePlanner.plan(
                hasItems = hasItems(),
                shouldBootstrapPlaybackService = bootstrapRequired(),
                preemptAudioFocus = preemptAudioFocus(),
                allowMixedPlayback = allowMixedPlayback()
            )
        },
        startService = { source, foreground ->
            starts.add(source to foreground)
            serviceStartAccepted()
        },
        playbackCommandFlow = commands
    )
}
