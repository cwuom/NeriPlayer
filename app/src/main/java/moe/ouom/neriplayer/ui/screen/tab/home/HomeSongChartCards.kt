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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.settings.home.HomeSongCardColumns

/** 榜单卡片的封面宽度上限。 */
private const val HomeSongChartCardMaxWidthDp = 140f

/** 卡片之间的间距。 */
private const val HomeSongChartCardSpacingDp = 12f

/** 卡片条左右内边距。 */
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
 */
@Composable
internal fun HomeSongChartCardStrip(
    cards: List<HomeSongChartCardData>,
    columns: Int,
    onCardClick: (HomeSongChartCardData) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return
    val safeColumns = HomeSongCardColumns.normalize(columns)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spacing = HomeSongChartCardSpacingDp.dp
        // 卡片宽度按「每页几张」平分可用宽度，扣掉的是卡片条自己的内边距与间距。
        val availableWidth = maxWidth.value -
            HomeSongChartCardHorizontalPaddingDp * 2f -
            HomeSongChartCardSpacingDp * (safeColumns - 1)
        val cardWidth = (availableWidth / safeColumns)
            .coerceIn(0f, HomeSongChartCardMaxWidthDp)
            .dp
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = HomeSongChartCardHorizontalPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            items(
                items = cards,
                key = { card -> card.id }
            ) { card ->
                HomeSongChartCard(
                    card = card,
                    width = cardWidth,
                    onClick = { onCardClick(card) }
                )
            }
        }
    }
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
