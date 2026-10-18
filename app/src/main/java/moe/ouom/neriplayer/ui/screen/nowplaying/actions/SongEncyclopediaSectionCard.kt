package moe.ouom.neriplayer.ui.screen.nowplaying.actions

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaSectionCard
 * Created: 2025/10/17
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/** 卡片之间的标准间距，保证各模块在视觉上彼此分离。 */
internal val SongEncyclopediaSectionSpacing: Dp
    @Composable @ReadOnlyComposable
    get() = if (NeriDesign.isClean) NeriDesign.tokens.sectionSpacing else 12.dp

/**
 * 百科模块独立容器。
 *
 * 每个模块都带自己的标题栏（图标 + 标题 + 分隔线），模块之间由
 * [SongEncyclopediaSectionSpacing] 拉开间距，避免多块内容在视觉上连成一片。
 */
@Composable
internal fun SongEncyclopediaSectionCard(
    titleRes: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val clean = NeriDesign.isClean
    Surface(
        modifier = modifier,
        // 原 Card 的默认 shape 就是 MaterialTheme.shapes.medium：Material 主题下
        // NeriShapes 未覆盖 medium，取 M3 默认 12dp 圆角，这里原样沿用；Clean 下
        // 16dp 经 cleanCornerRadiusFor 放大到 20dp 连续圆角，与 tokens.cardShape 一致。
        shape = if (clean) neriRoundedShape(16.dp) else MaterialTheme.shapes.medium,
        // Clean 的 surfaceContainerLow 是纯白，贴在 #F4F4F7 的宿主面板上几乎看不见，
        // 且 surfaceTint 被置空后 elevation 染色失效，改用墨色半透明兜底。
        color = if (clean) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        // 原 Card 的 contentColor 是 contentColorFor(containerColor)：Material 下
        // surfaceContainerLow → onSurface。Clean 的墨色半透明不在色板内，不显式指定
        // 会退化为继承外层 LocalContentColor，这里固定回 onSurface 保持一致。
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 原 Card 的 tonalElevation 是 0dp，shadowElevation 取 CardDefaults.cardElevation()
        // 的 defaultElevation = FilledCardTokens.ContainerElevation = ElevationTokens.Level0
        // = 0dp —— 两套主题原本都没有阴影，这里保持 0dp 才是真正的零回归。
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        SongEncyclopediaSectionCardBody(titleRes, icon, content)
    }
}

@Composable
private fun SongEncyclopediaSectionCardBody(
    titleRes: Int,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val clean = NeriDesign.isClean
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // Clean 只用字重表达层级，主色留给可点击动作
                tint = if (clean) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(titleRes),
                // 原代码直接用 titleSmall（M3 默认字重是 Medium 500），Material 下必须
                // 原样沿用，不能覆盖成 Normal(400)；Clean 才用 SemiBold 加重层级。
                style = if (clean) {
                    MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.titleSmall
                },
                color = if (clean) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            // Clean 的发丝线是墨色 6%，outlineVariant 在 Clean 下偏重
            color = if (clean) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = NeriDesign.tokens.hairlineAlpha)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            content = content
        )
    }
}
