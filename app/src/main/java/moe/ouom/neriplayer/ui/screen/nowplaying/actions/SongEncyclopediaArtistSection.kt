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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaArtistSection
 * Created: 2025/10/17
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo

private const val ENCYCLOPEDIA_ARTIST_BRIEF_MAX_LINES = 4
private const val ENCYCLOPEDIA_ARTIST_AVATAR_SIZE_DP = 48

/**
 * 模块五 关于艺人。
 *
 * 粉丝数无匿名数据源，本页**不展示**；
 * 关注按钮仅在网易云登录后出现（见 [SongEncyclopediaArtistFollowButton]）。
 */
@Composable
internal fun SongEncyclopediaArtistSection(
    artist: EncyclopediaArtistInfo,
    onOpenArtist: () -> Unit
) {
    SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_artist)
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .encyclopediaClickable(onOpenArtist)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongEncyclopediaArtistAvatar(artist)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = remember(artist.albumSize, artist.musicSize) {
                buildEncyclopediaArtistMeta(context, artist)
            }
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    SongEncyclopediaArtistFollowButton(artist)
    SongEncyclopediaArtistBrief(artist)
}

@Composable
private fun SongEncyclopediaArtistAvatar(artist: EncyclopediaArtistInfo) {
    val size = ENCYCLOPEDIA_ARTIST_AVATAR_SIZE_DP.dp
    val url = artist.picUrl
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}

@Composable
private fun SongEncyclopediaArtistBrief(artist: EncyclopediaArtistInfo) {
    val brief = artist.briefDesc?.takeIf { it.isNotBlank() }
    if (brief == null) {
        SongEncyclopediaEmptyHint(CoreCommonR.string.encyclopedia_empty)
        return
    }
    var expanded by rememberSaveable(artist.artistId) { mutableStateOf(false) }
    Text(
        text = brief,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else ENCYCLOPEDIA_ARTIST_BRIEF_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
    SongEncyclopediaExpandAction(expanded = expanded, onToggle = { expanded = !expanded })
}

private fun buildEncyclopediaArtistMeta(
    context: android.content.Context,
    artist: EncyclopediaArtistInfo
): String? {
    val parts = buildList {
        artist.albumSize?.let { albums -> artist.musicSize?.let { songs ->
            add(context.getString(CoreCommonR.string.encyclopedia_artist_bio_note, albums, songs))
        } }
    }
    return parts.firstOrNull()
}
