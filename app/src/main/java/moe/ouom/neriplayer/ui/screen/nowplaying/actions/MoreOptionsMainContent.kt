package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.ltw.validation.format

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Timer
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.presentation.downloadFailureReasonMessageRes
import moe.ouom.neriplayer.core.download.presentation.formatDownloadTransferProgress
import moe.ouom.neriplayer.core.download.presentation.isDownloadTaskCancellable
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.isServerSong
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.component.download.downloadStageLabelResource
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload
import moe.ouom.neriplayer.ui.screen.nowplaying.shouldHideDownloadActionForSong
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.album.isNeteaseAlbumNavigationSource
import moe.ouom.neriplayer.ui.viewmodel.album.neteaseAlbumDisplayName
import moe.ouom.neriplayer.ui.viewmodel.album.resolveNeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl
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
    translationFontScale: Float,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    isDismissing: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenSearch: () -> Unit,
    onOpenEditInfo: () -> Unit,
    onOpenLyricBehavior: () -> Unit,
    onOpenFontSize: () -> Unit,
    onOpenBiliVideoSkip: () -> Unit,
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
            translationFontScale = translationFontScale,
            onOpenLyricBehavior = onOpenLyricBehavior,
            onOpenFontSize = onOpenFontSize,
            onEnterAlbum = onEnterAlbum,
            snackbarHostState = snackbarHostState
        )
        if (PlayerManager.isBiliTrack(originalSong)) {
            ListItem(
                headlineContent = { Text(stringResource(CoreCommonR.string.bili_video_skip_manage)) },
                leadingContent = { Icon(Icons.Outlined.SkipNext, null) },
                modifier = Modifier.clickable(onClick = onOpenBiliVideoSkip)
            )
        }
        ShareSongAction(
            song = originalSong,
            queue = queue,
            snackbarHostState = snackbarHostState,
            onDismissSheet = onDismissSheet
        )
        PlaybackStatsAction(originalSong)
        ListItem(
            headlineContent = { Text(stringResource(CoreCommonR.string.listen_together_title)) },
            supportingContent = if (originalSong.isServerSong()) {
                { Text(stringResource(CoreCommonR.string.server_listen_together_unavailable)) }
            } else null,
            leadingContent = { Icon(Icons.Outlined.Headphones, null) },
            colors = if (originalSong.isServerSong()) ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                leadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            ) else ListItemDefaults.colors(),
            modifier = if (originalSong.isServerSong()) Modifier.semantics { disabled() }
                else Modifier.clickable(onClick = onOpenListenTogether)
        )
    }
}

@Composable
private fun MetadataAndPlaybackActions(
    audioInfo: PlaybackAudioInfo?,
    isDismissing: Boolean,
    onOpenSearch: () -> Unit,
    onOpenEditInfo: () -> Unit,
    onShowQualitySwitch: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.music_get_info)) },
        leadingContent = { Icon(Icons.Outlined.Info, null) },
        modifier = Modifier.clickable(
            enabled = !isDismissing,
            onClick = onOpenSearch
        )
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.music_edit_info)) },
        leadingContent = { Icon(Icons.Outlined.Edit, null) },
        modifier = Modifier.clickable(onClick = onOpenEditInfo)
    )
    if (audioInfo?.qualityOptions.orEmpty().size > 1) {
        ListItem(
            headlineContent = { Text(stringResource(CoreCommonR.string.nowplaying_quality_switch_title)) },
            leadingContent = { Icon(Icons.Outlined.MusicNote, null) },
            supportingContent = audioInfo?.qualityLabel
                ?.takeIf { it.isNotBlank() }
                ?.let { label -> { Text(label) } },
            modifier = Modifier.clickable(onClick = onShowQualitySwitch)
        )
    }
}

@Composable
private fun DownloadOrDetailsAction(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    isLocalSong: Boolean,
    onShowSongDetails: () -> Unit
) {
    if (song.isServerSong()) {
        ListItem(
            headlineContent = { Text(stringResource(CoreCommonR.string.download_to_local)) },
            supportingContent = { Text(stringResource(CoreCommonR.string.server_download_unavailable)) },
            leadingContent = { Icon(Icons.Outlined.Download, null) },
            colors = ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                leadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            ),
            modifier = Modifier.semantics { disabled() }
        )
        return
    }
    if (isLocalSong) {
        ListItem(
            headlineContent = { Text(stringResource(CoreCommonR.string.local_song_open_details)) },
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
        DownloadStatus.WAITING_NETWORK -> CoreCommonR.string.download_cancel_download
        DownloadStatus.FAILED -> CoreCommonR.string.action_retry
        else -> CoreCommonR.string.download_to_local
    }
}

