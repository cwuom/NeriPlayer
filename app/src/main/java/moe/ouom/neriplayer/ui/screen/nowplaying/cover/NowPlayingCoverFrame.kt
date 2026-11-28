package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.cleanPressHighlight
import moe.ouom.neriplayer.ui.theme.cleanPressLayerScale
import moe.ouom.neriplayer.ui.theme.rememberCleanPressProgress

internal val NowPlayingCoverCornerRadius = 24.dp
private val NowPlayingCoverShadowElevation = 20.dp

/**
 * 大封面的投影、圆角裁剪和点按入口; [onTap] 为空时封面不可点
 *
 * Clean 的按压下沉要连同投影和裁剪整层缩放: 交给 LocalIndication 时它只缩裁剪内的内容,
 * 内收的直边和满尺寸圆角相交, 四个角会被削成折线
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun NowPlayingCoverFrame(
    surfaceColor: Color,
    onTap: (() -> Unit)?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val cleanPress = if (NeriDesign.isClean) rememberCleanPressProgress(interactionSource) else null
    // 解析圆角让裁剪和投影轮廓都走 GPU 快路径; 路径轮廓在按压回弹和缩放变换下会丢角
    val coverShape = RoundedCornerShape(NowPlayingCoverCornerRadius)
    val highlightColor = MaterialTheme.colorScheme.onSurface
    val indication = if (cleanPress == null) LocalIndication.current else null
    Box(
        modifier = modifier
            .then(cleanPress?.let { Modifier.cleanPressLayerScale(it) } ?: Modifier)
            // 单张大封面的投影走硬件渲染, 给画面一点纵深
            .shadow(
                elevation = NowPlayingCoverShadowElevation,
                shape = coverShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.32f),
                spotColor = Color.Black.copy(alpha = 0.48f)
            )
            .clip(coverShape)
            .background(surfaceColor)
            .then(cleanPress?.let { Modifier.cleanPressHighlight(it, highlightColor) } ?: Modifier)
            .then(
                if (onTap != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onClick = onTap,
                        onLongClick = onLongPress
                    )
                } else Modifier
            ),
        content = content
    )
}
