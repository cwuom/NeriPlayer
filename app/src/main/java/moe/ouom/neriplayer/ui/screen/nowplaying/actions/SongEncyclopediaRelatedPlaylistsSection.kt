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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaRelatedPlaylistsSection
 * Created: 2025/10/17
 */
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.util.format.formatPlayCount
import androidx.compose.ui.Alignment
import moe.ouom.neriplayer.R as AppR
private const val RELATED_PLAYLIST_EXPAND_THRESHOLD = 3
private val ENCYCLOPEDIA_PLAYLIST_COVER_SIZE = 48.dp
/**
 * 模块八 相关歌单。
 *
 * 播放量只在数据源给出时展示；歌曲数接口不提供，**不显示该字段**。
 */
@Composable
internal fun SongEncyclopediaRelatedPlaylistsSection(
    playlists: List<EncyclopediaRelatedPlaylist>,
    onOpenPlaylist: (EncyclopediaRelatedPlaylist) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) playlists else playlists.take(RELATED_PLAYLIST_EXPAND_THRESHOLD)
    SongEncyclopediaSectionCard(
        titleRes = AppR.string.encyclopedia_section_related_playlists,
        icon = Icons.AutoMirrored.Outlined.QueueMusic
    ) {
        if (playlists.isEmpty()) {
            SongEncyclopediaEmptyHint(AppR.string.encyclopedia_empty)
        } else {
            visible.forEach { playlist ->
                SongEncyclopediaRelatedPlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenPlaylist(playlist) }
                )
            }
            if (playlists.size > RELATED_PLAYLIST_EXPAND_THRESHOLD) {
                SongEncyclopediaActionSpacer()
                SongEncyclopediaExpandAction(
                    expanded = expanded,
                    onToggle = { expanded = !expanded }
                )
            }
        }
    }
}
@Composable
private fun SongEncyclopediaRelatedPlaylistRow(
    playlist: EncyclopediaRelatedPlaylist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .encyclopediaClickable(onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongEncyclopediaPlaylistCover(
            coverUrl = playlist.coverUrl,
            size = ENCYCLOPEDIA_PLAYLIST_COVER_SIZE
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            playlist.playCount?.let { playCount ->
                Text(
                    text = stringResource(
                        AppR.string.encyclopedia_playlist_play_count,
                        formatPlayCount(LocalContext.current, playCount)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}