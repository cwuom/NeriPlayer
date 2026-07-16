package moe.ouom.neriplayer.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun MoreOptionsMainContent(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    isLocalSong: Boolean,
    lyricFontScale: Float,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    isDismissing: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenSearch: () -> Unit,
    onOpenEditInfo: () -> Unit,
    onOpenPlaybackSound: () -> Unit,
    onOpenLyricBehavior: () -> Unit,
    onOpenFontSize: () -> Unit,
    onOpenListenTogether: () -> Unit,
    onShowSongDetails: () -> Unit,
    onShowQualitySwitch: () -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    onDismissSheet: (() -> Unit) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .bottomSheetScrollGuard { scrollState.value == 0 }
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        MetadataAndPlaybackActions(
            audioInfo = currentPlaybackAudioInfo,
            isDismissing = isDismissing,
            onOpenSearch = onOpenSearch,
            onOpenEditInfo = onOpenEditInfo,
            onOpenPlaybackSound = onOpenPlaybackSound,
            onShowQualitySwitch = onShowQualitySwitch
        )
        DownloadOrDetailsAction(
            viewModel = viewModel,
            song = originalSong,
            isLocalSong = isLocalSong,
            onShowSongDetails = onShowSongDetails
        )
        LyricsAndAlbumActions(
            song = originalSong,
            lyricFontScale = lyricFontScale,
            onOpenLyricBehavior = onOpenLyricBehavior,
            onOpenFontSize = onOpenFontSize,
            onEnterAlbum = onEnterAlbum
        )
        ShareSongAction(
            song = originalSong,
            queue = queue,
            snackbarHostState = snackbarHostState,
            onDismissSheet = onDismissSheet
        )
        PlaybackStatsAction(originalSong)
        ListItem(
            headlineContent = { Text(stringResource(R.string.listen_together_title)) },
            leadingContent = { Icon(Icons.Outlined.Headphones, null) },
            modifier = Modifier.clickable(onClick = onOpenListenTogether)
        )
    }
}

@Composable
private fun MetadataAndPlaybackActions(
    audioInfo: PlaybackAudioInfo?,
    isDismissing: Boolean,
    onOpenSearch: () -> Unit,
    onOpenEditInfo: () -> Unit,
    onOpenPlaybackSound: () -> Unit,
    onShowQualitySwitch: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.music_get_info)) },
        leadingContent = { Icon(Icons.Outlined.Info, null) },
        modifier = Modifier.clickable(
            enabled = !isDismissing,
            onClick = onOpenSearch
        )
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.music_edit_info)) },
        leadingContent = { Icon(Icons.Outlined.Edit, null) },
        modifier = Modifier.clickable(onClick = onOpenEditInfo)
    )
    if (audioInfo?.qualityOptions.orEmpty().size > 1) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.nowplaying_quality_switch_title)) },
            leadingContent = { Icon(Icons.Outlined.MusicNote, null) },
            supportingContent = audioInfo?.qualityLabel
                ?.takeIf { it.isNotBlank() }
                ?.let { label -> { Text(label) } },
            modifier = Modifier.clickable(onClick = onShowQualitySwitch)
        )
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.nowplaying_audio_effects_title)) },
        leadingContent = { Icon(Icons.Outlined.Tune, null) },
        supportingContent = { Text(stringResource(R.string.nowplaying_audio_effects_desc)) },
        modifier = Modifier.clickable(onClick = onOpenPlaybackSound)
    )
}

@Composable
private fun DownloadOrDetailsAction(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    isLocalSong: Boolean,
    onShowSongDetails: () -> Unit
) {
    if (isLocalSong) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.local_song_open_details)) },
            leadingContent = { Icon(Icons.Outlined.Info, null) },
            modifier = Modifier.clickable(onClick = onShowSongDetails)
        )
        return
    }

    val context = LocalContext.current
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion
        .collectAsStateWithLifecycle()
    val hasLocalDownload = remember(downloadPresenceVersion, song) {
        hasCachedLocalDownload(song)
    }
    val downloadSongKey = remember(song) { song.stableKey() }
    val currentTaskFlow = remember(downloadSongKey) {
        GlobalDownloadManager.downloadTasks
            .map { tasks -> tasks.firstOrNull { it.song.stableKey() == downloadSongKey } }
            .distinctUntilChanged()
    }
    val currentTask by currentTaskFlow.collectAsStateWithLifecycle(initialValue = null)
    if (shouldHideDownloadActionForSong(hasLocalDownload, currentTask)) return

    val canCancel = remember(currentTask) { isDownloadTaskCancellable(currentTask) }
    val status = currentTask?.status
    val canClick = (
        status != DownloadStatus.QUEUED &&
            status != DownloadStatus.DOWNLOADING &&
            status != DownloadStatus.WAITING_NETWORK
        ) || canCancel
    ListItem(
        headlineContent = { Text(stringResource(downloadActionLabel(currentTask))) },
        leadingContent = { Icon(Icons.Outlined.Download, null) },
        supportingContent = { DownloadProgressContent(currentTask) },
        modifier = Modifier.clickable(enabled = canClick) {
            when (currentTask?.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.WAITING_NETWORK,
                DownloadStatus.DOWNLOADING -> viewModel.cancelDownload(downloadSongKey)
                DownloadStatus.CANCELLED -> viewModel.resumeDownload(context, downloadSongKey)
                DownloadStatus.FAILED -> viewModel.retryDownload(context, song)
                else -> viewModel.downloadSong(context, song)
            }
        }
    )
}

