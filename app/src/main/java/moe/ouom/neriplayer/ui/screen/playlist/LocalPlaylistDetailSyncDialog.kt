package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/LocalPlaylistDetailScreen
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.identity.stableKey
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDuration
import java.io.File
@Composable
internal fun NeteaseRemotePlaylistPickerDialog(
    playlists: List<NeteaseRemotePlaylist>,
    loading: Boolean,
    errorMessage: String?,
    onPlaylistClick: (NeteaseRemotePlaylist) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(CoreCommonR.string.local_playlist_sync_netease_picker_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(CoreCommonR.string.local_playlist_sync_netease_loading_playlists))
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (playlists.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(
                            items = playlists,
                            key = { playlist -> playlist.id }
                        ) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !loading) {
                                        onPlaylistClick(playlist)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                    contentDescription = null
                                )
                                Column {
                                    Text(
                                        text = playlist.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            CoreCommonR.plurals.local_playlist_sync_netease_track_count,
                                            playlist.trackCount,
                                            playlist.trackCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            HapticTextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun LocalMetadataProcessingCard(state: LocalMetadataProcessingState) {
    val total = state.totalCount.coerceAtLeast(0)
    val processed = state.processedCount.coerceIn(0, total.takeIf { it > 0 } ?: Int.MAX_VALUE)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreCommonR.string.local_playlist_metadata_processing_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (total > 0) {
                        stringResource(
                            CoreCommonR.string.local_playlist_metadata_processing_message,
                            processed,
                            total
                        )
                    } else {
                        stringResource(CoreCommonR.string.local_playlist_metadata_processing_message_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
        }
    }
}

internal data class LocalScanPreviewItem(
    val song: SongItem,
    val stableKey: String,
    val rowKey: String,
    val title: String,
    val fileName: String,
    val filePath: String,
    val subtitle: String,
    val hasMetadata: Boolean,
    val searchText: String
)

internal fun SongItem.toLocalScanPreviewItem(
    context: Context,
    metadataPending: Boolean = false
): LocalScanPreviewItem {
    val resolvedPath = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?: mediaUri?.takeIf { it.startsWith("/") }
        ?: mediaUri.orEmpty()
    val displayName = displayName()
    val displayArtist = displayArtist()
    val displayAlbum = displayAlbum(context)
    val resolvedFileName = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.name
        ?: localFileName?.takeIf { it.isNotBlank() }
        ?: displayName
    val hasMetadata = metadataPending || hasMeaningfulPreviewMetadata(context, resolvedFileName)
    val subtitle = buildList {
        displayArtist.takeIf { it.isNotBlank() }?.let(::add)
        displayAlbum.takeIf { it.isNotBlank() }?.let(::add)
        resolvedFileName.takeIf { it.isNotBlank() && it != displayName }?.let(::add)
        durationMs.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }.joinToString(" · ")
    return LocalScanPreviewItem(
        song = this,
        stableKey = stableKey(),
        rowKey = scanPreviewRowKey(),
        title = displayName,
        fileName = resolvedFileName,
        filePath = resolvedPath,
        subtitle = subtitle,
        hasMetadata = hasMetadata,
        searchText = listOf(resolvedFileName, resolvedPath, displayName, displayArtist, displayAlbum)
            .joinToString("\n")
    )
}

internal fun SongItem.scanPreviewRowKey(): String {
    val source = mediaUri
        ?.takeIf(String::isNotBlank)
        ?: localFilePath?.takeIf(String::isNotBlank)
        ?: localFileName?.takeIf(String::isNotBlank)
    return source?.let { "local-scan:$it" } ?: "local-scan:${stableKey()}"
}
