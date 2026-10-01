package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayItem
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.SpringDragCancelledAnimation
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.ReorderableLazyListState
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

private const val QueueSheetMaxHeightFraction = 0.9f
internal val NowPlayingQueueReorderAutoScrollMaxPerFrame = 2.dp
private val QueueReorderDragCancelStiffness = Spring.StiffnessMediumLow
private const val QueueReorderDraggedItemScale = 1.01f

internal data class NowPlayingQueueEntry(
    val key: String,
    val queueIndex: Int,
    val song: SongItem
)

internal fun buildNowPlayingQueueEntries(queue: List<SongItem>): List<NowPlayingQueueEntry> {
    return buildNowPlayingQueueEntriesFromDisplayItems(
        queue.mapIndexed { index, song ->
            PlayerQueueDisplayItem(
                queueIndex = index,
                song = song
            )
        }
    )
}

internal fun buildNowPlayingQueueEntriesFromDisplayItems(
    displayItems: List<PlayerQueueDisplayItem>
): List<NowPlayingQueueEntry> {
    val occurrenceByStableKey = mutableMapOf<String, Int>()
    return displayItems.map { item ->
        val stableKey = item.song.stableKey()
        val occurrence = occurrenceByStableKey.getOrDefault(stableKey, 0)
        occurrenceByStableKey[stableKey] = occurrence + 1
        NowPlayingQueueEntry(
            key = "$occurrence:$stableKey",
            queueIndex = item.queueIndex,
            song = item.song
        )
    }
}

internal fun moveNowPlayingQueueEntry(
    entries: MutableList<NowPlayingQueueEntry>,
    fromKey: String,
    toKey: String
): Boolean {
    val fromIndex = entries.indexOfFirst { it.key == fromKey }
    val toIndex = entries.indexOfFirst { it.key == toKey }
    if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return false
    entries.add(toIndex, entries.removeAt(fromIndex))
    return true
}

internal fun syncNowPlayingQueueEntries(
    entries: MutableList<NowPlayingQueueEntry>,
    sourceEntries: List<NowPlayingQueueEntry>
): Boolean {
    if (entries == sourceEntries) return false
    entries.clear()
    entries.addAll(sourceEntries)
    return true
}

internal fun shouldShowNowPlayingQueueQuickActions(
    queueSize: Int,
    currentIndex: Int,
    hasSourceRoute: Boolean
): Boolean = queueSize > 0

internal fun resolveNowPlayingQueueCurrentIndexAfterReorder(
    queueSize: Int,
    currentIndex: Int,
    currentIndexByKey: Int
): Int {
    if (queueSize <= 0) return -1
    if (currentIndexByKey in 0 until queueSize) return currentIndexByKey
    return currentIndex.coerceIn(0, queueSize - 1)
}

internal fun resolveNowPlayingQueueScrollTarget(
    queueSize: Int,
    currentIndex: Int
): Int? = currentIndex.takeIf { it in 0 until queueSize }

internal fun CoroutineScope.scrollToNowPlayingQueueIndex(
    index: Int,
    queueSize: Int,
    listState: LazyListState
) {
    val target = resolveNowPlayingQueueScrollTarget(queueSize, index) ?: return
    launch { listState.animateScrollToItem(target) }
}

internal fun shouldUpdateNowPlayingQueueScroll(
    targetIndex: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): Boolean = firstVisibleItemIndex != targetIndex || firstVisibleItemScrollOffset != 0

internal fun shouldAutoLocateNowPlayingQueue(
    selectionMode: Boolean,
    queueOrderDirty: Boolean
): Boolean = !selectionMode && !queueOrderDirty

internal sealed interface NowPlayingQueueScrollCommand {
    val marksPositioned: Boolean
    suspend fun execute(listState: LazyListState)

    data object Skip : NowPlayingQueueScrollCommand {
        override val marksPositioned = false
        override suspend fun execute(listState: LazyListState) = Unit
    }

    data object AlreadyPositioned : NowPlayingQueueScrollCommand {
        override val marksPositioned = true
        override suspend fun execute(listState: LazyListState) = Unit
    }

    data class Jump(val index: Int) : NowPlayingQueueScrollCommand {
        override val marksPositioned = true
        override suspend fun execute(listState: LazyListState) = listState.scrollToItem(index)
    }

