package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.ltw.validation.format

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown as ThumbDownFilled
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ThumbDown as ThumbDownOutlined
import moe.ouom.neriplayer.ui.component.overlay.ScrollableDialogColumn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.BoxScope
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.viewmodel.nowplaying.NeteaseCollectionFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatScorer
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.component.download.downloadStageLabelResource
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload
import moe.ouom.neriplayer.ui.screen.nowplaying.isNeteaseExclusiveFeatureAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.shouldHideDownloadActionForSong
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.nowplaying.NowPlayingNeteasePlaylistOwner
import moe.ouom.neriplayer.ui.viewmodel.nowplaying.canCollectToNeteasePlaylist
import moe.ouom.neriplayer.ui.component.playlist.NeteaseRemotePlaylistPickerDialog
import moe.ouom.neriplayer.ui.viewmodel.album.isNeteaseAlbumNavigationSource
import moe.ouom.neriplayer.ui.viewmodel.album.neteaseAlbumDisplayName
import moe.ouom.neriplayer.ui.viewmodel.album.resolveNeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import moe.ouom.neriplayer.ui.feedback.startActivityOrShowUnavailable
import moe.ouom.neriplayer.R as AppR

/** 播放页更多菜单。可用的歌曲操作取决于当前音源。 */
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
    onOpenBiliCcSubtitle: () -> Unit,
    onOpenListenTogether: () -> Unit,
    onOpenEncyclopedia: () -> Unit,
    onShowSongDetails: () -> Unit,
    onShowQualitySwitch: () -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    onDismissSheet: (() -> Unit) -> Unit,
    offlineMode: Boolean
) {
    val scrollState = rememberScrollState()
    val neteaseExclusiveFeatureAvailable = isNeteaseExclusiveFeatureAvailable(originalSong)
    Column(
        Modifier
            .bottomSheetScrollGuard { scrollState.value == 0 }
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        // 常用操作两列平铺, 其余设置类入口按主题分组成列表
        MoreOptionsTileGrid {
            DownloadOrDetailsAction(
                viewModel = viewModel,
                song = originalSong,
                isLocalSong = isLocalSong,
                onShowSongDetails = onShowSongDetails
            )
            ShareSongAction(
                song = originalSong,
                queue = queue,
                snackbarHostState = snackbarHostState,
                onDismissSheet = onDismissSheet
            )
            NeteasePlaylistCollectionAction(
                owner = viewModel.neteasePlaylistCollection,
                song = originalSong,
                audioInfo = currentPlaybackAudioInfo,
                offlineMode = offlineMode,
                isDismissing = isDismissing,
                onSubmitted = { onDismissSheet {} }
            )
            if (neteaseExclusiveFeatureAvailable) {
                DislikeSongAction(originalSong)
                MoreOptionsItem(
                    title = stringResource(AppR.string.encyclopedia_title),
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    hue = MoreHueViolet,
                    onClick = onOpenEncyclopedia
                )
            }
            MoreOptionsItem(
                title = stringResource(CoreCommonR.string.listen_together_title),
                icon = Icons.Outlined.Headphones,
                hue = MoreHueIndigo,
                onClick = onOpenListenTogether
            )
            PlaybackStatsAction(originalSong)
        }
        MoreOptionsGroup {
            MetadataAndPlaybackActions(
                audioInfo = currentPlaybackAudioInfo,
                isDismissing = isDismissing,
                onOpenSearch = onOpenSearch,
                onOpenEditInfo = onOpenEditInfo,
                onShowQualitySwitch = onShowQualitySwitch
            )
            NeteaseAlbumAction(
                song = originalSong,
                onEnterAlbum = onEnterAlbum,
                snackbarHostState = snackbarHostState
            )
        }
        MoreOptionsGroup {
            LyricsActions(
                lyricFontScale = lyricFontScale,
                translationFontScale = translationFontScale,
                onOpenLyricBehavior = onOpenLyricBehavior,
                onOpenFontSize = onOpenFontSize
            )
        }
        if (PlayerManager.isBiliTrack(originalSong)) {
            MoreOptionsGroup {
                MoreOptionsItem(
                    title = stringResource(CoreCommonR.string.bili_video_skip_manage),
                    icon = Icons.Outlined.SkipNext,
                    hue = MoreHuePink,
                    onClick = onOpenBiliVideoSkip
                )
                MoreOptionsItem(
                    title = stringResource(AppR.string.bili_cc_subtitle_manage),
                    icon = Icons.Outlined.Subtitles,
                    hue = MoreHuePink,
                    onClick = onOpenBiliCcSubtitle
                )
            }
        }
    }
}