private fun downloadActionLabel(task: DownloadTask?): Int {
    return when (task?.status) {
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.WAITING_NETWORK -> R.string.download_cancel_download
        DownloadStatus.FAILED -> R.string.action_retry
        else -> R.string.download_to_local
    }
}

@Composable
private fun DownloadProgressContent(task: DownloadTask?) {
    val progress = task?.progress
    when {
        progress?.stage == AudioDownloadManager.DownloadStage.FINALIZING -> {
            Column {
                Text(stringResource(R.string.download_finalizing))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        progress != null -> {
            Column {
                Text(
                    stringResource(
                        R.string.download_progress_file_label,
                        progress.percentage,
                        progress.fileName
                    )
                )
                if (progress.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = {
                            (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        task?.status == DownloadStatus.FAILED -> Text(stringResource(R.string.download_failed))
        task?.status == DownloadStatus.WAITING_NETWORK -> {
            Text(stringResource(R.string.download_waiting_network_recovery))
        }
    }
}

@Composable
private fun LyricsAndAlbumActions(
    song: SongItem,
    lyricFontScale: Float,
    onOpenLyricBehavior: () -> Unit,
    onOpenFontSize: () -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.lyrics_adjust_behavior)) },
        leadingContent = { Icon(Icons.Outlined.Timer, null) },
        modifier = Modifier.clickable(onClick = onOpenLyricBehavior)
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.lyrics_font_size)) },
        leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
        supportingContent = {
            Text(stringResource(R.string.common_percent_int, (lyricFontScale * 100).roundToInt()))
        },
        modifier = Modifier.clickable(onClick = onOpenFontSize)
    )
    if (!song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG)) return

    val albumName = song.album.removePrefix(PlayerManager.NETEASE_SOURCE_TAG)
    val album = remember(song, albumName) {
        AlbumSummary(
            id = song.albumId,
            name = albumName,
            size = 0,
            picUrl = song.coverUrl.orEmpty()
        )
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.music_view_album, albumName)) },
        leadingContent = { Icon(Icons.Outlined.LibraryMusic, null) },
        modifier = Modifier.clickable { onEnterAlbum(album) }
    )
}

@Composable
private fun ShareSongAction(
    song: SongItem,
    queue: List<SongItem>,
    snackbarHostState: SnackbarHostState,
    onDismissSheet: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    ListItem(
        headlineContent = { Text(stringResource(R.string.action_share)) },
        leadingContent = { Icon(Icons.Outlined.Share, null) },
        modifier = Modifier.clickable {
            if (song.isLocalSong()) {
                coroutineScope.launch {
                    val shared = runCatching {
                        LocalMediaSupport.shareSongFile(context, song)
                    }.getOrDefault(false)
                    if (shared) {
                        onDismissSheet {}
                    } else {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.local_song_share_failed)
                        )
                    }
                }
                return@clickable
            }

            val shareText = context.getString(
                R.string.nowplaying_share_song,
                song.name,
                song.artist,
                buildRemoteSongShareUrl(song, queue)
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            onDismissSheet { context.startActivity(shareIntent) }
        }
    )
}

@Composable
private fun PlaybackStatsAction(song: SongItem) {
    val songKey = remember(song) { song.stableKey() }
    val trackStat by produceState<TrackStat?>(initialValue = null, songKey) {
        value = withContext(Dispatchers.IO) {
            AppContainer.playbackStatsRepo.getStatForTrack(songKey)
        }
    }
    val resolvedTrackStat = trackStat ?: return
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.stats_title)) },
        leadingContent = { Icon(Icons.Outlined.BarChart, null) },
        modifier = Modifier.clickable { showDialog = true }
    )
    if (!showDialog) return

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val firstPlayedText = remember(resolvedTrackStat.firstPlayedAt) {
        dateFormat.format(Date(resolvedTrackStat.firstPlayedAt))
    }
    val totalListenText = remember(resolvedTrackStat.totalListenMs) {
        val totalSeconds = resolvedTrackStat.totalListenMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${totalSeconds}s"
        }
    }
    AlertDialog(
        onDismissRequest = { showDialog = false },
        icon = { Icon(Icons.Outlined.BarChart, null) },
        title = { Text(stringResource(R.string.stats_title)) },
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsCard(R.string.stats_song_first_played, firstPlayedText)
                StatsCard(R.string.stats_song_total_listen, totalListenText)
                StatsCard(
                    labelRes = R.string.stats_song_play_count_label,
                    value = pluralStringResource(
                        R.plurals.stats_play_count_value,
                        resolvedTrackStat.playCount,
                        resolvedTrackStat.playCount
                    )
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { showDialog = false }) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun StatsCard(labelRes: Int, value: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        )
    ) {
        ListItem(
            headlineContent = { Text(stringResource(labelRes)) },
            supportingContent = { Text(value) }
        )
    }
}
