package moe.ouom.neriplayer.ui.screen.tab.library

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
 * File: moe.ouom.neriplayer.ui.screen.tab.library/LibraryScreenNeteaseContent
 * Created: 2025/8/8
 */

import moe.ouom.neriplayer.ui.component.common.NeriSearchFieldGlass
import moe.ouom.neriplayer.ui.theme.neriSearchFieldColors
import moe.ouom.neriplayer.ui.theme.neriSearchFieldShape
import androidx.compose.ui.graphics.Shape
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.R as AppR
@Composable
internal fun NeteaseLibraryList(
    playlists: List<PlaylistSummary>,
    albums: List<AlbumSummary>,
    playlistListState: LazyListState,
    albumListState: LazyListState,
    selectedCategory: Int,
    onCategoryChange: (Int) -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    offlineMode: Boolean
) {
    var playlistSearchQuery by rememberSaveable { mutableStateOf("") }
    var albumSearchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val isAlbumCategory = selectedCategory == NETEASE_CATEGORY_ALBUM
    val listState = if (isAlbumCategory) albumListState else playlistListState
    val searchQuery = if (isAlbumCategory) albumSearchQuery else playlistSearchQuery
    val filteredPlaylists = remember(playlists, playlistSearchQuery) {
        filterNeteasePlaylists(playlists, playlistSearchQuery)
    }
    val filteredAlbums = remember(albums, albumSearchQuery) {
        filterNeteaseAlbums(albums, albumSearchQuery)
    }

    fun updateSearchQuery(category: Int, value: String) {
        if (category == NETEASE_CATEGORY_ALBUM) {
            albumSearchQuery = value
        } else {
            playlistSearchQuery = value
        }
    }

    val cardShape = neriRoundedShape(12.dp)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 8.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "netease_library_header") {
            NeteaseLibraryHeaderContent(
                selectedCategory = selectedCategory,
                onCategoryChange = { category ->
                    if (selectedCategory != category) {
                        onCategoryChange(category)
                    }
                }
            )
            LibraryInlineSearchField(
                query = searchQuery,
                onQueryChange = { value ->
                    updateSearchQuery(selectedCategory, value)
                },
                placeholderResId = CoreCommonR.string.library_netease_search_hint
            )
        }

        if (isAlbumCategory) {
            if (albums.isNotEmpty() && filteredAlbums.isEmpty()) {
                item(key = "netease_album_search_empty") {
                    NeteaseLibraryEmptyCard(
                        cardShape = cardShape,
                        title = stringResource(CoreCommonR.string.library_netease_search_empty),
                        hint = stringResource(CoreCommonR.string.library_netease_search_empty_hint),
                        iconIsAlbum = true
                    )
                }
            } else if (filteredAlbums.isEmpty()) {
                item(key = "netease_album_empty") {
                    NeteaseLibraryEmptyCard(
                        cardShape = cardShape,
                        title = stringResource(CoreCommonR.string.library_netease_album_empty),
                        hint = stringResource(CoreCommonR.string.library_netease_search_hint),
                        iconIsAlbum = true
                    )
                }
            }
            items(
                items = filteredAlbums,
                key = { album -> "album:${album.id}" }
            ) { album ->
                NeteaseAlbumRow(
                    album = album,
                    cardShape = cardShape,
                    onClick = { onAlbumClick(album) },
                    offlineMode = offlineMode
                )
            }
        } else {
            if (playlists.isNotEmpty() && filteredPlaylists.isEmpty()) {
                item(key = "netease_playlist_search_empty") {
                    NeteaseLibraryEmptyCard(
                        cardShape = cardShape,
                        title = stringResource(CoreCommonR.string.library_netease_search_empty),
                        hint = stringResource(CoreCommonR.string.library_netease_search_empty_hint),
                        iconIsAlbum = false
                    )
                }
            } else if (filteredPlaylists.isEmpty()) {
                item(key = "netease_playlist_empty") {
                    NeteaseLibraryEmptyCard(
                        cardShape = cardShape,
                        title = stringResource(CoreCommonR.string.library_netease_playlist_empty),
                        hint = stringResource(CoreCommonR.string.library_netease_search_hint),
                        iconIsAlbum = false
                    )
                }
            }
            items(
                items = filteredPlaylists,
                key = { playlist -> "playlist:${playlist.id}" }
            ) { playlist ->
                NeteasePlaylistRow(
                    playlist = playlist,
                    cardShape = cardShape,
                    context = context,
                    onClick = { onPlaylistClick(playlist) },
                    offlineMode = offlineMode
                )
            }
        }
    }
}

