package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

internal val CreatorHeroHeight = 300.dp

/**
 * 创作者详情页头图: 背景图铺满圆角卡片, 名字和统计压在底部暗角上,
 * 左侧可放头像, 右侧可放主操作; 网易云歌手和 B 站 UP 主共用
 */
@Composable
internal fun CreatorHeroBanner(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    summaryItems: List<String> = emptyList(),
    height: Dp = CreatorHeroHeight,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    backdrop: @Composable BoxScope.() -> Unit
) {
    val shape = if (NeriDesign.isClean) NeriDesign.tokens.groupShape else neriRoundedShape(32.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 大字号时名字和统计变高, 头图跟着长高而不是把底部统计裁掉
            .heightIn(min = height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.matchParentSize()) {
            backdrop()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.66f)
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, top = 56.dp, end = 16.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (summaryItems.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    CreatorHeroSummary(summaryItems)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun CreatorHeroSummary(items: List<String>) {
    val style = MaterialTheme.typography.labelLarge
    val color = Color.White.copy(alpha = 0.78f)
    // 窄侧栏里三项统计放不下一行时换行, 不截断数字
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            if (index > 0) Text("·", style = style, color = color)
            Text(item, style = style, color = color, maxLines = 1)
        }
    }
}

/** 简介默认收成几行, 被截断时点一下展开全文, 再点收起 */
@Composable
internal fun CreatorExpandableText(
    text: String,
    collapsedMaxLines: Int,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    val toggleable = overflowing || expanded
    val actionLabel = stringResource(
        if (expanded) CoreCommonR.string.action_collapse else CoreCommonR.string.action_expand
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 点按区域向外扩一圈再裁圆角, 圆角和尺寸动画的裁剪都落在留白里, 不切到首尾行的字
            .outsetBy(CreatorTextTouchOutsetX, CreatorTextTouchOutsetY)
            .clip(neriRoundedShape(12.dp))
            .then(
                if (toggleable) {
                    Modifier.clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = !expanded }
                } else {
                    Modifier
                }
            )
            .animateContentSize(tween(durationMillis = 260, easing = FastOutSlowInEasing))
            .padding(horizontal = CreatorTextTouchOutsetX, vertical = CreatorTextTouchOutsetY)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) overflowing = layout.hasVisualOverflow }
        )
        if (toggleable) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (NeriDesign.isClean) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val CreatorTextTouchOutsetX = 8.dp
private val CreatorTextTouchOutsetY = 6.dp

/** 让内容向四周多占 [x], [y] 绘制, 但对外报告的尺寸和位置不变, 不推动相邻布局 */
private fun Modifier.outsetBy(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val dx = x.roundToPx()
    val dy = y.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = dx * 2, vertical = dy * 2))
    val width = (placeable.width - dx * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (placeable.height - dy * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.place(-dx, -dy) }
}