    data class Animate(val index: Int) : NowPlayingQueueScrollCommand {
        override val marksPositioned = true
        override suspend fun execute(listState: LazyListState) = listState.animateScrollToItem(index)
    }
}

internal fun planNowPlayingQueueScroll(
    queueSize: Int,
    currentIndex: Int,
    selectionMode: Boolean,
    queueOrderDirty: Boolean,
    initialPositioned: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): NowPlayingQueueScrollCommand {
    if (!shouldAutoLocateNowPlayingQueue(selectionMode, queueOrderDirty)) {
        return NowPlayingQueueScrollCommand.Skip
    }
    val target = resolveNowPlayingQueueScrollTarget(queueSize, currentIndex)
        ?: return NowPlayingQueueScrollCommand.Skip
    if (!shouldUpdateNowPlayingQueueScroll(
            target,
            firstVisibleItemIndex,
            firstVisibleItemScrollOffset
        )
    ) {
        return NowPlayingQueueScrollCommand.AlreadyPositioned
    }
    return if (initialPositioned) {
        NowPlayingQueueScrollCommand.Animate(target)
    } else {
        NowPlayingQueueScrollCommand.Jump(target)
    }
}

internal fun isNowPlayingQueueReorderEnabled(
    selectionMode: Boolean,
    allowQueueReorder: Boolean
): Boolean = selectionMode && allowQueueReorder

internal fun shouldShowNowPlayingQueueDragHandle(
    selectionMode: Boolean,
    allowQueueReorder: Boolean
): Boolean = isNowPlayingQueueReorderEnabled(
    selectionMode = selectionMode,
    allowQueueReorder = allowQueueReorder
)

internal fun queueRowScale(isDragging: Boolean): Float =
    if (isDragging) QueueReorderDraggedItemScale else 1f

internal fun resolveNowPlayingQueueIndexInput(
    input: String,
    queueSize: Int
): Int? {
    val targetNumber = input.trim().toIntOrNull() ?: return null
    return (targetNumber - 1).takeIf { it in 0 until queueSize }
}

internal fun resolveNowPlayingQueueSelectedSongs(
    queue: List<SongItem>,
    selectedKeys: Set<String>
): List<SongItem> {
    if (selectedKeys.isEmpty()) return emptyList()
    return buildNowPlayingQueueEntries(queue).mapNotNull { entry ->
        entry.song.takeIf { entry.key in selectedKeys }
    }
}

internal fun invertNowPlayingQueueSelection(
    queue: List<SongItem>,
    selectedKeys: Set<String>
): Set<String> {
    return buildNowPlayingQueueEntries(queue).mapNotNullTo(LinkedHashSet()) { entry ->
        entry.key.takeUnless(selectedKeys::contains)
    }
}

internal fun selectAllNowPlayingQueueKeys(
    allSelected: Boolean,
    queueItemKeys: Set<String>
): Set<String> = if (allSelected) emptySet() else queueItemKeys

internal class NowPlayingQueueReorderOwner(sourceEntries: List<NowPlayingQueueEntry>) {
    val entries = mutableStateListOf<NowPlayingQueueEntry>().apply { addAll(sourceEntries) }
    var isDirty by mutableStateOf(false)
        private set

    fun move(enabled: Boolean, from: Any?, to: Any?) {
        if (!enabled) return
        val fromKey = from as? String ?: return
        val toKey = to as? String ?: return
        if (moveNowPlayingQueueEntry(entries, fromKey, toKey)) isDirty = true
    }

    fun sync(sourceEntries: List<NowPlayingQueueEntry>) {
        if (!isDirty) syncNowPlayingQueueEntries(entries, sourceEntries)
    }

    fun revokeReorder(sourceEntries: List<NowPlayingQueueEntry>) {
        if (!isDirty) return
        isDirty = false
        syncNowPlayingQueueEntries(entries, sourceEntries)
    }

