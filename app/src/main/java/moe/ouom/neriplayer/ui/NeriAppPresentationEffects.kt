package moe.ouom.neriplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle

internal data class AppStylePreferences(
    val uiStyle: UiStyle,
    val liquidGlassEnabled: Boolean,
    val predictiveBackPreviewEnabled: Boolean
)

internal data class NowPlayingBackgroundMode(val dynamic: Boolean, val audioReactive: Boolean)

internal fun resolveNowPlayingBackgroundMode(
    dynamicRequested: Boolean,
    coverBlurEnabled: Boolean,
    audioReactiveRequested: Boolean
): NowPlayingBackgroundMode {
    val dynamic = dynamicRequested && !coverBlurEnabled
    return NowPlayingBackgroundMode(dynamic, audioReactiveRequested && dynamic)
}

@Composable
internal fun rememberAppStylePreferences(
    repo: SettingsRepository,
    initialUiStyle: UiStyle
): AppStylePreferences {
    val uiStyle by repo.uiStyleFlow.collectAsStateWithLifecycle(initialValue = initialUiStyle)
    val liquidGlassEnabled by repo.liquidGlassEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val predictiveBackPreviewEnabled by repo.predictiveBackPreviewEnabledFlow
        .collectAsStateWithLifecycle(
            initialValue = AutoSettingsSchema.motion.predictiveBackPreviewEnabled.defaultValue
        )
    SideEffect { NeriMotionStyle.clean = uiStyle == UiStyle.Clean }
    return AppStylePreferences(
        uiStyle, liquidGlassEnabled, predictiveBackPreviewEnabled
    )
}

private const val BackdropRecedeScale = 0.06f
private val BackdropRecedeCorner = 28.dp

internal fun Modifier.drawNowPlayingUnderlay(
    flight: SharedCoverFlightState,
    playbackVisible: () -> Boolean,
    backProgress: () -> Float
): Modifier = drawWithContent {
    // 完整遮挡才省去下层绘制，任何露底或交接阶段都沿用原画面
    val completelyCovered = playbackVisible() &&
        backProgress() == 0f &&
        flight.dragOpenProgress == null &&
        !flight.linked &&
        flight.nowPlayingMorphProgressState?.value == 1f
    if (!completelyCovered) drawContent()
}

/** 手机播放页展开时让底层界面适当后退 */
internal fun Modifier.nowPlayingBackdropRecede(flight: SharedCoverFlightState): Modifier =
    graphicsLayer {
        val progress = flight.nowPlayingMorphProgress.coerceIn(0f, 1f)
        // fully open the page covers everything, so the scaffold can stay untransformed
        if (progress <= 0.001f || progress >= 0.999f) return@graphicsLayer
        val scale = 1f - BackdropRecedeScale * progress
        scaleX = scale
        scaleY = scale
        shape = RoundedCornerShape(BackdropRecedeCorner * progress)
        clip = true
    }

/** Window and player side effects that follow the Now Playing overlay visibility. */
@Composable
internal fun NowPlayingPresentationEffects(
    showNowPlaying: Boolean,
    lifecycleResumed: Boolean,
    audioReactiveEnabled: Boolean,
    keepScreenOn: Boolean
) {
    DisposableEffect(showNowPlaying, audioReactiveEnabled, lifecycleResumed) {
        AudioReactive.enabled = showNowPlaying && audioReactiveEnabled && lifecycleResumed
        onDispose { AudioReactive.enabled = false }
    }

    DisposableEffect(showNowPlaying, lifecycleResumed) {
        PlayerManager.updateInteractiveNowPlayingVisible(showNowPlaying && lifecycleResumed)
        onDispose { PlayerManager.updateInteractiveNowPlayingVisible(false) }
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, showNowPlaying, keepScreenOn, lifecycleResumed) {
        val window = activity?.window
        val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val shouldKeepScreenOn = showNowPlaying && keepScreenOn && lifecycleResumed
        val wasKeepScreenOn = window?.attributes?.flags?.and(keepScreenOnFlag) == keepScreenOnFlag
        if (shouldKeepScreenOn) {
            window?.addFlags(keepScreenOnFlag)
        }
        onDispose {
            if (shouldKeepScreenOn && !wasKeepScreenOn) {
                window?.clearFlags(keepScreenOnFlag)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
