package moe.ouom.neriplayer.ui.screen.playlist.insert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistSongArtwork

@Composable
internal fun PlaylistInsertPreviewCard(
    preview: PlaylistInsertPreview,
    songs: List<SongItem>,
    offlineMode: Boolean,
    maxHeight: Dp,
    modifier: Modifier = Modifier
) {
    val songsByKey = remember(songs.toList()) { songs.associateBy { it.stableKey() } }
    val startIndex = preview.startPosition - 1
    val endIndex = startIndex + preview.movedKeys.size
    val beforeStartIndex = (startIndex - ContextSongCount).coerceAtLeast(0)
    val afterEndIndex = (endIndex + ContextSongCount).coerceAtMost(preview.orderedKeys.size)
    val beforeCount = startIndex - beforeStartIndex
    val selectedSectionIndex = 1 + beforeCount + if (beforeStartIndex > 0) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedSectionIndex)
    Surface(
        modifier = modifier.heightIn(max = maxHeight),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.playlist_insert_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    CoreCommonR.string.playlist_insert_preview_range,
                    preview.startPosition,
                    endIndex
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "before-label") {
                    PlaylistInsertContextLabel(
                        stringResource(
                            if (startIndex == 0) CoreCommonR.string.playlist_insert_at_start
                            else CoreCommonR.string.playlist_insert_section_before
                        )
                    )
                }
                if (beforeStartIndex > 0) {
                    item(key = "omitted-before") {
                        PlaylistInsertContextLabel(
                            pluralStringResource(
                                CoreCommonR.plurals.playlist_insert_omitted_before,
                                beforeStartIndex,
                                beforeStartIndex
                            )
                        )
                    }
                }
                items(
                    count = beforeCount,
                    key = { offset -> preview.orderedKeys[beforeStartIndex + offset] }
                ) { offset ->
                    val index = beforeStartIndex + offset
                    songsByKey[preview.orderedKeys[index]]?.let {
                        PlaylistInsertPreviewSongRow(it, index + 1, false, offlineMode)
                    }
                }
                item(key = "selected-label") {
                    PlaylistInsertContextLabel(
                        pluralStringResource(
                            CoreCommonR.plurals.playlist_insert_section_selected,
                            preview.movedKeys.size,
                            preview.movedKeys.size
                        ),
                        emphasized = true
                    )
                }
                items(count = preview.movedKeys.size, key = { preview.movedKeys[it] }) { offset ->
                    songsByKey[preview.movedKeys[offset]]?.let {
                        PlaylistInsertPreviewSongRow(it, preview.startPosition + offset, true, offlineMode)
                    }
                }
                item(key = "after-label") {
                    PlaylistInsertContextLabel(
                        stringResource(
                            if (endIndex == preview.orderedKeys.size) CoreCommonR.string.playlist_insert_at_end
                            else CoreCommonR.string.playlist_insert_section_after
                        )
                    )
                }
                items(
                    count = afterEndIndex - endIndex,
                    key = { offset -> preview.orderedKeys[endIndex + offset] }
                ) { offset ->
                    val index = endIndex + offset
                    songsByKey[preview.orderedKeys[index]]?.let {
                        PlaylistInsertPreviewSongRow(it, index + 1, false, offlineMode)
                    }
                }
                if (afterEndIndex < preview.orderedKeys.size) {
                    item(key = "omitted-after") {
                        PlaylistInsertContextLabel(
                            pluralStringResource(
                                CoreCommonR.plurals.playlist_insert_omitted_after,
                                preview.orderedKeys.size - afterEndIndex,
                                preview.orderedKeys.size - afterEndIndex
                            )
                        )
                    }
                }
            }
        }
    }
}

private const val ContextSongCount = 5

@Composable
private fun PlaylistInsertContextLabel(text: String, emphasized: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        color = if (emphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PlaylistInsertPreviewSongRow(
    song: SongItem,
    position: Int,
    selected: Boolean,
    offlineMode: Boolean
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.width(38.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Box(Modifier.testTag("playlist-insert-artwork-${song.stableKey()}")) {
                LocalPlaylistSongArtwork(
                    song = song,
                    offlineMode = offlineMode,
                    resolveLocalFallback = true,
                    downloadPresenceVersion = 0,
                    allowEmbeddedCoverFallback = true
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = song.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtist(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
