package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import androidx.compose.ui.Alignment
import moe.ouom.neriplayer.R as AppR

/**
 * 百科各模块底部统一的操作按钮。
 *
 * 所有模块（歌曲详情 / 艺人 / 评论 / 相似歌曲 / 相关歌单）的「展开」「收起」
 * 「查看全部评论」「播放全部」「重试」都走这一套，保证尺寸、颜色、图标、触觉一致。
 *
 * @param icon 前置小图标；传 null 时不渲染图标（单按钮时会让按钮看起来更干净）
 * @param enabled 为 false 时按钮置灰但仍保持布局稳定
 */
@Composable
internal fun SongEncyclopediaSectionAction(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    HapticTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE)
            )
            Spacer(Modifier.width(ICON_SPACING))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/** 模块底部「展开全部 / 收起」入口，图标随状态切换。 */
@Composable
internal fun SongEncyclopediaExpandAction(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    SongEncyclopediaSectionAction(
        icon = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
        text = stringResource(
            if (expanded) AppR.string.encyclopedia_action_collapse
            else AppR.string.encyclopedia_action_expand
        ),
        onClick = onToggle,
        modifier = modifier
    )
}

/** 模块底部「重试」入口，与展开/查看全部按钮同一套样式。 */
@Composable
internal fun SongEncyclopediaRetryAction(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SongEncyclopediaSectionAction(
        icon = Icons.Outlined.Refresh,
        text = stringResource(AppR.string.encyclopedia_retry),
        onClick = onRetry,
        modifier = modifier
    )
}

/** 模块底部多按钮的排列容器：统一左对齐、统一间距。 */
@Composable
internal fun SongEncyclopediaSectionActionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** 模块底部按钮与上方内容之间的统一间距。 */
@Composable
internal fun SongEncyclopediaActionSpacer() {
    Spacer(Modifier.height(ACTION_TOP_SPACING))
}

private val ACTION_ROW_SPACING = 8.dp
private val ACTION_TOP_SPACING = 4.dp
private val ICON_SIZE = 18.dp
private val ICON_SPACING = 6.dp
