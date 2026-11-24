package moe.ouom.neriplayer.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.graphics.graphicsLayer
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
    val predictiveBackPreviewEnabled: Boolean,
    val enhancedVisualEffectsEnabled: Boolean
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
    val enhancedVisualEffectsEnabled by repo.enhancedVisualEffectsEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val predictiveBackPreviewEnabled by repo.predictiveBackPreviewEnabledFlow
        .collectAsStateWithLifecycle(
            initialValue = AutoSettingsSchema.motion.predictiveBackPreviewEnabled.defaultValue
        )
    SideEffect { NeriMotionStyle.clean = uiStyle == UiStyle.Clean }
    return AppStylePreferences(
        uiStyle, liquidGlassEnabled, predictiveBackPreviewEnabled, enhancedVisualEffectsEnabled
    )
}

private const val BackdropRecedeScale = 0.06f
private val BackdropRecedeCorner = 28.dp

/** 完整遮挡才省去下层绘制，任何露底或交接阶段都沿用原画面 */
internal fun isNowPlayingUnderlayCovered(
    playbackVisible: Boolean,
    backProgress: Float,
    dragOpenProgress: Float?,
    linked: Boolean,
    morphProgress: Float?
): Boolean = playbackVisible &&
    backProgress == 0f &&
    dragOpenProgress == null &&
    !linked &&
    morphProgress == 1f

internal fun Modifier.drawNowPlayingUnderlay(
    flight: SharedCoverFlightState,
    playbackVisible: () -> Boolean,
    backProgress: () -> Float
): Modifier = this then NowPlayingUnderlayElement(flight, playbackVisible, backProgress)

private data class NowPlayingUnderlayElement(
    val flight: SharedCoverFlightState,
    val playbackVisible: () -> Boolean,
    val backProgress: () -> Float
) : ModifierNodeElement<NowPlayingUnderlayNode>() {
    override fun create() = NowPlayingUnderlayNode(flight, playbackVisible, backProgress)

    override fun update(node: NowPlayingUnderlayNode) {
        node.flight = flight
        node.playbackVisible = playbackVisible
        node.backProgress = backProgress
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawNowPlayingUnderlay"
    }
}

// 绘制只读派生出的遮挡结果; 形变和返回进度逐帧变化时不再让下层整页重新录制
private class NowPlayingUnderlayNode(
    flight: SharedCoverFlightState,
    playbackVisible: () -> Boolean,
    backProgress: () -> Float
) : Modifier.Node(), DrawModifierNode {
    var flight by mutableStateOf(flight)
    var playbackVisible by mutableStateOf(playbackVisible)
    var backProgress by mutableStateOf(backProgress)

    private val covered = derivedStateOf {
        val current = this.flight
        isNowPlayingUnderlayCovered(
            playbackVisible = this.playbackVisible(),
            backProgress = this.backProgress(),
            dragOpenProgress = current.dragOpenProgress,
            linked = current.linked,
            morphProgress = current.nowPlayingMorphProgressState?.value
        )
    }

    override fun ContentDrawScope.draw() {
        if (!covered.value) drawContent()
    }
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

    NowPlayingKeepScreenOnEffect(showNowPlaying, lifecycleResumed, keepScreenOn)
}

@Composable
internal fun NowPlayingKeepScreenOnEffect(
    showNowPlaying: Boolean,
    lifecycleResumed: Boolean,
    keepScreenOn: Boolean
) {
    val activity = LocalActivity.current
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
