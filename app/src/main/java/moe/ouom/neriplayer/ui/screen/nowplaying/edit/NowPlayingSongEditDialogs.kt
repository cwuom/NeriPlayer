package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSongLocalMetadataWriteBackConfirmDialog(
    isSaving: Boolean,
    onWriteToLocal: () -> Unit,
    onSaveInAppOnly: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.local_song_metadata_write_confirm_title)) },
        text = { Text(stringResource(R.string.local_song_metadata_write_confirm_message)) },
        confirmButton = {
            HapticTextButton(
                enabled = !isSaving,
                onClick = onWriteToLocal
            ) {
                Text(stringResource(R.string.local_song_metadata_write_confirm_write))
            }
        },
        dismissButton = {
            EditSongWriteBackDismissActions(!isSaving, onSaveInAppOnly, onCancel)
        }
    )
}

@Composable
internal fun EditSongLyricsSourceChoiceDialog(
    loading: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onChooseSidecar: () -> Unit,
    onChooseEmbedded: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_lyrics_source_choice_title)) },
        text = { Text(stringResource(R.string.local_lyrics_source_choice_message)) },
        confirmButton = {
            HapticTextButton(enabled = enabled, onClick = onChooseSidecar) {
                Text(stringResource(R.string.local_lyrics_source_sidecar))
            }
        },
        dismissButton = {
            EditSongEmbeddedLyricsChoiceButton(loading, enabled, onChooseEmbedded)
        }
    )
}

@Composable
private fun EditSongEmbeddedLyricsChoiceButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    HapticTextButton(enabled = canChooseEmbeddedLyricsSource(loading, enabled), onClick = onClick) {
        EditSongEmbeddedLyricsChoiceLabel(loading)
    }
}

internal fun canChooseEmbeddedLyricsSource(loading: Boolean, enabled: Boolean): Boolean =
    enabled && !loading

@Composable
private fun EditSongEmbeddedLyricsChoiceLabel(loading: Boolean) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp), strokeWidth = 2.dp
        )
        Spacer(Modifier.width(6.dp))
    }
    Text(stringResource(R.string.local_lyrics_source_embedded))
}

@Composable
internal fun EditSongFillLyricsWriteBackDialog(
    enabled: Boolean,
    onWriteToLocal: () -> Unit,
    onSaveInAppOnly: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSaveInAppOnly,
        title = { Text(stringResource(R.string.local_song_metadata_write_confirm_title)) },
        text = { Text(stringResource(R.string.local_lyrics_fill_write_confirm_message)) },
        confirmButton = {
            HapticTextButton(enabled = enabled, onClick = onWriteToLocal) {
                Text(stringResource(R.string.local_song_metadata_write_confirm_write))
            }
        },
        dismissButton = {
            HapticTextButton(enabled = enabled, onClick = onSaveInAppOnly) {
                Text(stringResource(R.string.local_song_metadata_write_confirm_app_only))
            }
        }
    )
}

@Composable
private fun EditSongWriteBackDismissActions(
    enabled: Boolean,
    onSaveInAppOnly: () -> Unit,
    onCancel: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HapticTextButton(enabled = enabled, onClick = onSaveInAppOnly) {
            Text(stringResource(R.string.local_song_metadata_write_confirm_app_only))
        }
        HapticTextButton(enabled = enabled, onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillOptionsDialog(
    songResult: SongSearchInfo,
    onDismiss: () -> Unit,
    onConfirm: (fillCover: Boolean, fillTitle: Boolean, fillArtist: Boolean, fillLyrics: Boolean) -> Unit,
    enabled: Boolean = true
) {
    var fillCover by remember { mutableStateOf(true) }
    var fillTitle by remember { mutableStateOf(true) }
    var fillArtist by remember { mutableStateOf(true) }
    var fillLyrics by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.music_auto_fill_select)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 显示选中的歌曲信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = songResult.songName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = songResult.singer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 填充选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { fillCover = !fillCover }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fillCover,
                        onCheckedChange = { fillCover = it },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_cover))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { fillTitle = !fillTitle }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fillTitle,
                        onCheckedChange = { fillTitle = it },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_title))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { fillArtist = !fillArtist }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fillArtist,
                        onCheckedChange = { fillArtist = it },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_artist))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { fillLyrics = !fillLyrics }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fillLyrics,
                        onCheckedChange = { fillLyrics = it },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_lyrics))
                }
            }
        },
        confirmButton = {
            HapticTextButton(
                onClick = { onConfirm(fillCover, fillTitle, fillArtist, fillLyrics) },
                enabled = enabled
            ) {
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
