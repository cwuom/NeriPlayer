package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

/** 播放页随机键的三档：网易云歌曲按 默认 → 随机 → 心动 → 默认 循环，其他音源只有前两档 */
internal enum class NowPlayingShuffleMode { OFF, SHUFFLE, HEARTBEAT }

/**
 * 心动模式只用网易云官方推荐：没登录网易云时与 B 站等音源一样，随机键只有两档、不带角标。
 * 登录态还在校验中（Checking）按已登录处理，免得冷启动时角标闪一下。
 */
internal fun isHeartbeatModeAvailable(song: SongItem?, neteaseAuth: SavedCookieAuthState): Boolean =
    neteaseAuth != SavedCookieAuthState.Missing && isNeteaseExclusiveFeatureAvailable(song)

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

internal sealed interface ShuffleModeStep {
    data class Shuffle(val enabled: Boolean) : ShuffleModeStep
    data class Heartbeat(val enabled: Boolean) : ShuffleModeStep
}

/**
 * 切到目标档位要按什么顺序改两个开关。
 *
 * 要关心动就最先关：关心动会删掉插进来的心动歌，若先开随机，随机记下的「还原队列」
 * 里还带着心动歌，之后关随机时长度对不上，原队列就还原不回来了。
 * 要开心动就最后开：一起听房间会拒绝开启，这时停在默认档，用户还能继续点回随机。
 */
internal fun planShuffleModeSteps(
    target: NowPlayingShuffleMode,
    shuffleEnabled: Boolean,
    heartbeatEnabled: Boolean
): List<ShuffleModeStep> {
    val wantShuffle = target == NowPlayingShuffleMode.SHUFFLE
    val wantHeartbeat = target == NowPlayingShuffleMode.HEARTBEAT
    return buildList {
        if (heartbeatEnabled && !wantHeartbeat) add(ShuffleModeStep.Heartbeat(false))
        if (shuffleEnabled != wantShuffle) add(ShuffleModeStep.Shuffle(wantShuffle))
        if (!heartbeatEnabled && wantHeartbeat) add(ShuffleModeStep.Heartbeat(true))
    }
}

/** 按目标档位设置随机和心动两个开关，保证按钮看到的就是实际生效的。 */
internal fun NowPlayingPlaybackSource.applyShuffleMode(
    target: NowPlayingShuffleMode,
    shuffleEnabled: Boolean,
    heartbeatEnabled: Boolean
) {
    planShuffleModeSteps(target, shuffleEnabled, heartbeatEnabled).forEach { step ->
        when (step) {
            is ShuffleModeStep.Shuffle -> setShuffle(step.enabled)
            is ShuffleModeStep.Heartbeat -> setHeartbeatEnabled(step.enabled)
        }
    }
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
    inactiveColor: Color
) {
    val mode = resolveNowPlayingShuffleMode(shuffleEnabled, heartbeatEnabled, heartbeatAvailable)
    HapticIconButton(
        onClick = {
            val target = nextNowPlayingShuffleMode(mode, heartbeatAvailable)
            playbackSource.applyShuffleMode(target, shuffleEnabled, heartbeatEnabled)
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
                // 只有默认档画角标，提示这里能切到随机和心动；选中后显示纯粹的当前模式
                ShuffleIconWithHeartbeatBadge(
                    showBadge = heartbeatAvailable && shown == NowPlayingShuffleMode.OFF,
                    iconSize = iconSize,
                    tint = tint
                )
            }
        }
    }
}

/**
 * 默认档的混合图标：随机图标右下角带心跳角标。
 * 角标底下先挖一个圆再画，背景是封面取色的渐变，没法用固定底色垫开。
 */
@Composable
private fun ShuffleIconWithHeartbeatBadge(showBadge: Boolean, iconSize: Dp, tint: Color) {
    // 角标只是视觉提示，无障碍标签保持「随机播放」：读屏和界面测试都按这个名字找按钮
    val description = stringResource(CoreCommonR.string.player_shuffle)
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
