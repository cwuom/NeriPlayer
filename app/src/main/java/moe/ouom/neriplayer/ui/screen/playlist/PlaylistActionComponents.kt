package moe.ouom.neriplayer.ui.screen.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

/** overflow menu item for a playlist */
internal data class PlaylistMoreMenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** shared overflow button for playlist detail screens */
@Composable
internal fun PlaylistMoreMenuButton(
    actions: List<PlaylistMoreMenuAction>,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.cd_more_actions)
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        HapticIconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = contentDescription,
                tint = tint
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    enabled = action.enabled,
                    leadingIcon = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

internal data class PlaylistInsertPreviewRow(
    val position: Int,
    val title: String,
    val subtitle: String? = null,
    val isMoved: Boolean = false
)

/** first batch confirmation; the download manager owns the mobile-data warning */
@Composable
internal fun PlaylistDownloadConfirmationDialog(
    songCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (songCount <= 0) return
    DensityScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.playlist_download_confirm_title))
        },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.playlist_download_confirm_message,
                    songCount,
                    songCount
                )
            )
        },
        confirmButton = {
            HapticTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun PlaylistInsertAtDialog(
    itemCount: Int,
    selectedCount: Int,
    previewForPosition: (Int) -> List<PlaylistInsertPreviewRow>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val range = insertionPositionRange(itemCount, selectedCount)
    var rawPosition by remember(itemCount, selectedCount) { mutableStateOf("") }
    var previewMode by remember(itemCount, selectedCount) { mutableStateOf(false) }
    val position = parseOneBasedInsertionPosition(
        raw = rawPosition,
        itemCount = itemCount,
        selectedCount = selectedCount
    )
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (previewMode) {
                        R.string.playlist_insert_at_preview_title
                    } else {
                        R.string.playlist_insert_at_title
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_insert_at_selected,
                        selectedCount,
                        selectedCount
                    )
                )
                MiuixSettingsTextField(
                    value = rawPosition,
                    onValueChange = { rawPosition = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.playlist_insert_at_position)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Text(
                    text = when {
                        range.isEmpty() -> stringResource(R.string.playlist_insert_at_unavailable)
                        position == null && rawPosition.isNotBlank() -> stringResource(
                            R.string.playlist_insert_at_range,
                            range.first,
                            range.last
                        )
                        else -> stringResource(
                            R.string.playlist_insert_at_range,
                            range.first,
                            range.last
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rawPosition.isNotBlank() && position == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (previewMode && position != null) {
                    Text(
                        text = stringResource(R.string.playlist_insert_at_preview_note),
                        style = MaterialTheme.typography.titleSmall
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = previewForPosition(position),
                            key = { row -> row.position }
                        ) { row ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (row.isMoved) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                        alpha = 0.7f
                                    )
                                },
                                tonalElevation = if (row.isMoved) 1.dp else 0.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 8.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.position.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = row.title,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        row.subtitle
                                            ?.takeIf(String::isNotBlank)
                                            ?.let { subtitle ->
                                                Text(
                                                    text = subtitle,
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                    }
                                    if (row.isMoved) {
                                        Text(
                                            text = stringResource(R.string.playlist_insert_at_selected_marker),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                onClick = {
                    if (previewMode) {
                        position?.let(onConfirm)
                    } else if (position != null) {
                        previewMode = true
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
                enabled = position != null
            ) {
                Text(
                    stringResource(
                        if (previewMode) {
                            R.string.playlist_insert_at_confirm_button
                        } else {
                            R.string.playlist_insert_at_preview_button
                        }
                    )
                )
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(
                onClick = {
                    if (previewMode) {
                        previewMode = false
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (previewMode) {
                            R.string.playlist_insert_at_adjust_button
                        } else {
                            R.string.action_cancel
                        }
                    )
                )
            }
        }
    )
}
