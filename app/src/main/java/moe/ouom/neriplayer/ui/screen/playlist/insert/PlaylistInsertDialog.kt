package moe.ouom.neriplayer.ui.screen.playlist.insert

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassOverscrollBackdrop
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

@Composable
internal fun PlaylistInsertDialog(
    songs: List<SongItem>,
    selectedKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (PlaylistInsertPreview) -> Unit,
    offlineMode: Boolean = false
) {
    val sourceKeys = songs.map { it.stableKey() }
    val selectedSnapshot = selectedKeys.toSet()
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<PlaylistInsertPreview?>(null) }
    var sourceChanged by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val position = resolvePlaylistInsertPosition(input, sourceKeys.size, selectedSnapshot.size)
    val candidate = remember(sourceKeys, selectedSnapshot, position) {
        position?.let { createPlaylistInsertPreview(sourceKeys, selectedSnapshot, it) }
    }
    val currentPreview = preview?.takeIf {
        isPlaylistInsertPreviewCurrent(it, sourceKeys, selectedSnapshot)
    }
    val maxPosition = (sourceKeys.size - selectedSnapshot.size + 1).coerceAtLeast(1)
    val selectionValid = remember(sourceKeys, selectedSnapshot) {
        createPlaylistInsertPreview(sourceKeys, selectedSnapshot, 1) != null
    }
    LaunchedEffect(sourceKeys, selectedSnapshot) {
        if (preview != null && currentPreview == null) {
            preview = null
            sourceChanged = true
        }
    }
    val showPreview: () -> Unit = {
        if (candidate != null) {
            keyboardController?.hide()
            preview = candidate
            sourceChanged = false
        }
    }

    // 弹窗使用独立窗口，回弹位移不能写回背后的歌单头图
    CompositionLocalProvider(LocalAdvancedGlassOverscrollBackdrop provides null) {
        MiuixSettingsDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(CoreCommonR.string.playlist_insert_title)) },
            text = {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                ) {
                    val previewMaxHeight = maxHeight
                    Crossfade(
                        targetState = currentPreview,
                        animationSpec = tween(180),
                        label = "playlist_insert_stage"
                    ) { stagePreview ->
                        if (stagePreview != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(
                                            CoreCommonR.string.playlist_insert_position_summary,
                                            stagePreview.startPosition
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    MiuixSettingsTextButton(onClick = { preview = null }) {
                                        Text(stringResource(CoreCommonR.string.playlist_insert_edit_position))
                                    }
                                }
                                PlaylistInsertPreviewCard(
                                    preview = stagePreview,
                                    songs = songs,
                                    offlineMode = offlineMode,
                                    maxHeight = previewMaxHeight,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                MiuixSettingsDialogContent(verticalSpacing = 10.dp) {
                                    Text(
                                        text = pluralStringResource(
                                            CoreCommonR.plurals.playlist_insert_description,
                                            selectedSnapshot.size,
                                            selectedSnapshot.size
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    MiuixSettingsTextField(
                                        value = input,
                                        onValueChange = {
                                            input = it
                                            preview = null
                                        },
                                        label = {
                                            Text(stringResource(CoreCommonR.string.playlist_insert_position_label))
                                        },
                                        placeholder = {
                                            Text(stringResource(CoreCommonR.string.playlist_insert_position_hint, maxPosition))
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(onDone = { showPreview() })
                                    )
                                    PlaylistInsertSupportText(
                                        selectionValid = selectionValid,
                                        sourceChanged = sourceChanged || (preview != null && currentPreview == null),
                                        invalidInput = input.isNotBlank() && position == null,
                                        maxPosition = maxPosition
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                MiuixSettingsButton(
                    enabled = candidate != null,
                    onClick = {
                        if (currentPreview == null) {
                            showPreview()
                        } else if (isPlaylistInsertPreviewCurrent(currentPreview, sourceKeys, selectedSnapshot)) {
                            onConfirm(currentPreview)
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (currentPreview == null) CoreCommonR.string.playlist_insert_preview_action
                            else CoreCommonR.string.playlist_insert_confirm_action
                        )
                    )
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = onDismiss) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun PlaylistInsertSupportText(
    selectionValid: Boolean,
    sourceChanged: Boolean,
    invalidInput: Boolean,
    maxPosition: Int
) {
    val message = when {
        !selectionValid -> stringResource(CoreCommonR.string.playlist_insert_invalid_selection)
        sourceChanged -> stringResource(CoreCommonR.string.playlist_insert_stale)
        invalidInput -> stringResource(CoreCommonR.string.playlist_insert_invalid_position, maxPosition)
        else -> stringResource(CoreCommonR.string.playlist_insert_position_help, maxPosition)
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (!selectionValid || sourceChanged || invalidInput) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