    fun finish(
        enabled: Boolean,
        currentKey: String?,
        currentIndex: Int,
        sourceEntries: List<NowPlayingQueueEntry>,
        commit: (List<SongItem>, Int) -> Boolean
    ) {
        if (!enabled) {
            revokeReorder(sourceEntries)
            return
        }
        if (!isDirty) return
        val currentIndexByKey = currentKey
            ?.let { key -> entries.indexOfFirst { it.key == key } }
            ?: -1
        val indexAfterReorder = resolveNowPlayingQueueCurrentIndexAfterReorder(
            queueSize = entries.size,
            currentIndex = currentIndex,
            currentIndexByKey = currentIndexByKey
        )
        val reordered = commit(entries.map { it.song }, indexAfterReorder)
        isDirty = false
        if (!reordered) syncNowPlayingQueueEntries(entries, sourceEntries)
    }
}

@Composable
private fun NowPlayingQueueRow(
    modifier: Modifier,
    index: Int,
    song: SongItem,
    isCurrent: Boolean,
    isFavoriteSong: Boolean,
    offlineMode: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToEnd: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    dragHandle: @Composable (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = queueRowContainerColor(
        selected,
        isCurrent,
        colors.secondaryContainer,
        colors.primaryContainer,
        colors.surfaceVariant
    )
    val onClick = queueRowClick(selectionMode, onToggleSelect, onPlay)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(20.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueRowSelectionIndicator(visible = selectionMode, selected = selected)
            QueueRowNumber(index = index, isCurrent = isCurrent)
            QueueRowArtwork(song = song, offlineMode = offlineMode)
            Spacer(Modifier.width(12.dp))
            QueueRowSongText(song = song, modifier = Modifier.weight(1f))
            QueueRowCurrentMarker(visible = shouldShowQueueCurrentMarker(isCurrent, selectionMode))
            QueueRowTrailingActions(
                selectionMode = selectionMode,
                isFavoriteSong = isFavoriteSong,
                onPlayNext = onPlayNext,
                onAddToEnd = onAddToEnd,
                onFavoriteToggle = onFavoriteToggle,
                onRemoveFromQueue = onRemoveFromQueue,
                dragHandle = dragHandle
            )
        }
    }
}

internal fun queueRowClick(
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit
): () -> Unit = if (selectionMode) onToggleSelect else onPlay

internal fun shouldShowQueueCurrentMarker(isCurrent: Boolean, selectionMode: Boolean): Boolean =
    isCurrent && !selectionMode

internal fun queueRowContainerColor(
    selected: Boolean,
    isCurrent: Boolean,
    selectedColor: Color,
    currentColor: Color,
    normalColor: Color
): Color =
    when {
        selected -> selectedColor.copy(alpha = 0.64f)
        isCurrent -> currentColor.copy(alpha = 0.42f)
        else -> normalColor.copy(alpha = 0.36f)
    }

@Composable
private fun QueueRowSelectionIndicator(visible: Boolean, selected: Boolean) {
    if (!visible) return
    QueueRowCheckmark(selected)
}

@Composable
private fun QueueRowCheckmark(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.CheckBox,
            contentDescription = stringResource(CoreCommonR.string.common_selected),
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(end = 10.dp)
        )
    } else {
        Icon(
            imageVector = Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = stringResource(CoreCommonR.string.action_select),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp)
        )
    }
}

@Composable
private fun QueueRowNumber(index: Int, isCurrent: Boolean) {
    Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1
        )
    }
}