/** 两个界面共享提示状态。菜单可见时由菜单内的 Snackbar 显示消息。 */
@Composable
internal fun BoxScope.NeteaseCollectionFeedbackHost(
    feedback: NeteaseCollectionFeedback,
    host: SnackbarHostState,
    menuOpen: Boolean
) {
    val resources = LocalResources.current
    LaunchedEffect(feedback, host, resources) {
        feedback.display { host.showNeriSnackbar(it.resolve(resources)) }
    }
    if (!menuOpen) NeriOverlaySnackbarHost(host)
}

/** 选择器只在提交被接受时收起更多菜单。 */
@Composable
internal fun NeteasePlaylistCollectionAction(
    owner: NowPlayingNeteasePlaylistOwner,
    song: SongItem,
    audioInfo: PlaybackAudioInfo?,
    offlineMode: Boolean,
    isDismissing: Boolean,
    onSubmitted: () -> Unit
) {
    val state by owner.state.collectAsStateWithLifecycle()
    LaunchedEffect(owner, song, audioInfo) { owner.updatePlayback(song, audioInfo) }
    DisposableEffect(owner) { onDispose { owner.dismiss() } }
    if (!canCollectToNeteasePlaylist(song, audioInfo)) return
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.nowplaying_netease_playlist_collect),
        icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
        hue = MoreHuePink,
        enabled = !isDismissing && !state.submitting,
        busy = state.submitting,
        onClick = { owner.open(song, audioInfo, offlineMode) }
    )
    if (state.song != null) {
        NeteaseRemotePlaylistPickerDialog(
            playlists = state.playlists,
            loading = state.loading,
            errorMessage = state.error,
            onPlaylistClick = { playlist ->
                if (owner.select(playlist, song, audioInfo, offlineMode)) onSubmitted()
            },
            onDismissRequest = owner::dismiss
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
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.music_get_info),
        icon = Icons.Outlined.Info,
        hue = MoreHueBlue,
        enabled = !isDismissing,
        onClick = onOpenSearch
    )
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.music_edit_info),
        icon = Icons.Outlined.Edit,
        hue = MoreHueOrange,
        onClick = onOpenEditInfo
    )
    if (audioInfo?.qualityOptions.orEmpty().size > 1) {
        MoreOptionsItem(
            title = stringResource(CoreCommonR.string.nowplaying_quality_switch_title),
            icon = Icons.Outlined.MusicNote,
            hue = MoreHueRed,
            subtitle = audioInfo?.qualityLabel?.takeIf { it.isNotBlank() },
            onClick = onShowQualitySwitch
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
    if (isLocalSong) {
        MoreOptionsItem(
            title = stringResource(CoreCommonR.string.local_song_open_details),
            icon = Icons.Outlined.Info,
            hue = MoreHueTeal,
            onClick = onShowSongDetails
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
    MoreOptionsItem(
        title = stringResource(downloadActionLabel(currentTask)),
        icon = Icons.Outlined.Download,
        hue = MoreHueGreen,
        enabled = canClick,
        supporting = if (currentTask != null) {
            { DownloadProgressContent(currentTask) }
        } else {
            null
        },
        onClick = {
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
private fun LyricsActions(
    lyricFontScale: Float,
    translationFontScale: Float,
    onOpenLyricBehavior: () -> Unit,
    onOpenFontSize: () -> Unit
) {
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.lyrics_adjust_behavior),
        icon = Icons.Outlined.Timer,
        hue = MoreHueViolet,
        onClick = onOpenLyricBehavior
    )
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.lyrics_font_size),
        icon = Icons.Outlined.FormatSize,
        hue = MoreHueMint,
        subtitle = stringResource(
            CoreCommonR.string.settings_lyrics_font_scale_pair_value,
            (lyricFontScale * 100).roundToInt(),
            (translationFontScale * 100).roundToInt()
        ),
        onClick = onOpenFontSize
    )
}

@Composable
private fun NeteaseAlbumAction(
    song: SongItem,
    onEnterAlbum: (AlbumSummary) -> Unit,
    snackbarHostState: SnackbarHostState
) {
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

    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.music_view_album, albumName),
        icon = Icons.Outlined.LibraryMusic,
        hue = MoreHuePurple,
        enabled = !resolvingAlbum,
        busy = resolvingAlbum,
        onClick = {
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
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.action_share),
        icon = Icons.Outlined.Share,
        hue = MoreHueBlue,
        onClick = share@{
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
                return@share
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
            onDismissSheet { context.startActivityOrShowUnavailable(shareIntent) }
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
    MoreOptionsItem(
        title = stringResource(AppR.string.stats_title),
        icon = Icons.Outlined.BarChart,
        hue = MoreHueOrange,
        onClick = { showDialog = true }
    )
    if (!showDialog) return

    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val dateFormat = remember(locale) { SimpleDateFormat("yyyy/MM/dd HH:mm", locale) }
    val firstPlayedText = remember(resolvedTrackStat.firstPlayedAt, dateFormat) {
        dateFormat.format(Date(resolvedTrackStat.firstPlayedAt))
    }
    val totalSeconds = resolvedTrackStat.totalListenMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val totalListenText = when {
        hours > 0 -> stringResource(AppR.string.encyclopedia_total_listen_hours, hours, minutes)
        minutes > 0 -> stringResource(AppR.string.encyclopedia_total_listen_minutes, minutes)
        else -> stringResource(AppR.string.encyclopedia_total_listen_seconds, totalSeconds)
    }
    AlertDialog(
        onDismissRequest = { showDialog = false },
        icon = { Icon(Icons.Outlined.BarChart, null) },
        title = { Text(stringResource(AppR.string.stats_title)) },
        shape = neriRoundedShape(28.dp),
        text = {
            ScrollableDialogColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsCard(AppR.string.stats_song_first_played, firstPlayedText)
                StatsCard(AppR.string.stats_song_total_listen, totalListenText)
                StatsCard(
                    labelRes = AppR.string.stats_song_play_count_label,
                    value = pluralStringResource(
                        AppR.plurals.stats_play_count_value,
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

/**
 * 「不喜欢」菜单项。
 *
 * 数据只存在本地（网易云没有「给单曲打不喜欢」的通用接口），标记后：
 * - 本地降级推荐：评分被压到最低（[HeartbeatScorer.NOT_LIKED_PENALTY]）；
 * - 官方推荐：命中名单时丢弃并重新请求，不改官方顺序与权重。
 * 菜单项点一次加入、再点一次移出，不需要单独的管理页。
 */
@Composable
private fun DislikeSongAction(song: SongItem) {
    val songKey = remember(song) { HeartbeatScorer.identityKey(song) }
    val dislikedKeys by AppContainer.dislikedSongRepo.dislikedKeysFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val isDisliked = songKey in dislikedKeys
    val scope = rememberCoroutineScope()
    MoreOptionsItem(
        title = stringResource(
            if (isDisliked) {
                CoreCommonR.string.heartbeat_dislike_marked
            } else {
                CoreCommonR.string.heartbeat_dislike_action
            }
        ),
        icon = if (isDisliked) Icons.Filled.ThumbDownFilled else Icons.Outlined.ThumbDownOutlined,
        hue = if (isDisliked) MoreHueRed else MoreHueOrange,
        onClick = {
            scope.launch { AppContainer.dislikedSongRepo.toggle(songKey) }
        }
    )
}

@Composable
private fun StatsCard(labelRes: Int, value: String) {
    Card(
        shape = neriRoundedShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (NeriDesign.isClean) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        )
    ) {
        ListItem(
            colors = if (NeriDesign.isClean) ListItemDefaults.colors(containerColor = Color.Transparent)
                else ListItemDefaults.colors(),
            headlineContent = { Text(stringResource(labelRes)) },
            supportingContent = { Text(value) }
        )
    }
}

private val MoreHueBlue = Color(0xFF0A84FF)
private val MoreHueOrange = Color(0xFFFF9F0A)
private val MoreHueRed = Color(0xFFFF453A)
private val MoreHueGreen = Color(0xFF30D158)
private val MoreHueTeal = Color(0xFF30B0C7)
private val MoreHuePurple = Color(0xFFBF5AF2)
private val MoreHueViolet = Color(0xFF7D7AFF)
private val MoreHueMint = Color(0xFF00C7BE)
private val MoreHuePink = Color(0xFFFF375F)
private val MoreHueIndigo = Color(0xFF5E5CE6)
private val MoreRowShape = RoundedCornerShape(14.dp)
private val MoreGlyphShape = RoundedCornerShape(8.dp)
private val MoreGroupShape = RoundedCornerShape(20.dp)

private val MoreTileShapeMaterial = RoundedCornerShape(20.dp)
private val MoreTileShapeClean = RoundedCornerShape(16.dp)
private val MoreGroupShapeMaterial = RoundedCornerShape(24.dp)

/** 平铺网格内的条目改画成磁贴, 由所在 FlowRow 提供等分权重 */
private val LocalMoreOptionsTileScope = staticCompositionLocalOf<FlowRowScope?> { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreOptionsTileGrid(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompositionLocalProvider(LocalMoreOptionsTileScope provides this) { content() }
    }
}

/** 两种风格都把相关入口收进一块连续的卡片, 卡片内不画分隔线 */
@Composable
private fun MoreOptionsGroup(content: @Composable ColumnScope.() -> Unit) {
    val clean = NeriDesign.isClean
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(if (clean) MoreGroupShape else MoreGroupShapeMaterial)
            .background(moreOptionsSurfaceColor())
            .padding(4.dp),
        content = content
    )
}

@Composable
private fun moreOptionsSurfaceColor(): Color =
    if (NeriDesign.isClean) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

@Composable
private fun MoreOptionsGlyph(icon: ImageVector, hue: Color, busy: Boolean) {
    val clean = NeriDesign.isClean
    val container = if (clean) hue else MaterialTheme.colorScheme.secondaryContainer
    val content = if (clean) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = Modifier
            .size(if (clean) 30.dp else 40.dp)
            .clip(if (clean) MoreGlyphShape else CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (clean) 16.dp else 20.dp),
                strokeWidth = 2.dp,
                color = content
            )
        } else {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (clean) 18.dp else 20.dp))
        }
    }
}

