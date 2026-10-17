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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaSimilarSongsSection
 * Created: 2025/10/17
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong

private const val SIMILAR_SONG_EXPAND_THRESHOLD = 5

/**
 * 模块七 喜欢这首歌的人也爱听。
 *
 * 数据全部来自网易云的相似歌曲板块，**不是**随机推荐。
 * 点击单首仅尝试用现有播放能力播放；无法播放时不静默跳过，显示提示。
 */
@Composable
internal fun SongEncyclopediaSimilarSongsSection(
    similarSongs: List<EncyclopediaSimilarSong>,
    onPlaySong: (EncyclopediaSimilarSong) -> Unit,
    onPlayAll: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) similarSongs else similarSongs.take(SIMILAR_SONG_EXPAND_THRESHOLD)
    SongEncyclopediaSectionCard(
        titleRes = CoreCommonR.string.encyclopedia_section_similar_songs,
        icon = Icons.Outlined.MusicNote
    ) {
        if (similarSongs.isEmpty()) {
            SongEncyclopediaEmptyHint(CoreCommonR.string.encyclopedia_empty)
        } else {
            visible.forEachIndexed { index, song ->
                SongEncyclopediaSimilarSongRow(
                    index = index + 1,
                    song = song,
                    onClick = { onPlaySong(song) }
                )
            }
            if (similarSongs.size > SIMILAR_SONG_EXPAND_THRESHOLD) {
                SongEncyclopediaExpandAction(expanded = expanded, onToggle = { expanded = !expanded })
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.encyclopedia_action_play_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .encyclopediaClickable(onPlayAll)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SongEncyclopediaSimilarSongRow(
    index: Int,
    song: EncyclopediaSimilarSong,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .encyclopediaClickable(onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = context.getString(CoreCommonR.string.encyclopedia_similar_index, index),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        SongEncyclopediaSongCover(song.coverUrl)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SongEncyclopediaSongCover(coverUrl: String?) {
    val size = 40.dp
    val shape = RoundedCornerShape(6.dp)
    if (coverUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        return
    }
    AsyncImage(
        model = coverUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}

/** 供模块八复用的封面占位逻辑（同尺寸）。 */
@Composable
internal fun SongEncyclopediaPlaylistCover(coverUrl: String?, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(8.dp)
    if (coverUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        return
    }
    AsyncImage(
        model = coverUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}