@Composable
private fun QueueRowArtwork(song: SongItem, offlineMode: Boolean) {
    val context = LocalContext.current
    val coverUrl = queueArtworkUrl(rememberSongDisplayCoverUrl(song))
    if (coverUrl != null) {
        AsyncImage(
            model = offlineCachedImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                allowHardware = false,
                crossfade = true,
                offlineMode = offlineMode
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
        )
    } else {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun queueArtworkUrl(url: String?): String? = url?.takeIf { it.isNotBlank() }

@Composable
private fun QueueRowSongText(song: SongItem, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = song.displayName(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = song.displayArtist(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QueueRowCurrentMarker(visible: Boolean) {
    if (visible) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = stringResource(CoreCommonR.string.player_now_playing),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun QueueRowTrailingActions(
    selectionMode: Boolean,
    isFavoriteSong: Boolean,
    onPlayNext: () -> Unit,
    onAddToEnd: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    dragHandle: @Composable (() -> Unit)?
) {
    if (selectionMode) {
        QueueRowDragHandle(dragHandle)
    } else {
        QueueRowMoreMenu(
            isFavoriteSong = isFavoriteSong,
            onPlayNext = onPlayNext,
            onAddToEnd = onAddToEnd,
            onFavoriteToggle = onFavoriteToggle,
            onRemoveFromQueue = onRemoveFromQueue
        )
    }
}

@Composable
private fun QueueRowDragHandle(dragHandle: @Composable (() -> Unit)?) {
    dragHandle?.invoke()
}

@Composable
private fun QueueRowReorderHandle(reorderState: ReorderableLazyListState) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.48f))
            .detectReorder(reorderState)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = stringResource(CoreCommonR.string.common_drag_handle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QueueRowMoreMenu(
    isFavoriteSong: Boolean,
    onPlayNext: () -> Unit,
    onAddToEnd: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemoveFromQueue: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(CoreCommonR.string.common_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            QueueRowMenuAction(
                label = stringResource(CoreCommonR.string.local_playlist_play_next),
                icon = Icons.Outlined.SkipNext,
                onClick = { onPlayNext(); expanded = false }
            )
            QueueRowMenuAction(
                label = stringResource(CoreCommonR.string.playlist_add_to_end),
                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                onClick = { onAddToEnd(); expanded = false }
            )
            QueueRowFavoriteAction(isFavoriteSong) { onFavoriteToggle(); expanded = false }
            QueueRowMenuAction(
                label = stringResource(CoreCommonR.string.nowplaying_queue_remove),
                icon = Icons.Outlined.DeleteOutline,
                onClick = { onRemoveFromQueue(); expanded = false }
            )
        }
    }
}

@Composable
private fun QueueRowFavoriteAction(isFavoriteSong: Boolean, onClick: () -> Unit) {
    QueueRowMenuAction(
        label = stringResource(queueFavoriteLabel(isFavoriteSong)),
        icon = queueFavoriteIcon(isFavoriteSong),
        onClick = onClick
    )
}

private fun queueFavoriteLabel(isFavoriteSong: Boolean): Int =
    if (isFavoriteSong) CoreCommonR.string.favorite_remove else CoreCommonR.string.favorite_add

private fun queueFavoriteIcon(isFavoriteSong: Boolean): ImageVector =
    if (isFavoriteSong) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder

@Composable
private fun QueueRowMenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
private fun NowPlayingQueueQuickActionButton(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 1
            )
        }
        SmallFloatingActionButton(
            onClick = {
                context.performHapticFeedback(HapticFeedbackEffect.Click)
                onClick()
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            elevation = FloatingActionButtonDefaults.elevation()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
    }
}

@Composable
private fun NowPlayingQueueQuickActionsFab(
    queueSize: Int,
    currentIndex: Int,
    hasSourceRoute: Boolean,
    onLocateCurrent: () -> Unit,
    onOpenSource: () -> Unit,
    onEnterSelection: () -> Unit,
    modifier: Modifier
) {
    if (!shouldShowNowPlayingQueueQuickActions(queueSize, currentIndex, hasSourceRoute)) return

    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            QueueQuickActionItems(
                hasSourceRoute = hasSourceRoute,
                currentIndex = currentIndex,
                onOpenSource = { expanded = false; onOpenSource() },
                onLocateCurrent = { expanded = false; onLocateCurrent() },
                onEnterSelection = { expanded = false; onEnterSelection() }
            )
        }

        QueueQuickActionToggle(expanded) { expanded = !expanded }
    }
}

@Composable
private fun QueueQuickActionItems(
    hasSourceRoute: Boolean,
    currentIndex: Int,
    onOpenSource: () -> Unit,
    onLocateCurrent: () -> Unit,
    onEnterSelection: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QueueSourceAction(hasSourceRoute, onOpenSource)
        QueueLocateAction(currentIndex, onLocateCurrent)
        NowPlayingQueueQuickActionButton(
            label = stringResource(CoreCommonR.string.action_enter_multi_select),
            icon = Icons.Filled.CheckBox,
            contentDescription = stringResource(CoreCommonR.string.action_enter_multi_select),
            onClick = onEnterSelection
        )
    }
}

