package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private enum class PlaybackControlVisualState {
    PLAY,
    PAUSE,
    WAITING
}

@Composable
internal fun PlaybackControlIndicator(
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playContentDescription: String,
    pauseContentDescription: String,
    waitingContentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    progressIndicatorSize: Dp = 24.dp,
    progressStrokeWidth: Dp = 2.5.dp
) {
    val visualState = when {
        isPlaybackWaiting -> PlaybackControlVisualState.WAITING
        isPlaying -> PlaybackControlVisualState.PAUSE
        else -> PlaybackControlVisualState.PLAY
    }
    val resolvedContentDescription = resolvePlaybackControlContentDescription(
        isPlaying = isPlaying,
        isPlaybackWaiting = isPlaybackWaiting,
        playContentDescription = playContentDescription,
        pauseContentDescription = pauseContentDescription,
        waitingContentDescription = waitingContentDescription
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

internal fun resolvePlaybackControlContentDescription(
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playContentDescription: String,
    pauseContentDescription: String,
    waitingContentDescription: String
): String {
    return when {
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
