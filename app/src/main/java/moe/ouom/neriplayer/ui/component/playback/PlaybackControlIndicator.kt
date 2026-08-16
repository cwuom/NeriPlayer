package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val PLAYBACK_WAITING_VISUAL_DELAY_MS = 1_000L

internal enum class PlaybackControlVisualState {
    PLAY,
    PAUSE,
    RESTORE_VOLUME,
    WAITING
}

@Composable
internal fun PlaybackControlIndicator(
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playContentDescription: String,
    pauseContentDescription: String,
    waitingContentDescription: String,
    isAudioRouteMuted: Boolean = false,
    restoreVolumeContentDescription: String = playContentDescription,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    progressIndicatorSize: Dp = 24.dp,
    progressStrokeWidth: Dp = 2.5.dp
) {
    val delayedPlaybackWaiting = rememberDelayedPlaybackWaiting(isPlaybackWaiting)
    val visualState = resolvePlaybackControlVisualState(
        isPlaying = isPlaying,
        isPlaybackWaiting = delayedPlaybackWaiting,
        isAudioRouteMuted = isAudioRouteMuted
    )
    val resolvedContentDescription = resolvePlaybackControlContentDescription(
        isPlaying = isPlaying,
        isPlaybackWaiting = delayedPlaybackWaiting,
        playContentDescription = playContentDescription,
        pauseContentDescription = pauseContentDescription,
        waitingContentDescription = waitingContentDescription,
        isAudioRouteMuted = isAudioRouteMuted,
        restoreVolumeContentDescription = restoreVolumeContentDescription
    )

    AnimatedContent(
        targetState = visualState,
        modifier = modifier,
        label = "playback_control_indicator",
        transitionSpec = {
            (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
        }
    ) { state ->
        when (state) {
            PlaybackControlVisualState.PLAY -> Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = resolvedContentDescription,
                tint = color
            )

            PlaybackControlVisualState.PAUSE -> Icon(
                imageVector = Icons.Outlined.Pause,
                contentDescription = resolvedContentDescription,
                tint = color
            )

            PlaybackControlVisualState.RESTORE_VOLUME -> Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = resolvedContentDescription,
                tint = color
            )

            PlaybackControlVisualState.WAITING -> CircularProgressIndicator(
                modifier = Modifier
                    .size(progressIndicatorSize)
                    .semantics {
                        contentDescription = resolvedContentDescription
                        stateDescription = waitingContentDescription
                    },
                color = color,
                strokeWidth = progressStrokeWidth
            )
        }
    }
}

internal fun resolvePlaybackControlVisualState(
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    isAudioRouteMuted: Boolean
): PlaybackControlVisualState {
    return when {
        isAudioRouteMuted -> PlaybackControlVisualState.RESTORE_VOLUME
        isPlaybackWaiting -> PlaybackControlVisualState.WAITING
        isPlaying -> PlaybackControlVisualState.PAUSE
        else -> PlaybackControlVisualState.PLAY
    }
}

@Composable
internal fun rememberDelayedPlaybackWaiting(
    isPlaybackWaiting: Boolean,
    delayMillis: Long = PLAYBACK_WAITING_VISUAL_DELAY_MS
): Boolean {
    var showWaiting by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaybackWaiting, delayMillis) {
        if (!isPlaybackWaiting) {
            showWaiting = false
            return@LaunchedEffect
        }

        // 短缓冲先别闪等待圈，超过 1 秒再显示会更稳
        showWaiting = false
        delay(delayMillis)
        showWaiting = true
    }

    return showWaiting
}

internal fun resolvePlaybackControlContentDescription(
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playContentDescription: String,
    pauseContentDescription: String,
    waitingContentDescription: String,
    isAudioRouteMuted: Boolean = false,
    restoreVolumeContentDescription: String = playContentDescription
): String {
    return when {
        isAudioRouteMuted -> restoreVolumeContentDescription
        isPlaybackWaiting -> waitingContentDescription
        isPlaying -> pauseContentDescription
        else -> playContentDescription
    }
}

internal fun resolvePlaybackWaiting(
    playbackRequested: Boolean,
    isPlaying: Boolean,
    usbPlaybackPreparing: Boolean
): Boolean {
    return playbackRequested && (!isPlaying || usbPlaybackPreparing)
}