@Composable
private fun QueueSourceAction(visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    NowPlayingQueueQuickActionButton(
        label = stringResource(CoreCommonR.string.cd_open_current_playback_source),
        icon = Icons.Outlined.LibraryMusic,
        contentDescription = stringResource(CoreCommonR.string.cd_open_current_playback_source),
        onClick = onClick
    )
}

@Composable
private fun QueueLocateAction(currentIndex: Int, onClick: () -> Unit) {
    if (currentIndex < 0) return
    NowPlayingQueueQuickActionButton(
        label = stringResource(CoreCommonR.string.cd_locate_playing),
        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
        contentDescription = stringResource(CoreCommonR.string.cd_locate_playing),
        onClick = onClick
    )
}

@Composable
private fun QueueQuickActionToggle(expanded: Boolean, onClick: () -> Unit) {
    HapticFloatingActionButton(onClick = onClick, hapticEffect = HapticFeedbackEffect.Click) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.Close else Icons.Filled.MoreVert,
            contentDescription = stringResource(CoreCommonR.string.cd_queue_quick_actions)
        )
    }
}

@Composable
private fun NowPlayingQueueSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    canExport: Boolean,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onExport: () -> Unit,
    onExitSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HapticIconButton(onClick = onExitSelection) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(CoreCommonR.string.action_cancel)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(CoreCommonR.string.common_selected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = pluralStringResource(
                    CoreCommonR.plurals.common_selected_count,
                    selectedCount,
                    selectedCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HapticIconButton(onClick = onSelectAll) {
            QueueSelectAllIcon(allSelected)
        }
        HapticTextButton(onClick = onInvertSelection) {
            Text(stringResource(CoreCommonR.string.action_inverse_select))
        }
        HapticIconButton(
            enabled = canExport,
            onClick = onExport
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = stringResource(CoreCommonR.string.cd_export_playlist)
            )
        }
    }
}

@Composable
private fun QueueSelectAllIcon(allSelected: Boolean) {
    val icon = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank
    Icon(imageVector = icon, contentDescription = queueSelectAllDescription(allSelected))
}

