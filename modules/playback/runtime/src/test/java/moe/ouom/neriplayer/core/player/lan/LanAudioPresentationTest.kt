package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.lanltw.audio.LanAudioState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanAudioPresentationTest {
    @Test fun lyriconFollowsActualPlaybackSeeksAndRoomExit() {
        val player = PlayerManager
        val wasEnabled = player.lyriconEnabled
        val externalAudio = LanPlaybackBinding.externalAudio
        val previousPlaying = player._isPlayingFlow.value
        val previousPosition = player._playbackPositionMs.value
        val previousDuration = player.playbackDurationFlow.value
        val previousStats = player.playbackStatsOwner
        val previousState = player._playerPlaybackStateFlow.value
        val previousRequested = player._playWhenReadyFlow.value
        val previousControl = player._playbackControlPlayingFlow.value
        val playing = mutableListOf<Boolean>()
        val positions = mutableListOf<Long>()
        val presentation = LanAudioPresentation({ playing.add(it) }, { position, duration, speed ->
            assertEquals(60_000L, duration)
            assertEquals(1f, speed, 0f)
            positions.add(position)
        })
        try {
            player.playbackStatsOwner = mock(PlaybackStatsOwner::class.java)
            player.lyriconEnabled = true
            LanPlaybackBinding.externalAudio = true
            val start = LanAudioState(playing = true, positionSeconds = 1.0, durationSeconds = 60.0)
            presentation.update(start)
            presentation.update(start.copy(positionSeconds = 2.0))
            val buffering = start.copy(playing = false, positionSeconds = 2.0)
            presentation.update(buffering, Player.STATE_BUFFERING, playbackRequested = true)
            presentation.update(buffering, Player.STATE_BUFFERING, playbackRequested = true)
            presentation.update(buffering.copy(positionSeconds = 42.0))
            presentation.update(start.copy(positionSeconds = 42.0))
            presentation.end()
            presentation.update(start.copy(positionSeconds = 42.0))
            assertEquals(listOf(true, false, true, false, true), playing)
            assertEquals(listOf(1000L, 2000L, 2000L, 42000L, 42000L, 42000L, 42000L), positions)
        } finally {
            player.lyriconEnabled = wasEnabled
            LanPlaybackBinding.externalAudio = externalAudio
            player.playbackStatsOwner = previousStats
            player._playbackPositionMs.value = previousPosition
            player.playbackProgressOwner.setDuration(previousDuration)
            player._isPlayingFlow.value = previousPlaying
            player._playerPlaybackStateFlow.value = previousState
            player._playWhenReadyFlow.value = previousRequested
            player._playbackControlPlayingFlow.value = previousControl
        }
    }
}
