package moe.ouom.neriplayer.ui.screen.nowplaying.actions

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaListeningSection
 * Created: 2025/10/17
 */

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块二 听歌记录。
 *
 * 数据来自本机播放统计（`TrackStat`），**不是**网易云账号完整听歌历史；
 * 统计未初始化或没有该曲记录时显示「暂无听歌记录」，不编造任何数字。
 */
@Composable
internal fun SongEncyclopediaListeningSection(song: SongItem) {
    val songKey = remember(song) { song.stableKey() }
    val context = LocalContext.current
    val trackStat by produceState<TrackStat?>(initialValue = null, songKey) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                AppContainer.playbackStatsRepo.takeIf { it.awaitInitialized() }
                    ?.getStatForTrack(songKey)
            }.getOrNull()
        }
    }
    val stat = trackStat ?: return
    // 本机没有任何收听记录时整段隐藏，不显示「暂无」，也不用空态误导用户。
    if (stat.playCount <= 0 && stat.totalListenMs <= 0L) return
    SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_listen_history)

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val firstPlayedText = remember(stat.firstPlayedAt) {
        dateFormat.format(Date(stat.firstPlayedAt))
    }
    val totalListenText = remember(stat.totalListenMs) {
        val totalSeconds = stat.totalListenMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        when {
            hours > 0 -> context.getString(
                CoreCommonR.string.encyclopedia_total_listen_hours,
                hours,
                minutes
            )
            minutes > 0 -> context.getString(
                CoreCommonR.string.encyclopedia_total_listen_minutes,
                minutes
            )
            else -> context.getString(
                CoreCommonR.string.encyclopedia_total_listen_seconds,
                totalSeconds
            )
        }
    }
    SongEncyclopediaRow(
        CoreCommonR.string.encyclopedia_label_first_listened,
        firstPlayedText
    )
    SongEncyclopediaRow(
        CoreCommonR.string.encyclopedia_label_total_listen_time,
        totalListenText
    )
    SongEncyclopediaRow(
        CoreCommonR.string.encyclopedia_label_play_count_times,
        context.getString(CoreCommonR.string.encyclopedia_play_count_value, stat.playCount)
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(CoreCommonR.string.encyclopedia_history_source_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    )
}
