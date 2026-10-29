package moe.ouom.neriplayer.ui.screen.nowplaying.actions

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
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
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
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ThumbDown as ThumbDownOutlined
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
    onDismissSheet: (() -> Unit) -> Unit
) {
    val scrollState = rememberScrollState()
    val neteaseExclusiveFeatureAvailable = isNeteaseExclusiveFeatureAvailable(originalSong)
    Column(
        Modifier
            .bottomSheetScrollGuard { scrollState.value == 0 }
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        MoreOptionsGroup {
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
        MoreOptionsGroup {
            if (PlayerManager.isBiliTrack(originalSong)) {
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
            ShareSongAction(
                song = originalSong,
                queue = queue,
                snackbarHostState = snackbarHostState,
                onDismissSheet = onDismissSheet
            )
            PlaybackStatsAction(originalSong)
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
        }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private val MoreRowShape = ContinuousRoundedCornerShape(14.dp)
private val MoreGlyphShape = ContinuousRoundedCornerShape(8.dp)
private val MoreGroupShape = ContinuousRoundedCornerShape(20.dp)

/** Clean sheets group related actions into cards; Material keeps its flat list. */
@Composable
private fun MoreOptionsGroup(content: @Composable ColumnScope.() -> Unit) {
    if (!NeriDesign.isClean) {
        Column(content = content)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(MoreGroupShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(4.dp),
        content = content
    )
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
    if (!NeriDesign.isClean) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, null)
                }
            },
            supportingContent = supporting ?: subtitle?.let { text -> { Text(text) } },
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MoreRowShape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(MoreGlyphShape)
                .background(hue),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (supporting != null) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) { supporting() }
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}
