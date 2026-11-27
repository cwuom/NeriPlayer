package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

/** 播放页随机键的三档：网易云歌曲按 默认 → 随机 → 心动 → 默认 循环，其他音源只有前两档 */
internal enum class NowPlayingShuffleMode { OFF, SHUFFLE, HEARTBEAT }

internal fun resolveNowPlayingShuffleMode(
    shuffleEnabled: Boolean,
    heartbeatEnabled: Boolean,
    heartbeatAvailable: Boolean
): NowPlayingShuffleMode = when {
    heartbeatAvailable && heartbeatEnabled -> NowPlayingShuffleMode.HEARTBEAT
    shuffleEnabled -> NowPlayingShuffleMode.SHUFFLE
    else -> NowPlayingShuffleMode.OFF
}

internal fun nextNowPlayingShuffleMode(
    current: NowPlayingShuffleMode,
    heartbeatAvailable: Boolean
): NowPlayingShuffleMode = when (current) {
    NowPlayingShuffleMode.OFF -> NowPlayingShuffleMode.SHUFFLE
    NowPlayingShuffleMode.SHUFFLE ->
        if (heartbeatAvailable) NowPlayingShuffleMode.HEARTBEAT else NowPlayingShuffleMode.OFF
    NowPlayingShuffleMode.HEARTBEAT -> NowPlayingShuffleMode.OFF
}

/**
 * 按目标档位同时设置随机和心动两个开关，保证按钮看到的就是实际生效的。
 * 先关随机再开心动：一起听房间会拒绝开启心动，这时停在默认档，用户还能继续点回随机。
 */
internal fun NowPlayingPlaybackSource.applyShuffleMode(
    target: NowPlayingShuffleMode,
    shuffleEnabled: Boolean,
    heartbeatEnabled: Boolean
) {
    val wantShuffle = target == NowPlayingShuffleMode.SHUFFLE
    val wantHeartbeat = target == NowPlayingShuffleMode.HEARTBEAT
    if (shuffleEnabled != wantShuffle) setShuffle(wantShuffle)
    if (heartbeatEnabled != wantHeartbeat) setHeartbeatEnabled(wantHeartbeat)
}

@Composable
internal fun NowPlayingShuffleModeButton(
    playbackSource: NowPlayingPlaybackSource,
    shuffleEnabled: Boolean,
    heartbeatEnabled: Boolean,
    heartbeatAvailable: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    activeColor: Color,
    inactiveColor: Color,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    val mode = resolveNowPlayingShuffleMode(shuffleEnabled, heartbeatEnabled, heartbeatAvailable)
    val heartbeatHint = stringResource(CoreCommonR.string.player_shuffle_heartbeat_next_hint)
    HapticIconButton(
        onClick = {
            val target = nextNowPlayingShuffleMode(mode, heartbeatAvailable)
            playbackSource.applyShuffleMode(target, shuffleEnabled, heartbeatEnabled)
            // 告诉用户随机之后还有一档，否则只看图标很难发现能切到心动
            if (target == NowPlayingShuffleMode.SHUFFLE && heartbeatAvailable) {
                scope.launch { snackbarHostState.showNeriSnackbar(heartbeatHint) }
            }
        },
        modifier = Modifier.size(buttonSize).testTag("nowPlayingShuffleModeButton")
    ) {
        val tint = if (mode == NowPlayingShuffleMode.OFF) inactiveColor else activeColor
        Crossfade(targetState = mode, label = "nowPlayingShuffleMode") { shown ->
            if (shown == NowPlayingShuffleMode.HEARTBEAT) {
                Icon(
                    painter = painterResource(CoreCommonR.drawable.ic_heartbeat_pulse_24),
                    contentDescription = stringResource(CoreCommonR.string.heartbeat_mode_content_description),
                    modifier = Modifier.size(iconSize),
                    tint = tint
                )
            } else {
                ShuffleIconWithHeartbeatBadge(
                    showBadge = heartbeatAvailable,
                    iconSize = iconSize,
                    tint = tint
                )
            }
        }
    }
}

/**
 * 网易云歌曲的随机图标右下角带一个心跳角标，没点过的人也能看出这里还能切到心动。
 * 角标底下先挖一个圆再画，背景是封面取色的渐变，没法用固定底色垫开。
 */
@Composable
private fun ShuffleIconWithHeartbeatBadge(showBadge: Boolean, iconSize: Dp, tint: Color) {
    val description = stringResource(
        if (showBadge) CoreCommonR.string.player_shuffle_with_heartbeat_description
        else CoreCommonR.string.player_shuffle
    )
    if (!showBadge) {
        Icon(Icons.Outlined.Shuffle, contentDescription = description, modifier = Modifier.size(iconSize), tint = tint)
        return
    }
    val badgeSize = iconSize * ShuffleHeartbeatBadgeScale
    Box(
        modifier = Modifier
            .size(iconSize)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        Icon(
            Icons.Outlined.Shuffle,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val badgePx = badgeSize.toPx()
                    drawCircle(
                        color = Color.Black,
                        radius = badgePx * ShuffleHeartbeatBadgeCutoutRatio,
                        center = Offset(size.width - badgePx / 2f, size.height - badgePx / 2f),
                        blendMode = BlendMode.Clear
                    )
                }
        )
        Icon(
            painter = painterResource(CoreCommonR.drawable.ic_heartbeat_pulse_24),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.align(Alignment.BottomEnd).size(badgeSize)
        )
    }
}

private const val ShuffleHeartbeatBadgeScale = 0.55f

/** 挖空半径比角标本身大一圈，留出和随机箭头之间的缝 */
private const val ShuffleHeartbeatBadgeCutoutRatio = 0.62f
