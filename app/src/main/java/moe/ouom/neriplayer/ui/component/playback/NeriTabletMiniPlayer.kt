package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

data class MiniPlayerTabletControls(
    val trackKey: String?,
    val positionMs: Long,
    val durationMs: Long,
    val seekEnabled: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val onSeek: (Long) -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onVolume: () -> Unit,
    val onListenTogether: () -> Unit,
    val onQueue: () -> Unit
)

internal enum class TabletMiniPlayerLayout {
    Full, Compact, Overflow, Minimal
}

internal fun tabletMiniPlayerLayout(width: Dp): TabletMiniPlayerLayout = when {
    !width.value.isFinite() -> TabletMiniPlayerLayout.Minimal
    width >= 840.dp -> TabletMiniPlayerLayout.Full
    width >= 600.dp -> TabletMiniPlayerLayout.Compact
    width >= 480.dp -> TabletMiniPlayerLayout.Overflow
    else -> TabletMiniPlayerLayout.Minimal
}

internal fun miniPlayerProgress(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f

internal fun miniPlayerSeekPosition(progress: Float, durationMs: Long): Long =
    if (durationMs > 0L && progress.isFinite()) {
        (progress.coerceIn(0f, 1f).toDouble() * durationMs).toLong().coerceIn(0L, durationMs)
    } else 0L

@Composable
internal fun TabletMiniPlayerContent(
    controls: MiniPlayerTabletControls,
    playPauseEnabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    cover: @Composable () -> Unit,
    metadata: @Composable (Modifier) -> Unit,
    playPauseIcon: @Composable () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(NeriMiniPlayerDefaults.TabletHeight)) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = NeriMiniPlayerDefaults.TabletProgressThickness)
        ) {
            val layout = tabletMiniPlayerLayout(maxWidth)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    cover()
                    metadata(Modifier.weight(1f).testTag("miniPlayerMetadata"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (layout == TabletMiniPlayerLayout.Full) {
                        TabletMiniPlayerAction(
                            icon = Icons.Outlined.Shuffle,
                            label = CoreCommonR.string.player_shuffle,
                            tag = "miniPlayerShuffle",
                            onClick = controls.onShuffle,
                            active = controls.shuffleEnabled
                        )
                    }
                    if (layout != TabletMiniPlayerLayout.Minimal) {
                        TabletMiniPlayerAction(
                            icon = Icons.Outlined.SkipPrevious,
                            label = CoreCommonR.string.player_previous,
                            tag = "miniPlayerPrevious",
                            onClick = onPrevious
                        )
                    }
                    HapticIconButton(
                        onClick = onPlayPause,
                        enabled = playPauseEnabled,
                        modifier = Modifier.size(48.dp).testTag("miniPlayerPlayPause")
                    ) {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { playPauseIcon() }
                    }
                    if (layout != TabletMiniPlayerLayout.Minimal) {
                        TabletMiniPlayerAction(
                            icon = Icons.Outlined.SkipNext,
                            label = CoreCommonR.string.player_next,
                            tag = "miniPlayerNext",
                            onClick = onNext
                        )
                    }
                    if (layout == TabletMiniPlayerLayout.Full) {
                        TabletMiniPlayerAction(
                            icon = if (controls.repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else Icons.Outlined.Repeat,
                            label = CoreCommonR.string.player_repeat,
                            tag = "miniPlayerRepeat",
                            onClick = controls.onRepeat,
                            active = controls.repeatMode != Player.REPEAT_MODE_OFF
                        )
                    }
                }
                Row(
                    modifier = if (layout == TabletMiniPlayerLayout.Full) Modifier.weight(1f) else Modifier,
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val showUtilities = layout == TabletMiniPlayerLayout.Full || layout == TabletMiniPlayerLayout.Compact
                    if (showUtilities) {
                        TabletMiniPlayerAction(
                            icon = Icons.AutoMirrored.Outlined.VolumeUp,
                            label = CoreCommonR.string.mini_player_volume,
                            tag = "miniPlayerVolume",
                            onClick = controls.onVolume
                        )
                        TabletMiniPlayerAction(
                            icon = Icons.Outlined.Groups,
                            label = CoreCommonR.string.listen_together_title,
                            tag = "miniPlayerListenTogether",
                            onClick = controls.onListenTogether
                        )
                        TabletMiniPlayerAction(
                            icon = Icons.AutoMirrored.Outlined.QueueMusic,
                            label = CoreCommonR.string.playlist_queue,
                            tag = "miniPlayerQueue",
                            onClick = controls.onQueue
                        )
                    }
                    if (layout == TabletMiniPlayerLayout.Full) {
                        TabletMiniPlayerAction(
                            icon = Icons.Outlined.ExpandLess,
                            label = CoreCommonR.string.player_now_playing,
                            tag = "miniPlayerExpand",
                            onClick = onExpand
                        )
                    } else {
                        TabletMiniPlayerOverflow(
                            controls = controls,
                            showUtilities = showUtilities,
                            showSkipItems = layout == TabletMiniPlayerLayout.Minimal,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onExpand = onExpand
                        )
                    }
                }
            }
        }
        // 进度命中区域叠在顶部，避免它的高度把信息和控件向下挤
        TabletMiniPlayerProgress(controls)
    }
}

