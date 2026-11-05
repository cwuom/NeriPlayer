package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun ServerSongActions(
    songs: List<SongItem>, selectedCount: Int, selectionMode: Boolean, allSelected: Boolean, partial: Boolean,
    onPlay: () -> Unit, onAddToPlaylist: () -> Unit, onDownload: () -> Unit,
    onSelection: () -> Unit, onSelectAll: () -> Unit, onShufflePlay: (() -> Unit)? = null
) {
    var showActions by remember { mutableStateOf(false) }
    val count = if (selectionMode) selectedCount else songs.size
    val countText = if (selectionMode) pluralStringResource(AppR.plurals.common_selected_count, count, count)
        else pluralStringResource(AppR.plurals.explore_song_count, count, count)
    val playLabel = stringResource(when {
        selectionMode -> CoreCommonR.string.server_play_selected
        partial -> CoreCommonR.string.server_play_loaded
        else -> CoreCommonR.string.player_play_all
    })
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(countText, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selectionMode) {
                HapticIconButton(onClick = onSelectAll) {
                    Icon(if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        stringResource(if (allSelected) CoreCommonR.string.action_deselect_all else CoreCommonR.string.action_select_all))
                }
                MiuixSettingsTextButton(onClick = onSelection) { Text(stringResource(CoreCommonR.string.action_done)) }
            }
            HapticIconButton(onClick = { showActions = true }, enabled = songs.isNotEmpty()) {
                Icon(Icons.Filled.MoreVert, stringResource(CoreCommonR.string.cd_more_actions))
            }
        }
        if (onShufflePlay == null) {
            MiuixSettingsButton(onClick = onPlay, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(playLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            val shuffleLabel = stringResource(CoreCommonR.string.player_shuffle_play)
            val labelStyle = MaterialTheme.typography.labelLarge
            val measurer = rememberTextMeasurer()
            val iconSize = 20.dp
            val iconSpacing = 8.dp
            val horizontalPadding = 18.dp
            val contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 10.dp)
            // 按较长的完整文案给两个按钮预留同样宽度，避免等分后截断文字
            val labelWidth = listOf(playLabel, shuffleLabel).maxOf { label ->
                measurer.measure(AnnotatedString(label), style = labelStyle, softWrap = false, maxLines = 1).size.width
            }
            val minWidth = with(LocalDensity.current) {
                (labelWidth + iconSize.roundToPx() + iconSpacing.roundToPx() + horizontalPadding.roundToPx() * 2).toDp()
            }
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticButton(onClick = onPlay, enabled = count > 0, shape = NeriDesign.tokens.controlShape,
                    contentPadding = contentPadding, modifier = Modifier.weight(1f).widthIn(min = minWidth).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(iconSize))
                    Spacer(Modifier.width(iconSpacing))
                    Text(playLabel, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HapticOutlinedButton(onClick = onShufflePlay, enabled = count > 0, shape = NeriDesign.tokens.controlShape,
                    contentPadding = contentPadding, modifier = Modifier.weight(1f).widthIn(min = minWidth).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Shuffle, null, Modifier.size(iconSize))
                    Spacer(Modifier.width(iconSpacing))
                    Text(shuffleLabel, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (partial) Text(stringResource(CoreCommonR.string.server_loaded_results_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showActions) MusicServerActionSheet(title = countText, onDismiss = { showActions = false }) {
        MusicServerActionRow(playLabel, Icons.Filled.PlayArrow, enabled = count > 0) {
            showActions = false; onPlay()
        }
        MusicServerActionRow(stringResource(CoreCommonR.string.playlist_add_to), Icons.Outlined.LibraryAdd,
            enabled = count > 0) { showActions = false; onAddToPlaylist() }
        MusicServerActionRow(stringResource(CoreCommonR.string.download_to_local), Icons.Outlined.Download,
            enabled = count > 0) { showActions = false; onDownload() }
        if (!selectionMode) MusicServerActionRow(stringResource(CoreCommonR.string.action_enter_multi_select),
            Icons.Outlined.SelectAll) { showActions = false; onSelection() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicServerActionSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    DensityScaledModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState()).bottomSheetScrollGuard()
            .padding(horizontal = NeriDesign.tokens.pageHorizontalPadding).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = NeriDesign.tokens.groupShape,
                color = if (NeriDesign.isClean) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
            }
        }
    }
}

@Composable
private fun MusicServerActionRow(title: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            leadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick))
}
