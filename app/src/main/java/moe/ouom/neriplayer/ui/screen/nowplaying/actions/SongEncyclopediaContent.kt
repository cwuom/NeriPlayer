package moe.ouom.neriplayer.ui.screen.nowplaying.actions
/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaContent
 * Created: 2025/10/16
 */
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.platform.comments.resolveCommentSource
import moe.ouom.neriplayer.ui.component.sheet.HalfExpandableSheetPanel
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongEncyclopediaUiState
import androidx.compose.ui.Alignment
internal const val SONG_ENCYCLOPEDIA_TEST_TAG = "song_encyclopedia_content"
/**
 * 歌曲百科二级页。
 *
 * 状态由 [NowPlayingViewModel.encyclopediaState] 提供，且始终按当前歌曲 key 校验，
 * 快速切歌时上一首的结果不会渲染到新歌上。
 */
@Composable
internal fun SongEncyclopediaContent(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    onDismiss: () -> Unit,
    onEnterPlaylist: (PlaylistSummary) -> Unit = {},
    onEnterArtist: (NeteaseArtistSummary) -> Unit = {},
    onPlaySongs: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onOpenComments: (CommentSource) -> Unit = {},
    onCloseSheet: () -> Unit = onDismiss,
    offlineMode: Boolean = false
) {
    val state by viewModel.encyclopediaState.collectAsStateWithLifecycle()
    val songKey = song.encyclopediaSongKey()
    LaunchedEffect(songKey) {
        viewModel.loadEncyclopedia(song)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelEncyclopediaLoad() }
    }
    val commentSource = remember(song) { resolveCommentSource(song) }
    SongEncyclopediaStateContent(
        state = state.forSongKey(songKey),
        songKey = songKey,
        song = song,
        commentSource = commentSource,
        offlineMode = offlineMode,
        // 交给上层关闭百科/更多面板后再打开原生评论面板，避免在百科之上再叠一层评论弹层。
        onCommentClick = { commentSource?.let(onOpenComments) },
        onEnterPlaylist = onEnterPlaylist,
        onEnterArtist = onEnterArtist,
        onPlaySongs = onPlaySongs,
        onRetry = { viewModel.retryEncyclopediaLoad(song) },
        onDismiss = onDismiss,
        onCloseSheet = onCloseSheet
    )
}
@Composable
private fun SongEncyclopediaStateContent(
    state: SongEncyclopediaUiState,
    songKey: String,
    song: SongItem,
    commentSource: CommentSource?,
    offlineMode: Boolean,
    onCommentClick: () -> Unit,
    onEnterPlaylist: (PlaylistSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit,
    onPlaySongs: (List<SongItem>, Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCloseSheet: () -> Unit
) {
    // 半屏 / 全屏 / 关闭三档由公共组件 HalfExpandableSheetPanel 提供，
    // 与评论面板共用同一套拖拽与吸附实现，避免两处各写一遍。
    HalfExpandableSheetPanel(
        onDismiss = onCloseSheet,
        panelTestTag = SONG_ENCYCLOPEDIA_TEST_TAG,
        header = {
            SongEncyclopediaHeader(
                onDismiss = onDismiss,
                landscape = landscape,
                isFullScreen = isFullScreen,
                onToggleFullScreen = toggleFullScreen
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .bottomSheetScrollGuard { false }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (state) {
                SongEncyclopediaUiState.Idle,
                SongEncyclopediaUiState.Loading -> SongEncyclopediaLoading()
                SongEncyclopediaUiState.NotSupported -> SongEncyclopediaMessage(
                    CoreCommonR.string.encyclopedia_not_supported
                )
                SongEncyclopediaUiState.Empty -> SongEncyclopediaMessage(
                    CoreCommonR.string.encyclopedia_empty
                )
                is SongEncyclopediaUiState.Failed -> SongEncyclopediaFailure(onRetry)
                is SongEncyclopediaUiState.Success -> {
                    // 双保险：即使状态泄漏，也不渲染不属于当前歌曲的数据。
                    if (state.data.songKey == songKey) {
                        SongEncyclopediaBody(
                            data = state.data,
                            song = song,
                            commentSource = commentSource,
                            offlineMode = offlineMode,
                            onCommentClick = onCommentClick,
                            onEnterPlaylist = onEnterPlaylist,
                            onEnterArtist = onEnterArtist,
                            onPlaySongs = onPlaySongs
                        )
                    } else {
                        SongEncyclopediaLoading()
                    }
                }
            }
        }
    }
}
@Composable
private fun SongEncyclopediaHeader(
    onDismiss: () -> Unit,
    landscape: Boolean,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(CoreCommonR.string.action_back)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(CoreCommonR.string.encyclopedia_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        if (!landscape) {
            IconButton(onClick = onToggleFullScreen) {
                Icon(
                    imageVector = if (isFullScreen) {
                        Icons.Filled.FullscreenExit
                    } else {
                        Icons.Filled.Fullscreen
                    },
                    contentDescription = stringResource(
                        if (isFullScreen) {
                            CoreCommonR.string.encyclopedia_action_collapse
                        } else {
                            CoreCommonR.string.encyclopedia_action_expand
                        }
                    )
                )
            }
        }
    }
}
@Composable
private fun SongEncyclopediaLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(CoreCommonR.string.encyclopedia_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
private fun SongEncyclopediaMessage(messageRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable
private fun SongEncyclopediaFailure(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(CoreCommonR.string.encyclopedia_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(CoreCommonR.string.encyclopedia_retry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(8.dp)
        )
    }
}
/** 模块三歌曲详情 + 模块二听歌记录 + 模块五艺人 + 模块六评论 + 模块七/八。 */
@Composable
private fun SongEncyclopediaBody(
    data: SongEncyclopedia,
    song: SongItem,
    commentSource: CommentSource?,
    offlineMode: Boolean,
    onCommentClick: () -> Unit,
    onEnterPlaylist: (PlaylistSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit,
    onPlaySongs: (List<SongItem>, Int) -> Unit
) {
    // 每个模块自带卡片与标题栏，这里只负责在模块之间留出统一间距。
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SongEncyclopediaSectionSpacing)
    ) {
        SongEncyclopediaSongDetailSection(data)
        SongEncyclopediaListeningSection(song)
        data.artist?.let { artist ->
            SongEncyclopediaArtistSection(
                artist = artist,
                onOpenArtist = { onEnterArtist(NeteaseArtistSummary(artist.artistId, artist.name)) }
            )
        }
        if (commentSource?.platform == CommentPlatform.NETEASE) {
            SongEncyclopediaCommentPreview(
                source = commentSource,
                offlineMode = offlineMode,
                onOpenAll = onCommentClick
            )
        }
        SongEncyclopediaSimilarSongsSection(
            similarSongs = data.similarSongs,
            onPlaySong = { similar ->
                val queue = buildEncyclopediaSimilarQueue(data.similarSongs)
                val index = queue.indexOfFirst { it.id == similar.songId }
                if (index >= 0) onPlaySongs(queue, index)
            },
            onPlayAll = {
                val queue = buildEncyclopediaSimilarQueue(data.similarSongs)
                if (queue.isNotEmpty()) onPlaySongs(queue, 0)
            }
        )
        SongEncyclopediaRelatedPlaylistsSection(
            playlists = data.relatedPlaylists,
            onOpenPlaylist = { playlist ->
                onEnterPlaylist(
                    PlaylistSummary(
                        id = playlist.playlistId,
                        name = playlist.name,
                        picUrl = playlist.coverUrl.orEmpty(),
                        playCount = playlist.playCount ?: 0L,
                        trackCount = playlist.trackCount ?: 0
                    )
                )
            }
        )
        SongEncyclopediaLegacySections(data)
    }
    SongEncyclopediaSourceNote()
}
@Composable
private fun SongEncyclopediaSourceNote() {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(CoreCommonR.string.encyclopedia_source_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
}
/** 供相邻文件复用的空态文案。 */
@Composable
internal fun SongEncyclopediaEmptyHint(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
internal fun Modifier.encyclopediaClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
/**
 * 模块七 播放队列构造：把相似歌曲转成可播放的 [SongItem]。
 *
 * 只使用接口真实给出的 id / 歌名 / 歌手 / 封面 / 专辑 / 时长，缺失字段一律留空，
 * 不使用任何推断值。专辑字段按本项目网易云歌曲的惯例带上 [PlayerManager.NETEASE_SOURCE_TAG]。
 */
internal fun buildEncyclopediaSimilarQueue(
    similarSongs: List<EncyclopediaSimilarSong>
): List<SongItem> {
    return similarSongs.mapNotNull { similar ->
        if (similar.songId <= 0L || similar.name.isBlank()) return@mapNotNull null
        SongItem(
            id = similar.songId,
            name = similar.name,
            artist = similar.artist,
            album = "${PlayerManager.NETEASE_SOURCE_TAG}${similar.albumName.orEmpty()}",
            albumId = 0L,
            durationMs = similar.durationMs ?: 0L,
            coverUrl = similar.coverUrl,
            originalCoverUrl = similar.coverUrl,
            channelId = "netease",
            audioId = similar.songId.toString()
        )
    }
}
/** 发行日期只显示到「年月日」，且使用数据源给出的毫秒时间戳。 */
internal fun formatEncyclopediaDate(epochMs: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(Date(epochMs))
}
/** 播放量按「万」取整展示，接口给 0 时也如实显示 0。 */
internal fun formatEncyclopediaPlayCount(playCount: Long): String {
    return if (playCount >= 10_000L) {
        "${playCount / 10_000L}万"
    } else {
        playCount.toString()
    }
}