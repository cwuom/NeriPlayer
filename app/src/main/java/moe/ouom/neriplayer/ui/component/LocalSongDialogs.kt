package moe.ouom.neriplayer.ui.component

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.LocalMediaDetails
import moe.ouom.neriplayer.data.LocalMediaSupport
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.convertTimestampToDate
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatFileSize

@Composable
fun LocalSongDetailsDialog(
    song: SongItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var details by remember(song) { mutableStateOf<LocalMediaDetails?>(null) }
    var error by remember(song) { mutableStateOf<String?>(null) }

    LaunchedEffect(song) {
        details = null
        error = null
        runCatching { withContext(Dispatchers.IO) { LocalMediaSupport.inspect(context, song) } }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_song_details_title)) },
        text = {
            fun copyPath(path: String) {
                clipboardManager.setClip(ClipEntry(ClipData.newPlainText("text", path)))
                Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    details == null && error == null -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.local_song_details_loading))
                        }
                    }

                    error != null -> {
                        Text(
                            text = stringResource(R.string.local_song_details_failed, error.orEmpty()),
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    details != null -> {
                        DetailsRow(stringResource(R.string.local_song_detail_display_name), details!!.displayName)
                        DetailsRow(stringResource(R.string.local_song_detail_title), details!!.title)
                        DetailsRow(stringResource(R.string.local_song_detail_artist), details!!.artist)
                        DetailsRow(stringResource(R.string.local_song_detail_album), details!!.album)
                        DetailsRow(stringResource(R.string.local_song_detail_album_artist), details!!.albumArtist)
                        DetailsRow(stringResource(R.string.local_song_detail_composer), details!!.composer)
                        DetailsRow(stringResource(R.string.local_song_detail_genre), details!!.genre)
                        DetailsRow(stringResource(R.string.local_song_detail_year), details!!.year?.toString())
                        DetailsRow(stringResource(R.string.local_song_detail_track), details!!.trackNumber?.toString())
                        DetailsRow(stringResource(R.string.local_song_detail_disc), details!!.discNumber?.toString())
                        DetailsRow(stringResource(R.string.local_song_detail_duration), formatDuration(details!!.durationMs))
                        DetailsRow(stringResource(R.string.local_song_detail_extension), details!!.fileExtension)
                        DetailsRow(
                            stringResource(R.string.local_song_detail_bitrate),
                            details!!.bitrateKbps?.let { "$it kbps" }
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_sample_rate),
                            details!!.sampleRateHz?.let { "$it Hz" }
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_channels),
                            details!!.channelCount?.toString()
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_bit_depth),
                            details!!.bitsPerSample?.let { "$it-bit" }
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_size),
                            details!!.sizeBytes?.let(::formatFileSize)
                        )
                        DetailsRow(stringResource(R.string.local_song_detail_mime), details!!.mimeType)
                        DetailsRow(stringResource(R.string.local_song_detail_audio_mime), details!!.audioMimeType)
                        DetailsRow(
                            stringResource(R.string.local_song_detail_modified),
                            details!!.lastModifiedMs?.let(::convertTimestampToDate)
                        )
                        DetailsRow(stringResource(R.string.local_song_detail_cover), details!!.coverSource)
                        DetailsRow(stringResource(R.string.local_song_detail_lyric), details!!.lyricSource)
                        DetailsRow(
                            stringResource(R.string.local_song_detail_has_lyrics),
                            if (details!!.lyricContent.isNullOrBlank()) {
                                stringResource(R.string.local_song_detail_no_lyrics_value)
                            } else {
                                stringResource(R.string.local_song_detail_has_lyrics_value)
                            }
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_path),
                            details!!.filePath,
                            mono = true,
                            onClick = details!!.filePath?.let { path -> { copyPath(path) } }
                        )
                        DetailsRow(
                            stringResource(R.string.local_song_detail_lyric_path),
                            details!!.lyricPath,
                            mono = true,
                            onClick = details!!.lyricPath?.let { path -> { copyPath(path) } }
                        )
                        if (details!!.filePath.isNullOrBlank()) {
                            DetailsRow(stringResource(R.string.local_song_detail_uri), details!!.sourceUri.toString(), mono = true)
                        }
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

@Composable
fun LocalSongSyncConfirmDialog(
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_song_sync_confirm_title)) },
        text = { Text(stringResource(R.string.local_song_sync_confirm_message, actionLabel)) },
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
private fun DetailsRow(
    label: String,
    value: String?,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    if (value.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
            } else {
                MaterialTheme.typography.bodyMedium
            }
        )
    }
}