@Composable
internal fun NeteaseLibraryHeaderContent(
    selectedCategory: Int,
    onCategoryChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        NeteaseCategoryTabs(
            selectedCategory = selectedCategory,
            onCategoryChange = onCategoryChange
        )
    }
}

@Composable
internal fun NeteaseCategoryTabs(
    selectedCategory: Int,
    onCategoryChange: (Int) -> Unit
) {
    LibrarySecondaryTabs(
        labels = listOf(
            stringResource(CoreCommonR.string.library_netease_tab_playlists),
            stringResource(CoreCommonR.string.library_netease_tab_albums)
        ),
        selectedIndex = selectedCategory,
        onSelected = onCategoryChange
    )
}

@Composable
internal fun NeteaseLibraryEmptyCard(
    cardShape: Shape,
    title: String,
    hint: String,
    iconIsAlbum: Boolean
) {
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(cardShape)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    text = hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Icon(
                    imageVector = if (iconIsAlbum) {
                        Icons.Filled.Album
                    } else {
                        Icons.AutoMirrored.Filled.QueueMusic
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
            }
        )
    }
}

@Composable
internal fun NeteasePlaylistRow(
    playlist: PlaylistSummary,
    cardShape: Shape,
    context: Context,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(cardShape)
            .clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = { Text(playlist.name) },
            supportingContent = {
                Text(
                    text = stringResource(
                        CoreCommonR.string.home_play_count_format,
                        formatPlayCount(context, playlist.playCount),
                        playlist.trackCount
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = playlist.picUrl,
                        sizePx = 192,
                        allowHardware = false,
                        offlineMode = offlineMode
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(neriRoundedShape(8.dp))
                )
            }
        )
    }
}

@Composable
internal fun NeteaseAlbumRow(
    album: AlbumSummary,
    cardShape: Shape,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(cardShape)
            .clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = { Text(album.name) },
            supportingContent = {
                Text(
                    text = pluralStringResource(
                        AppR.plurals.library_song_count,
                        album.size,
                        album.size
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = album.picUrl,
                        sizePx = 192,
                        allowHardware = false,
                        offlineMode = offlineMode
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(neriRoundedShape(8.dp))
                )
            }
        )
    }
}

@Composable
internal fun NeteaseAlbumList(
    playlists: List<AlbumSummary>,
    listState: LazyListState,
    selectedCategory: Int,
    onCategoryChange: (Int) -> Unit,
    onClick: (AlbumSummary) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredAlbums = remember(playlists, searchQuery) {
        filterNeteaseAlbums(playlists, searchQuery)
    }

    val cardShape = neriRoundedShape(12.dp)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "netease_library_header") {
            NeteaseLibraryHeaderContent(
                selectedCategory = selectedCategory,
                onCategoryChange = onCategoryChange
            )
        }
        item(key = "netease_album_search") {
            NeriSearchFieldGlass(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                shape = neriSearchFieldShape(LibrarySearchFieldShape)
            ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(CoreCommonR.string.library_netease_search_hint)) },
                singleLine = true,
                shape = neriSearchFieldShape(LibrarySearchFieldShape),

                colors = neriSearchFieldColors()
            )
            }
        }
        if (playlists.isNotEmpty() && filteredAlbums.isEmpty()) {
            item {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(CoreCommonR.string.library_netease_search_empty))
                        },
                        supportingContent = {
                            Text(
                                stringResource(CoreCommonR.string.library_netease_search_empty_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                }
            }
        } else if (filteredAlbums.isEmpty()) {
            item {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(CoreCommonR.string.library_netease_album_empty))
                        },
                        supportingContent = {
                            Text(
                                stringResource(CoreCommonR.string.library_netease_search_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                }
            }
        }
        items(
            items = filteredAlbums,
            key = { it.id }
        ) { pl ->
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text(
                            pluralStringResource(AppR.plurals.library_song_count, pl.size, pl.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    leadingContent = {
                        AsyncImage(
                            model = offlineCachedImageRequest(
                                context = context,
                                data = pl.picUrl,
                                sizePx = 192,
                                allowHardware = false,
                                offlineMode = offlineMode
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(neriRoundedShape(8.dp))
                        )
                    }
                )
            }
        }
    }
}