@Composable
private fun TabletMiniPlayerAction(
    icon: ImageVector,
    label: Int,
    tag: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    HapticIconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun TabletMiniPlayerOverflow(
    controls: MiniPlayerTabletControls,
    showUtilities: Boolean,
    showSkipItems: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TabletMiniPlayerAction(
            icon = Icons.Outlined.MoreHoriz,
            label = CoreCommonR.string.nowplaying_more_options,
            tag = "miniPlayerOverflow",
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            @Composable
            fun ActionItem(icon: ImageVector, label: Int, callback: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, null) },
                    onClick = {
                        expanded = false
                        callback()
                    }
                )
            }
            if (showSkipItems) {
                ActionItem(Icons.Outlined.SkipPrevious, CoreCommonR.string.player_previous, onPrevious)
                ActionItem(Icons.Outlined.SkipNext, CoreCommonR.string.player_next, onNext)
            }
            ActionItem(Icons.Outlined.Shuffle, CoreCommonR.string.player_shuffle, controls.onShuffle)
            ActionItem(
                if (controls.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                CoreCommonR.string.player_repeat,
                controls.onRepeat
            )
            if (!showUtilities) {
                ActionItem(Icons.AutoMirrored.Outlined.VolumeUp, CoreCommonR.string.mini_player_volume, controls.onVolume)
                ActionItem(Icons.Outlined.Groups, CoreCommonR.string.listen_together_title, controls.onListenTogether)
                ActionItem(Icons.AutoMirrored.Outlined.QueueMusic, CoreCommonR.string.playlist_queue, controls.onQueue)
            }
            ActionItem(Icons.Outlined.ExpandLess, CoreCommonR.string.player_now_playing, onExpand)
        }
    }
}

@Composable
private fun TabletMiniPlayerProgress(controls: MiniPlayerTabletControls) {
    val enabled = controls.seekEnabled && controls.durationMs > 0L
    var preview by remember(controls.trackKey, controls.durationMs, enabled) { mutableStateOf<Float?>(null) }
    val latestSeek by rememberUpdatedState(controls.onSeek)
    val progress = preview ?: miniPlayerProgress(controls.positionMs, controls.durationMs)
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f)
    val description = stringResource(CoreCommonR.string.mini_player_progress)
    Canvas(
        Modifier.fillMaxWidth().height(NeriMiniPlayerDefaults.TabletProgressHeight)
            .clipToBounds().testTag("miniPlayerProgress")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (enabled) setProgress { value ->
                    if (!value.isFinite()) false else {
                        latestSeek(miniPlayerSeekPosition(value, controls.durationMs))
                        true
                    }
                } else disabled()
            }
            .pointerInput(controls.trackKey, controls.durationMs, enabled) {
                detectTapGestures { offset ->
                    if (enabled) {
                        latestSeek(miniPlayerSeekPosition(offset.x / size.width.coerceAtLeast(1), controls.durationMs))
                    }
                }
            }
            .pointerInput(controls.trackKey, controls.durationMs, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { preview = (it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        preview = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onDragCancel = { preview = null },
                    onDragEnd = {
                        preview?.let { latestSeek(miniPlayerSeekPosition(it, controls.durationMs)) }
                        preview = null
                    }
                )
            }
    ) {
        val strokeWidth = NeriMiniPlayerDefaults.TabletProgressThickness.toPx().coerceAtMost(size.height)
        val y = strokeWidth / 2f
        val x = progress * size.width
        val radius = minOf(
            if (preview != null) 5.dp.toPx() else 3.dp.toPx(),
            size.width / 2f,
            size.height / 2f
        )
        val thumbX = x.coerceIn(radius, size.width - radius)
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth, StrokeCap.Butt)
        drawLine(color, Offset(0f, y), Offset(x, y), strokeWidth, StrokeCap.Butt)
        drawCircle(color, radius, Offset(thumbX, radius))
    }
}
