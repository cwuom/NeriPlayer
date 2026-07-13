package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState

typealias NeriMiniPlayerDefaults = moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
typealias PlaybackSourceType = moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType

@Composable
fun NeriMiniPlayer(
    title: String,
    artist: String,
    coverUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    playPauseEnabled: Boolean = true,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    hazeState: HazeState,
    enableHaze: Boolean = true,
    offlineMode: Boolean = false
) {
    moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer(
        title = title,
        artist = artist,
        coverUrl = coverUrl,
        isPlaying = isPlaying,
        modifier = modifier,
        playPauseEnabled = playPauseEnabled,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onExpand = onExpand,
        hazeState = hazeState,
        enableHaze = enableHaze,
        offlineMode = offlineMode
    )
}

@Composable
fun PlaybackSoundSheet(
    state: PlaybackSoundState,
    onSpeedChange: (Float, Boolean) -> Unit,
    onPitchChange: (Float, Boolean) -> Unit,
    onLoudnessGainChange: (Int, Boolean) -> Unit,
    onEqualizerEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandLevelChange: (Int, Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    moe.ouom.neriplayer.ui.component.playback.PlaybackSoundSheet(
        state = state,
        onSpeedChange = onSpeedChange,
        onPitchChange = onPitchChange,
        onLoudnessGainChange = onLoudnessGainChange,
        onEqualizerEnabledChange = onEqualizerEnabledChange,
        onPresetSelected = onPresetSelected,
        onBandLevelChange = onBandLevelChange,
        onReset = onReset,
        onDismiss = onDismiss
    )
}

@Composable
fun PlaybackSourceBadge(
    source: PlaybackSourceType,
    modifier: Modifier = Modifier
) {
    moe.ouom.neriplayer.ui.component.playback.PlaybackSourceBadge(
        source = source,
        modifier = modifier
    )
}

@Composable
fun SleepTimerDialog(
    onDismiss: () -> Unit
) {
    moe.ouom.neriplayer.ui.component.playback.SleepTimerDialog(onDismiss = onDismiss)
}

@Composable
fun WaveformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    onValueChangeStarted: (Float) -> Unit = {},
    onValueChangeCanceled: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    moe.ouom.neriplayer.ui.component.playback.WaveformSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        isPlaying = isPlaying,
        onValueChangeStarted = onValueChangeStarted,
        onValueChangeCanceled = onValueChangeCanceled,
        enabled = enabled,
        modifier = modifier
    )
}

