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
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeYouTubeMusicBlocks
 * Created: 2026/11/20
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId

internal fun LazyGridScope.addInternationalHomeShelf(
    shelf: InternationalHomeShelf,
    registerKey: (String) -> String,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onYouTubeCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    favoriteKeys: Set<String>,
    favoriteSongLookup: FavoriteSongLookup,
    downloadPresenceVersion: Int,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val trackShelf = shelf.entries.size == shelf.songs.size
    item(
        key = registerKey(shelf.key),
        span = { GridItemSpan(maxLineSpan) },
        contentType = if (trackShelf) "youtube:tracks" else "youtube:artwork"
    ) {
        val clean = NeriDesign.isClean
        LaunchedEffect(shelf.songs, offlineMode) {
            if (!offlineMode && shelf.songs.isNotEmpty()) {
                PlayerManager.prefetchYouTubePlayableUrlWindow(shelf.songs, 0, "yt_home_shelf_visible")
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shelf.title.isNotBlank()) {
                Text(
                    text = shelf.title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (clean) FontWeight.Bold else FontWeight.SemiBold
                )
            }
            if (trackShelf) {
                ResponsiveSongPagerList(
                    songs = shelf.songs,
                    onSongClick = onSongClick,
                    favoriteSongLookup = favoriteSongLookup,
                    downloadPresenceVersion = downloadPresenceVersion,
                    onFavoriteToggle = onFavoriteToggle,
                    onShowSnackbar = onShowSnackbar,
                    offlineMode = offlineMode,
                    maxVisibleSongs = shelf.songs.size
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(if (clean) 14.dp else 12.dp)) {
                    itemsIndexed(
                        items = shelf.entries,
                        key = { index, entry -> "$index:${entry.item.browseId}:${entry.item.videoId}" },
                        contentType = { _, entry -> entry.kind }
                    ) { _, entry ->
                        val isFavorite = when (entry) {
                            is InternationalHomeEntry.Collection -> "youtubeMusic:${entry.playlist.favoriteId()}" in favoriteKeys
                            is InternationalHomeEntry.Track -> favoriteSongLookup.contains(entry.song)
                            is InternationalHomeEntry.Artist -> false
                        }
                        InternationalHomeArtworkCard(
                            entry = entry,
                            modifier = Modifier.width(if (clean) 150.dp else 142.dp),
                            isFavorite = isFavorite,
                            onClick = {
                                when (entry) {
                                    is InternationalHomeEntry.Track -> onSongClick(shelf.songs, entry.queueIndex)
                                    is InternationalHomeEntry.Collection -> onYouTubeMusicPlaylistClick(entry.playlist)
                                    is InternationalHomeEntry.Artist -> onYouTubeCreatorClick(entry.creator)
                                }
                            },
                            downloadPresenceVersion = downloadPresenceVersion,
                            onFavoriteToggle = onFavoriteToggle,
                            onShowSnackbar = onShowSnackbar,
                            offlineMode = offlineMode
                        )
                    }
                }
            }
        }
    }
}
