package moe.ouom.neriplayer.ui.screen.tab.settings.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.PlaybackControlSize
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget

internal enum class PlaybackControlLayoutSetting {
    NOW_PLAYING_PLACEMENT,
    NOW_PLAYING_SIZE,
    LYRICS_SIZE
}

internal class PlaybackControlLayoutOwner {
    private val selectedSettingState = mutableStateOf<PlaybackControlLayoutSetting?>(null)
    val selectedSetting: PlaybackControlLayoutSetting?
        get() = selectedSettingState.value

    var preferences: PlaybackControlLayoutPreferences = PlaybackControlLayoutPreferences()
        private set
    private var onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit = {}

    fun update(
        preferences: PlaybackControlLayoutPreferences,
        onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit
    ) {
        this.preferences = preferences
        this.onPreferencesChange = onPreferencesChange
    }

    fun openPlacement() {
        selectedSettingState.value = PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT
    }

    fun openNowPlayingSize() {
        selectedSettingState.value = PlaybackControlLayoutSetting.NOW_PLAYING_SIZE
    }

    fun openLyricsSize() {
        selectedSettingState.value = PlaybackControlLayoutSetting.LYRICS_SIZE
    }

    fun dismiss() {
        selectedSettingState.value = null
    }

    fun selectPlacement(placement: NowPlayingControlPlacement) {
        onPreferencesChange(preferences.copy(nowPlayingPlacement = placement))
        dismiss()
    }

    fun selectNowPlayingSize(size: PlaybackControlSize) {
        onPreferencesChange(preferences.copy(nowPlayingSize = size))
        dismiss()
    }

    fun selectLyricsSize(size: PlaybackControlSize) {
        onPreferencesChange(preferences.copy(lyricsSize = size))
        dismiss()
    }

    fun placementAction(placement: NowPlayingControlPlacement): () -> Unit = {
        selectPlacement(placement)
    }

    fun nowPlayingSizeAction(size: PlaybackControlSize): () -> Unit = {
        selectNowPlayingSize(size)
    }

    fun lyricsSizeAction(size: PlaybackControlSize): () -> Unit = {
        selectLyricsSize(size)
    }
}

