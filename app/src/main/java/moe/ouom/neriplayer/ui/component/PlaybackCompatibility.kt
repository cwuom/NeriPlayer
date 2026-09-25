package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    enableBlur: Boolean = true,
    offlineMode: Boolean = false,
    isPlaybackWaiting: Boolean = false,
    isAudioRouteMuted: Boolean = false,
    visualCoverUrl: String? = null,
    coverIdentityKey: String? = null,
    visualCoverIdentityKey: String? = null,
    hasCurrentSong: Boolean = true
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
        enableBlur = enableBlur,
        offlineMode = offlineMode,
        isPlaybackWaiting = isPlaybackWaiting,
        isAudioRouteMuted = isAudioRouteMuted,
        visualCoverUrl = visualCoverUrl,
        coverIdentityKey = coverIdentityKey,
        visualCoverIdentityKey = visualCoverIdentityKey,
        hasCurrentSong = hasCurrentSong
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
    modifier: Modifier = Modifier,
    onValueChangeStarted: (Float) -> Unit = {},
    onValueChangeCanceled: () -> Unit = {},
    enabled: Boolean = true,
    isPlaybackWaiting: Boolean = false
) {
    moe.ouom.neriplayer.ui.component.playback.WaveformSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        isPlaying = isPlaying,
        onValueChangeStarted = onValueChangeStarted,
        onValueChangeCanceled = onValueChangeCanceled,
        enabled = enabled,
        modifier = modifier,
        isPlaybackWaiting = isPlaybackWaiting
    )
}
