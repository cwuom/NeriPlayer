package moe.ouom.neriplayer.ui.screen.tab.home

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeSongChartCards
 * Created: 2026/11/20
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 榜单卡片的封面宽度上限。
 *
 * 该上限只用于避免平板等超宽容器下单张卡片过大；
 * 每行的卡片数量始终由用户设置的「并排数量」决定。
 */
private const val HomeSongChartCardMaxWidthDp = 200f

/**
 * 卡片之间的间距。
 */
private const val HomeSongChartCardSpacingDp = 12f

/**
 * 卡片条左右内边距。
 */
private const val HomeSongChartCardHorizontalPaddingDp = 8f

/**
 * 榜单卡片的数据。
 */
internal data class HomeSongChartCardData(
    /** 小模块稳定 id，同时用作列表 key。 */
    val id: String,
    /** 卡片标题的资源 id。 */
    val titleRes: Int
)

/**
 * 歌曲类小模块的「榜单卡片」模式。
 *
 * 卡片按用户设置的「并排数量」分行排列，每行固定 N 张，
 * 不再使用横向滚动列表，避免 1 列设置下因为固定宽度上限而露出多张卡片。
 */
@Composable
internal fun HomeSongChartCardStrip(
    cards: List<HomeSongChartCardData>,
    columns: Int,
    onCardClick: (HomeSongChartCardData) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return
    val safeColumns = columns.coerceIn(1, 3)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spacing = HomeSongChartCardSpacingDp.dp
        val horizontalPadding = HomeSongChartCardHorizontalPaddingDp.dp
        val cardWidth = resolveHomeSongChartCardWidthDp(
            containerWidthDp = maxWidth.value,
            columns = safeColumns
        ).dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            cards.chunked(safeColumns).forEach { rowCards ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    rowCards.forEach { card ->
                        HomeSongChartCard(
                            card = card,
                            width = cardWidth,
                            onClick = { onCardClick(card) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 计算单张榜单卡片的宽度。
 *
 * 宽度由「容器宽度 / 并排数量」直接决定，保证每行恰好放下指定数量的卡片；
 * 超宽容器下会额外收窄卡片，避免单张卡片铺满整行。
 */
internal fun resolveHomeSongChartCardWidthDp(
    containerWidthDp: Float,
    columns: Int
): Float {
    val slots = columns.coerceAtLeast(1)
    val availableWidth = (containerWidthDp -
        HomeSongChartCardHorizontalPaddingDp * 2f -
        HomeSongChartCardSpacingDp * (slots - 1))
        .coerceAtLeast(0f)
    val width = availableWidth / slots
    return width.coerceAtMost(HomeSongChartCardMaxWidthDp)
}

/**
 * 单张榜单卡片。
 */
@Composable
private fun HomeSongChartCard(
    card: HomeSongChartCardData,
    width: Dp,
    onClick: () -> Unit
) {
    HomeSongChartCoverTile(
        sectionId = card.id,
        title = stringResource(card.titleRes),
        width = width,
        modifier = Modifier.clickable(onClick = onClick)
    )
}
