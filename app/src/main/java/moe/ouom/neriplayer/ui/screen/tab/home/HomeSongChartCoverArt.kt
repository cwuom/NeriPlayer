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
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeSongChartCoverArt
 * Created: 2026/11/20
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/**
 * 首页歌曲小模块卡片的封面配色。
 */
internal object HomeSongChartPalette {

    /** 飙升榜。 */
    val topSoaring = Color(0xFFA78BFA)

    /** 私人雷达。 */
    val personalRadar = Color(0xFF7C8CF8)

    /** 热歌榜。 */
    val topHot = Color(0xFFF0A44B)

    /** 新歌榜。 */
    val topNew = Color(0xFF5CC79A)

    /** 每日推荐。 */
    val dailyRecommend = Color(0xFFE05B5B)

    /** 私人 FM。 */
    val privateFm = Color(0xFFEC7A9B)

    /** 推荐新歌。 */
    val personalizedNewSongs = Color(0xFF5B9DEF)

    /** 小模块 id 到主色的映射。 */
    val bySectionId: Map<String, Color> = mapOf(
        HomeTrendingSubSectionIds.TOP_SOARING to topSoaring,
        HomeRadarSubSectionIds.PERSONAL_RADAR to personalRadar,
        HomeTrendingSubSectionIds.TOP_HOT to topHot,
        HomeTrendingSubSectionIds.TOP_NEW to topNew,
        HomeRadarSubSectionIds.DAILY_RECOMMEND to dailyRecommend,
        HomeRadarSubSectionIds.PRIVATE_FM to privateFm,
        HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS to personalizedNewSongs
    )

    /** 小模块 id 到渐变底色的映射。 */
    val bySectionIdDeep: Map<String, Color> = mapOf(
        HomeTrendingSubSectionIds.TOP_SOARING to Color(0xFF6A50C4),
        HomeRadarSubSectionIds.PERSONAL_RADAR to Color(0xFF4655B4),
        HomeTrendingSubSectionIds.TOP_HOT to Color(0xFFB06D1F),
        HomeTrendingSubSectionIds.TOP_NEW to Color(0xFF26855F),
        HomeRadarSubSectionIds.DAILY_RECOMMEND to Color(0xFF9B3131),
        HomeRadarSubSectionIds.PRIVATE_FM to Color(0xFFAE4463),
        HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS to Color(0xFF2A69AF)
    )

    /** 取小模块主色，未知 id 返回 null。 */
    fun colorOf(sectionId: String): Color? = bySectionId[sectionId]

    /** 取小模块渐变底端色，未知 id 返回 null。 */
    fun deepColorOf(sectionId: String): Color? = bySectionIdDeep[sectionId]
}

/** 标题字号相对封面宽度的比例。 */
private const val HomeSongChartTitleScale = 0.125f

/**
 * 卡片封面：方形渐变底 + 波浪底纹 + 居中标题。
 */
@Composable
internal fun HomeSongChartCoverArt(
    sectionId: String,
    title: String,
    modifier: Modifier = Modifier
) {
    val accent = HomeSongChartPalette.colorOf(sectionId) ?: MaterialTheme.colorScheme.primary
    val deep = HomeSongChartPalette.deepColorOf(sectionId)
        ?: accent.shadeForChartGradient(0.62f)
    BoxWithConstraints(modifier = modifier) {
        val titleSize = (maxWidth.value * HomeSongChartTitleScale).coerceIn(11f, 26f).sp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(neriRoundedShape(8.dp))
                .background(
                    Brush.linearGradient(colors = listOf(accent, deep))
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawChartWavePattern(color = Color.White)
            }
            Text(
                text = title,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * 波浪底纹：两层起伏曲线。
 */
private fun DrawScope.drawChartWavePattern(color: Color) {
    val w = size.width
    val h = size.height

    // 上层大波浪
    val top = Path().apply {
        moveTo(0f, h * 0.72f)
        cubicTo(w * 0.18f, h * 0.58f, w * 0.32f, h * 0.84f, w * 0.50f, h * 0.72f)
        cubicTo(w * 0.68f, h * 0.60f, w * 0.82f, h * 0.80f, w, h * 0.70f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        path = top,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
            startY = h * 0.58f,
            endY = h
        )
    )

    // 下层小波浪
    val bottom = Path().apply {
        moveTo(0f, h * 0.84f)
        cubicTo(w * 0.20f, h * 0.74f, w * 0.34f, h * 0.94f, w * 0.54f, h * 0.83f)
        cubicTo(w * 0.74f, h * 0.72f, w * 0.86f, h * 0.90f, w, h * 0.82f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        path = bottom,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0.04f)),
            startY = h * 0.72f,
            endY = h
        )
    )
}

/**
 * 方形封面 + 居中标题。
 */
@Composable
internal fun HomeSongChartCoverTile(
    sectionId: String,
    title: String,
    width: Dp,
    modifier: Modifier = Modifier
) {
    HomeSongChartCoverArt(
        sectionId = sectionId,
        title = title,
        modifier = modifier.width(width).aspectRatio(1f)
    )
}

/**
 * 按比例压暗颜色。
 */
internal fun Color.shadeForChartGradient(factor: Float = 0.55f): Color =
    Color(
        red = red * factor,
        green = green * factor,
        blue = blue * factor,
        alpha = alpha
    )