@Composable
private fun MoreOptionsItem(
    title: String,
    icon: ImageVector,
    hue: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    subtitle: String? = null,
    supporting: (@Composable () -> Unit)? = null
) {
    val tileScope = LocalMoreOptionsTileScope.current
    if (tileScope != null) {
        MoreOptionsTile(
            title = title,
            icon = icon,
            hue = hue,
            onClick = onClick,
            enabled = enabled,
            busy = busy,
            subtitle = subtitle,
            supporting = supporting,
            modifier = with(tileScope) { Modifier.weight(1f) }
        )
        return
    }
    val clean = NeriDesign.isClean
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(if (clean) MoreRowShape else MoreTileShapeMaterial)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = if (clean) 52.dp else 60.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoreOptionsGlyph(icon, hue, busy)
        Spacer(Modifier.width(14.dp))
        MoreOptionsTexts(title, subtitle, supporting, Modifier.weight(1f))
        if (clean) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MoreOptionsTile(
    title: String,
    icon: ImageVector,
    hue: Color,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    subtitle: String?,
    supporting: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    val clean = NeriDesign.isClean
    Row(
        modifier = modifier
            .clip(if (clean) MoreTileShapeClean else MoreTileShapeMaterial)
            .background(moreOptionsSurfaceColor())
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = if (clean) 60.dp else 68.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoreOptionsGlyph(icon, hue, busy)
        Spacer(Modifier.width(if (clean) 10.dp else 12.dp))
        MoreOptionsTexts(title, subtitle, supporting, Modifier.weight(1f), maxTitleLines = 2)
    }
}

@Composable
private fun MoreOptionsTexts(
    title: String,
    subtitle: String?,
    supporting: (@Composable () -> Unit)?,
    modifier: Modifier,
    maxTitleLines: Int = Int.MAX_VALUE
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            title,
            style = if (maxTitleLines == Int.MAX_VALUE) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (supporting != null) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) { supporting() }
            }
        }
    }
}