@Composable
private fun queueSelectAllDescription(allSelected: Boolean): String =
    stringResource(if (allSelected) CoreCommonR.string.action_deselect_all else CoreCommonR.string.action_select_all)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingQueueSheet(
    displayedQueueItems: List<PlayerQueueDisplayItem>,
    currentIndexInDisplay: Int,
    offlineMode: Boolean,
    allowQueueReorder: Boolean,
    onDismissRequest: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val localPlaylistRepo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val playerPlaylists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val allLocalPlaylists by localPlaylistRepo.playlists.collectAsStateWithLifecycle(
        initialValue = playerPlaylists
    )
    val displayedQueue = remember(displayedQueueItems) {
        displayedQueueItems.map { it.song }
    }
    val favoriteSongs = remember(allLocalPlaylists, context) {
        FavoritesPlaylist.firstOrNull(allLocalPlaylists, context)?.songs.orEmpty()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showQueueIndexJumpDialog by remember { mutableStateOf(false) }
    var queueIndexInput by remember { mutableStateOf("") }
    val sourceEntries = remember(displayedQueueItems) {
        buildNowPlayingQueueEntriesFromDisplayItems(displayedQueueItems)
    }
    val initialQueueScrollTarget = remember(sourceEntries, currentIndexInDisplay) {
        resolveNowPlayingQueueScrollTarget(
            queueSize = sourceEntries.size,
            currentIndex = currentIndexInDisplay
        )
    }
    val queueListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialQueueScrollTarget ?: 0
    )
    var initialQueuePositioned by remember { mutableStateOf(initialQueueScrollTarget != null) }
    val reorderOwner = remember { NowPlayingQueueReorderOwner(sourceEntries) }
    val queueEntries = reorderOwner.entries
    val queueOrderDirty = reorderOwner.isDirty
    val currentEntryKey = sourceEntries.getOrNull(currentIndexInDisplay)?.key
    val currentIndexInQueueEntries = currentEntryKey
        ?.let { key -> queueEntries.indexOfFirst { it.key == key } }
        ?.takeIf { it >= 0 }
        ?: currentIndexInDisplay
    val latestCurrentEntryKey by rememberUpdatedState(currentEntryKey)
    val latestCurrentIndexInQueueEntries by rememberUpdatedState(currentIndexInQueueEntries)
    val latestQueueReorderEnabled by rememberUpdatedState(
        isNowPlayingQueueReorderEnabled(
            selectionMode = selectionMode,
            allowQueueReorder = allowQueueReorder
        )
    )
    val latestSourceEntries by rememberUpdatedState(sourceEntries)
    val queueItemKeys by remember {
        derivedStateOf {
            queueEntries.mapTo(LinkedHashSet()) { it.key }
        }
    }
    val selectedSongs by remember {
        derivedStateOf {
            queueEntries.filter { it.key in selectedKeys }.map { it.song }
        }
    }
    val allItemsSelected = queueEntries.isNotEmpty() &&
        selectedKeys.size == queueItemKeys.size &&
        selectedKeys.containsAll(queueItemKeys)
    val reorderState = rememberReorderableLazyListState(
        listState = queueListState,
        onMove = { from: ItemPosition, to: ItemPosition ->
            reorderOwner.move(latestQueueReorderEnabled, from.key, to.key)
        },
        onDragEnd = { _, _ ->
            reorderOwner.finish(
                enabled = latestQueueReorderEnabled,
                currentKey = latestCurrentEntryKey,
                currentIndex = latestCurrentIndexInQueueEntries,
                sourceEntries = latestSourceEntries
            ) { songs, index ->
                PlayerManager.reorderQueue(
                    queue = songs,
                    currentIndexInQueue = index
                )
            }
        },
        maxScrollPerFrame = NowPlayingQueueReorderAutoScrollMaxPerFrame,
        dragCancelledAnimation = SpringDragCancelledAnimation(
            stiffness = QueueReorderDragCancelStiffness
        )
    )

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    var dismissingQueue by remember { mutableStateOf(false) }

    fun dismissQueue() {
        if (dismissingQueue) return
        dismissingQueue = true
        showExportSheet = false
        showQueueIndexJumpDialog = false
        exitSelection()
        screenScope.launch {
            runCatching { sheetState.hide() }
            onDismissRequest()
        }
    }

    fun scrollToQueueIndex(index: Int) {
        screenScope.scrollToNowPlayingQueueIndex(index, queueEntries.size, reorderState.listState)
    }

    fun locateCurrentQueueItem() {
        scrollToQueueIndex(currentIndexInQueueEntries)
    }

    fun openQueueIndexJumpDialog() {
        context.performHapticFeedback(HapticFeedbackEffect.Click)
        queueIndexInput = (currentIndexInQueueEntries + 1)
            .coerceIn(1, queueEntries.size.coerceAtLeast(1))
            .toString()
        showQueueIndexJumpDialog = true
    }

    fun toggleItem(key: String) {
        selectedKeys = if (key in selectedKeys) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
    }

    fun applySelection(keys: Set<String>) {
        selectedKeys = keys
        if (keys.isEmpty()) selectionMode = false
    }

    fun toggleQueueSongFavorite(song: SongItem, isFavoriteSong: Boolean) {
        screenScope.launchLocalPlaylistMutation("toggleNowPlayingQueueSongFavorite") {
            if (isFavoriteSong) {
                localPlaylistRepo.removeFromFavorites(song)
            } else {
                localPlaylistRepo.addToFavorites(song)
            }
        }
    }

    LaunchedEffect(sourceEntries) {
        reorderOwner.sync(sourceEntries)
    }

    LaunchedEffect(allowQueueReorder) {
        if (!allowQueueReorder) reorderOwner.revokeReorder(sourceEntries)
    }

    LaunchedEffect(queueEntries.size, currentIndexInQueueEntries, selectionMode, queueOrderDirty) {
        val command = planNowPlayingQueueScroll(
            queueSize = queueEntries.size,
            currentIndex = currentIndexInQueueEntries,
            selectionMode = selectionMode,
            queueOrderDirty = queueOrderDirty,
            initialPositioned = initialQueuePositioned,
            firstVisibleItemIndex = queueListState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = queueListState.firstVisibleItemScrollOffset
        )
        if (command.marksPositioned) initialQueuePositioned = true
        command.execute(queueListState)
    }

    LaunchedEffect(queueItemKeys, selectedKeys) {
        val cleanedKeys = selectedKeys.intersect(queueItemKeys)
        if (cleanedKeys != selectedKeys) selectedKeys = cleanedKeys
    }

    ModalBottomSheet(
        onDismissRequest = ::dismissQueue,
        sheetState = sheetState,
        sheetGesturesEnabled = false
    ) {
        BackHandler(enabled = selectionMode && !showExportSheet) {
            exitSelection()
        }

        BackHandler(enabled = showExportSheet) {
            showExportSheet = false
        }

        if (showQueueIndexJumpDialog) {
            NowPlayingQueueIndexJumpDialog(
                queueSize = queueEntries.size,
                input = queueIndexInput,
                onInputChange = { queueIndexInput = it },
                onDismiss = { showQueueIndexJumpDialog = false },
                onJump = { targetIndex ->
                    scrollToQueueIndex(targetIndex)
                    showQueueIndexJumpDialog = false
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight(QueueSheetMaxHeightFraction)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(Modifier.fillMaxSize()) {
                if (selectionMode) {
                    NowPlayingQueueSelectionToolbar(
                        selectedCount = selectedKeys.size,
                        allSelected = allItemsSelected,
                        canExport = selectedSongs.isNotEmpty(),
                        onSelectAll = {
                            applySelection(selectAllNowPlayingQueueKeys(allItemsSelected, queueItemKeys))
                        },
                        onInvertSelection = {
                            applySelection(invertNowPlayingQueueSelection(
                                queueEntries.map { it.song },
                                selectedKeys
                            ))
                        },
                        onExport = { showExportSheet = true },
                        onExitSelection = ::exitSelection
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 18.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(CoreCommonR.string.playlist_queue),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = pluralStringResource(
                                    CoreCommonR.plurals.nowplaying_queue_count_format,
                                    displayedQueue.size,
                                    displayedQueue.size
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentIndexInQueueEntries >= 0) {
                            val queueIndexButtonShape = RoundedCornerShape(999.dp)
                            Surface(
                                modifier = Modifier
                                    .clip(queueIndexButtonShape)
                                    .clickable(onClick = ::openQueueIndexJumpDialog),
                                shape = queueIndexButtonShape,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            CoreCommonR.string.nowplaying_queue_current_position,
                                            currentIndexInQueueEntries + 1
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = reorderState.listState,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (allowQueueReorder) {
                                Modifier.reorderable(reorderState)
                            } else {
                                Modifier
                            }
                        )
                        .bottomSheetScrollGuard(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 98.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = queueEntries,
                        key = { _, entry -> entry.key },
                        contentType = { _, _ -> "queue_song" }
                    ) { index, entry ->
                        ReorderableItem(state = reorderState, key = entry.key) { isDragging ->
                            val isFavoriteSong = remember(favoriteSongs, entry.song) {
                                favoriteSongs.any { it.sameIdentityAs(entry.song) }
                            }
                            val rowScale by animateFloatAsState(
                                targetValue = queueRowScale(isDragging),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "queue_row_scale"
                            )
                            NowPlayingQueueRow(
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = rowScale
                                        scaleY = rowScale
                                    },
                                index = index,
                                song = entry.song,
                                isCurrent = entry.key == currentEntryKey,
                                isFavoriteSong = isFavoriteSong,
                                offlineMode = offlineMode,
                                selectionMode = selectionMode,
                                selected = entry.key in selectedKeys,
                                onPlay = {
                                    PlayerManager.playFromQueue(index)
                                    dismissQueue()
                                },
                                onLongPress = {
                                    selectionMode = true
                                    selectedKeys = selectedKeys + entry.key
                                },
                                onToggleSelect = { toggleItem(entry.key) },
                                onPlayNext = { PlayerManager.addToQueueNext(entry.song) },
                                onAddToEnd = { PlayerManager.addToQueueEnd(entry.song) },
                                onFavoriteToggle = {
                                    toggleQueueSongFavorite(entry.song, isFavoriteSong)
                                },
                                onRemoveFromQueue = {
                                    PlayerManager.removeQueueItem(index)
                                },
                                dragHandle = if (
                                    shouldShowNowPlayingQueueDragHandle(
                                        selectionMode = selectionMode,
                                        allowQueueReorder = allowQueueReorder
                                    )
                                ) {
                                    {
                                        QueueRowReorderHandle(reorderState)
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }

            if (!selectionMode) {
                NowPlayingQueueQuickActionsFab(
                    queueSize = displayedQueue.size,
                    currentIndex = currentIndexInQueueEntries,
                    hasSourceRoute = onOpenCurrentPlaybackSource != null,
                    onLocateCurrent = {
                        locateCurrentQueueItem()
                    },
                    onOpenSource = {
                        dismissQueue()
                        onOpenCurrentPlaybackSource?.invoke()
                    },
                    onEnterSelection = {
                        selectionMode = true
                        selectedKeys = emptySet()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                )
            }

            NeriOverlaySnackbarHost(
                hostState = snackbarHostState,
                applyNavigationBarsPadding = false
            )
        }
    }

    if (showExportSheet) {
        PlaylistExportSheet(
            title = stringResource(CoreCommonR.string.playlist_export_to_local),
            playlists = allLocalPlaylists.filterNot {
                LocalFilesPlaylist.isSystemPlaylist(it, context)
            },
            selectedCount = selectedSongs.size,
            onDismissRequest = { showExportSheet = false },
            onCreateAndExport = { name ->
                val songs = selectedSongs
                screenScope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromNowPlayingQueue",
                    onResult = { result ->
                        screenScope.showPlaylistBatchExportCreatedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = localPlaylistRepo,
                            result = result
                        )
                    }
                ) {
                    localPlaylistRepo.createPlaylistWithSongs(name, songs)
                }
                showExportSheet = false
                dismissQueue()
            },
            onExportToPlaylist = { playlist ->
                val songs = selectedSongs
                screenScope.launchLocalPlaylistMutation(
                    operation = "exportSongsFromNowPlayingQueue",
                    onResult = { result ->
                        screenScope.showPlaylistBatchExportAddedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = localPlaylistRepo,
                            targetPlaylistId = playlist.id,
                            targetPlaylistName = playlist.name,
                            result = result
                        )
                    }
                ) {
                    localPlaylistRepo.addSongsToPlaylistWithResult(playlist.id, songs)
                }
                showExportSheet = false
                dismissQueue()
            }
        )
    }
}

@Composable
private fun NowPlayingQueueIndexJumpDialog(
    queueSize: Int,
    input: String,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    val targetIndex = resolveNowPlayingQueueIndexInput(input, queueSize)
    val isInputError = isNowPlayingQueueIndexInputError(input, targetIndex)
    val onSubmit: () -> Unit = { targetIndex?.let(onJump) }

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.nowplaying_queue_jump_title)) },
        text = {
            QueueIndexJumpContent(input, queueSize, isInputError, onInputChange, onSubmit)
        },
        confirmButton = {
            MiuixSettingsButton(
                onClick = onSubmit,
                enabled = targetIndex != null
            ) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

internal fun isNowPlayingQueueIndexInputError(input: String, targetIndex: Int?): Boolean =
    input.isNotBlank() && targetIndex == null

internal fun filterNowPlayingQueueIndexInput(input: String): String =
    input.filter(Char::isDigit).take(6)

@Composable
private fun QueueIndexJumpContent(
    input: String,
    queueSize: Int,
    isInputError: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    MiuixSettingsDialogContent(verticalSpacing = 8.dp) {
        MiuixSettingsTextField(
            value = input,
            onValueChange = { onInputChange(filterNowPlayingQueueIndexInput(it)) },
            placeholder = { Text(stringResource(CoreCommonR.string.nowplaying_queue_jump_input_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() })
        )
        QueueIndexJumpSupportText(queueSize, isInputError)
    }
}

@Composable
private fun QueueIndexJumpSupportText(queueSize: Int, isInputError: Boolean) {
    Text(
        text = stringResource(CoreCommonR.string.nowplaying_queue_jump_input_supporting, queueSize),
        style = MaterialTheme.typography.bodySmall,
        color = if (isInputError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