@Composable
internal fun PlaybackControlLayoutSettings(
    preferences: PlaybackControlLayoutPreferences,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val owner = remember { PlaybackControlLayoutOwner() }
    owner.update(preferences, onPreferencesChange)

    PlaybackControlLayoutListItem(
        targetId = "setting:nowplaying_control_placement",
        icon = Icons.Outlined.DashboardCustomize,
        title = stringResource(R.string.settings_nowplaying_control_placement),
        description = stringResource(R.string.settings_nowplaying_control_placement_desc),
        value = nowPlayingControlPlacementLabel(preferences.nowPlayingPlacement),
        onClick = owner::openPlacement,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    PlaybackControlLayoutListItem(
        targetId = "setting:nowplaying_control_size",
        icon = Icons.Outlined.AspectRatio,
        title = stringResource(R.string.settings_nowplaying_control_size),
        description = stringResource(R.string.settings_nowplaying_control_size_desc),
        value = playbackControlSizeLabel(preferences.nowPlayingSize),
        onClick = owner::openNowPlayingSize,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    PlaybackControlLayoutListItem(
        targetId = "setting:lyrics_control_size",
        icon = Icons.Outlined.TextFields,
        title = stringResource(R.string.settings_lyrics_control_size),
        description = stringResource(R.string.settings_lyrics_control_size_desc),
        value = playbackControlSizeLabel(preferences.lyricsSize),
        onClick = owner::openLyricsSize,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    PlacementDialogHost(owner)
    NowPlayingSizeDialogHost(owner)
    LyricsSizeDialogHost(owner)
}

@Composable
private fun PlacementDialogHost(owner: PlaybackControlLayoutOwner) {
    if (owner.selectedSetting == PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT) {
        PlacementDialog(owner)
    }
}

@Composable
private fun PlacementDialog(owner: PlaybackControlLayoutOwner) {
    MiuixSettingsDialog(
        onDismissRequest = owner::dismiss,
        title = { Text(stringResource(R.string.settings_nowplaying_control_placement)) },
        text = {
            Column {
                val dockSubtitle = stringResource(
                    R.string.settings_nowplaying_toolbar_dock_disabled_by_control_position
                )
                PlacementChoiceRow(owner, NowPlayingControlPlacement.LOWER, null)
                PlacementChoiceRow(owner, NowPlayingControlPlacement.BOTTOM, dockSubtitle)
                PlacementChoiceRow(owner, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS, dockSubtitle)
            }
        },
        confirmButton = { ControlLayoutDialogCloseButton(owner::dismiss) }
    )
}

@Composable
private fun PlacementChoiceRow(
    owner: PlaybackControlLayoutOwner,
    placement: NowPlayingControlPlacement,
    subtitle: String?
) {
    MiuixSettingsChoiceRow(
        title = nowPlayingControlPlacementLabel(placement),
        subtitle = subtitle,
        selected = placement == owner.preferences.nowPlayingPlacement,
        onClick = owner.placementAction(placement)
    )
}

@Composable
private fun NowPlayingSizeDialogHost(owner: PlaybackControlLayoutOwner) {
    if (owner.selectedSetting == PlaybackControlLayoutSetting.NOW_PLAYING_SIZE) {
        NowPlayingSizeDialog(owner)
    }
}

@Composable
private fun NowPlayingSizeDialog(owner: PlaybackControlLayoutOwner) {
    MiuixSettingsDialog(
        onDismissRequest = owner::dismiss,
        title = { Text(stringResource(R.string.settings_nowplaying_control_size)) },
        text = {
            Column {
                PlaybackControlSize.entries.forEach { size ->
                    ControlSizeChoiceRow(
                        size = size,
                        selected = size == owner.preferences.nowPlayingSize,
                        onClick = owner.nowPlayingSizeAction(size)
                    )
                }
            }
        },
        confirmButton = { ControlLayoutDialogCloseButton(owner::dismiss) }
    )
}

@Composable
private fun LyricsSizeDialogHost(owner: PlaybackControlLayoutOwner) {
    if (owner.selectedSetting == PlaybackControlLayoutSetting.LYRICS_SIZE) {
        LyricsSizeDialog(owner)
    }
}

@Composable
private fun LyricsSizeDialog(owner: PlaybackControlLayoutOwner) {
    MiuixSettingsDialog(
        onDismissRequest = owner::dismiss,
        title = { Text(stringResource(R.string.settings_lyrics_control_size)) },
        text = {
            Column {
                PlaybackControlSize.entries.forEach { size ->
                    ControlSizeChoiceRow(
                        size = size,
                        selected = size == owner.preferences.lyricsSize,
                        onClick = owner.lyricsSizeAction(size)
                    )
                }
            }
        },
        confirmButton = { ControlLayoutDialogCloseButton(owner::dismiss) }
    )
}

@Composable
private fun ControlSizeChoiceRow(
    size: PlaybackControlSize,
    selected: Boolean,
    onClick: () -> Unit
) {
    MiuixSettingsChoiceRow(
        title = playbackControlSizeLabel(size),
        selected = selected,
        onClick = onClick
    )
}

@Composable
private fun ControlLayoutDialogCloseButton(onDismiss: () -> Unit) {
    MiuixSettingsTextButton(
        onClick = onDismiss,
        text = { Text(stringResource(R.string.action_close)) }
    )
}

@Composable
private fun PlaybackControlLayoutListItem(
    targetId: String,
    icon: ImageVector,
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun nowPlayingControlPlacementLabel(
    placement: NowPlayingControlPlacement
): String = stringResource(nowPlayingControlPlacementLabelRes(placement))

@Composable
private fun playbackControlSizeLabel(size: PlaybackControlSize): String =
    stringResource(playbackControlSizeLabelRes(size))

internal fun nowPlayingControlPlacementLabelRes(placement: NowPlayingControlPlacement): Int =
    when (placement) {
        NowPlayingControlPlacement.LOWER -> R.string.settings_nowplaying_control_placement_lower
        NowPlayingControlPlacement.BOTTOM -> R.string.settings_nowplaying_control_placement_bottom
        NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS ->
            R.string.settings_nowplaying_control_placement_bottom_with_progress
    }

internal fun playbackControlSizeLabelRes(size: PlaybackControlSize): Int = when (size) {
    PlaybackControlSize.SMALL -> R.string.settings_playback_control_size_small
    PlaybackControlSize.MEDIUM -> R.string.settings_playback_control_size_medium
    PlaybackControlSize.LARGE -> R.string.settings_playback_control_size_large
}
