package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.ui.component.common.CleanSearchField
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.server.artist.ServerArtistArtwork
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun ServerBrowserHeader(
    title: String,
    refreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onDownloads: () -> Unit,
    onBack: (() -> Unit)?
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) HapticIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(CoreCommonR.string.action_back))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        ServerBrowserActions(refreshing, canRefresh, onRefresh, onDownloads)
    }
}

@Composable
internal fun ServerBrowserActions(
    refreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onDownloads: () -> Unit
) {
    HapticIconButton(onClick = onDownloads) {
        Icon(Icons.Outlined.Download, stringResource(CoreCommonR.string.download_manager))
    }
    HapticIconButton(onClick = onRefresh, enabled = canRefresh && !refreshing) {
        Icon(Icons.Outlined.Refresh, stringResource(CoreCommonR.string.server_refresh))
    }
}

@Composable
internal fun ServerProfileSelector(
    profiles: List<SubsonicProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    if (profiles.isEmpty()) return
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(
            labels = profiles.map { it.label },
            selectedIndex = profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
            onSelected = { onSelect(profiles[it].id) },
            maxFixedItems = 0
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { profile ->
                FilterChip(selected = selectedId == profile.id, onClick = { onSelect(profile.id) },
                    label = { Text(profile.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = NeriDesign.tokens.chipShape)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerLibraryTabs(category: ServerLibraryCategory, onSelect: (ServerLibraryCategory) -> Unit) {
    val labels = ServerLibraryCategory.entries.map { entry ->
        stringResource(when (entry) {
            ServerLibraryCategory.ALBUMS -> CoreCommonR.string.server_tab_albums
            ServerLibraryCategory.SONGS -> CoreCommonR.string.server_tab_songs
            ServerLibraryCategory.ARTISTS -> CoreCommonR.string.server_tab_artists
        })
    }
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(labels, category.ordinal, { onSelect(ServerLibraryCategory.entries[it]) },
            modifier = Modifier.padding(bottom = 12.dp))
    } else {
        PrimaryTabRow(selectedTabIndex = category.ordinal, containerColor = Color.Transparent,
            modifier = Modifier.padding(bottom = 12.dp)) {
            labels.forEachIndexed { index, label ->
                Tab(selected = index == category.ordinal, onClick = { onSelect(ServerLibraryCategory.entries[index]) },
                    text = { Text(label) })
            }
        }
    }
}

@Composable
internal fun ServerSearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    if (NeriDesign.isClean) {
        CleanSearchField(query, { if (it.isEmpty()) onClear() else onQueryChange(it) }, hint,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), onSearch = onSearch)
    } else {
        OutlinedTextField(value = query, onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true, shape = NeriDesign.tokens.cardShape,
            label = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) HapticIconButton(onClick = onClear) {
                        Icon(Icons.Outlined.Clear, stringResource(CoreCommonR.string.settings_search_clear_query))
                    }
                    HapticIconButton(onClick = onSearch) {
                        Icon(Icons.Outlined.Search, stringResource(CoreCommonR.string.server_search))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }))
    }
}

@Composable
internal fun ServerBrowseMessage(message: String, error: Boolean = false, onRetry: (() -> Unit)? = null) {
    Surface(shape = NeriDesign.tokens.cardShape,
        color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!error) Icon(Icons.Outlined.Album, null, Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) MiuixSettingsTextButton(onClick = onRetry) {
                Text(stringResource(CoreCommonR.string.server_retry))
            }
        }
    }
}

@Composable
internal fun ServerAlbumRow(album: ServerAlbum, offlineMode: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(NeriDesign.tokens.cardShape).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ServerAlbumCover(album, offlineMode, Modifier.size(60.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(AppR.plurals.explore_song_count, album.songCount, album.songCount),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun ServerAlbumCover(album: ServerAlbum, offlineMode: Boolean, modifier: Modifier) {
    Surface(modifier, shape = NeriDesign.tokens.coverSmallShape,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        if (album.coverUrl.isNullOrBlank()) Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Album, null) }
        else AsyncImage(model = offlineCachedImageRequest(LocalContext.current, album.coverUrl, offlineMode = offlineMode),
            contentDescription = null, contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ServerArtistRow(artist: ServerArtist, modifier: Modifier = Modifier, offlineMode: Boolean = false, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().testTag("serverArtist:${artist.profileId}:${artist.id}")
        .clip(NeriDesign.tokens.cardShape).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(60.dp), shape = NeriDesign.tokens.cardShape,
            color = MaterialTheme.colorScheme.surfaceVariant) {
            ServerArtistArtwork(artist, offlineMode, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(artist.name, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(CoreCommonR.plurals.server_artist_album_count, artist.albumCount, artist.albumCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