@Composable
internal fun DownloadProgressContent(task: DownloadTask?) {
    val progress = task?.progress
    when {
        progress?.stage?.let(::downloadStageLabelResource) != null -> {
            val stageLabel = requireNotNull(
                progress.stage.let(::downloadStageLabelResource)
            )
            Column {
                Text(stringResource(stageLabel))
                Text(
                    formatDownloadTransferProgress(
                        progress = progress,
                        showSpeed = false
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

        progress?.stage == DownloadStage.FINALIZING -> {
            Column {
                Text(stringResource(CoreCommonR.string.download_finalizing))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        task?.status == DownloadStatus.WAITING_NETWORK -> {
            Column {
                Text(stringResource(CoreCommonR.string.download_waiting_network_recovery))
                progress?.let { retainedProgress ->
                    Text(
                        formatDownloadTransferProgress(
                            progress = retainedProgress,
                            showSpeed = false
                        )
                    )
                    if (retainedProgress.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = {
                                (
                                    retainedProgress.bytesRead.toFloat() /
                                        retainedProgress.totalBytes.toFloat()
                                    ).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        progress != null -> {
            Column {
                Text(
                    formatDownloadTransferProgress(
                        progress = progress,
                        showSpeed = task.status == DownloadStatus.DOWNLOADING
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
        task?.status == DownloadStatus.FAILED -> Text(
            stringResource(
                task?.let(::downloadFailureReasonMessageRes) ?: CoreCommonR.string.download_failed
            )
        )
    }
}

@Composable
private fun LyricsAndAlbumActions(
    song: SongItem,
    lyricFontScale: Float,
    translationFontScale: Float,
    onOpenLyricBehavior: () -> Unit,
    onOpenFontSize: () -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.lyrics_adjust_behavior)) },
        leadingContent = { Icon(Icons.Outlined.Timer, null) },
        modifier = Modifier.clickable(onClick = onOpenLyricBehavior)
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.lyrics_font_size)) },
        leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
        supportingContent = {
            Text(
                stringResource(
                    CoreCommonR.string.settings_lyrics_font_scale_pair_value,
                    (lyricFontScale * 100).roundToInt(),
                    (translationFontScale * 100).roundToInt()
                )
            )
        },
        modifier = Modifier.clickable(onClick = onOpenFontSize)
    )
    if (!isNeteaseAlbumNavigationSource(song)) return

    val albumName = neteaseAlbumDisplayName(song)
    val composeResources = LocalResources.current
    var albumResolveRequest by remember(song) { mutableIntStateOf(0) }
    var resolvingAlbum by remember(song) { mutableStateOf(false) }

    LaunchedEffect(song, albumResolveRequest) {
        if (albumResolveRequest == 0) return@LaunchedEffect
        val album = try {
            resolveNeteaseAlbum(song)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e("MoreOptionsMainContent", "解析网易云专辑失败", error)
            null
        }
        resolvingAlbum = false
        if (album != null) {
            onEnterAlbum(album)
        } else {
            snackbarHostState.showNeriSnackbar(composeResources.getString(CoreCommonR.string.music_get_detail_failed))
        }
    }

    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.music_view_album, albumName)) },
        leadingContent = {
            if (resolvingAlbum) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Outlined.LibraryMusic, null)
            }
        },
        modifier = Modifier.clickable(enabled = !resolvingAlbum) {
            resolvingAlbum = true
            albumResolveRequest++
        }
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
    val composeResources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.action_share)) },
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
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(CoreCommonR.string.local_song_share_failed)
                        )
                    }
                }
                return@clickable
            }

            val shareUrl = buildRemoteSongShareUrl(song, queue)
            val shareText = if (shareUrl.isNullOrBlank()) {
                "${song.displayName()} - ${song.displayArtist()}"
            } else {
                composeResources.getString(
                    CoreCommonR.string.nowplaying_share_song,
                    song.displayName(),
                    song.displayArtist(),
                    shareUrl,
                )
            }
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
            AppContainer.playbackStatsRepo.takeIf { it.awaitInitialized() }
                ?.getStatForTrack(songKey)
        }
    }
    val resolvedTrackStat = trackStat ?: return
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.stats_title)) },
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
        title = { Text(stringResource(CoreCommonR.string.stats_title)) },
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsCard(CoreCommonR.string.stats_song_first_played, firstPlayedText)
                StatsCard(CoreCommonR.string.stats_song_total_listen, totalListenText)
                StatsCard(
                    labelRes = CoreCommonR.string.stats_song_play_count_label,
                    value = pluralStringResource(
                        CoreCommonR.plurals.stats_play_count_value,
                        resolvedTrackStat.playCount,
                        resolvedTrackStat.playCount
                    )
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { showDialog = false }) {
                Text(stringResource(CoreCommonR.string.action_close))
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
