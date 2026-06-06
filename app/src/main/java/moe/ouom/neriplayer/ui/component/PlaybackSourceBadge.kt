package moe.ouom.neriplayer.ui.component

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
 * File: moe.ouom.neriplayer.ui.component/PlaybackSourceBadge
 * Updated: 2026/3/23
 */


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R

enum class PlaybackSourceType {
    NETEASE,
    BILIBILI,
    YOUTUBE_MUSIC,
    LOCAL
}

@Composable
fun PlaybackSourceBadge(
    source: PlaybackSourceType,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (source) {
            PlaybackSourceType.NETEASE -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                    contentDescription = stringResource(R.string.cd_netease),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.nowplaying_netease_cloud),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            PlaybackSourceType.BILIBILI -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_bilibili),
                    contentDescription = stringResource(R.string.cd_bilibili),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.nowplaying_bilibili),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            PlaybackSourceType.YOUTUBE_MUSIC -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_youtube),
                    contentDescription = stringResource(R.string.common_youtube),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.nowplaying_youtube_music),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            PlaybackSourceType.LOCAL -> {
                Icon(
                    imageVector = Icons.Outlined.LibraryMusic,
                    contentDescription = stringResource(R.string.local_files),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.local_files),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
