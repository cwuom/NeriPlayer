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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.platform.comments.resolveCommentSource
import moe.ouom.neriplayer.ui.component.comment.CommentSheet
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongEncyclopediaUiState

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
    var showCommentSheet by remember(songKey) { mutableStateOf(false) }
    LaunchedEffect(commentSource) {
        if (commentSource == null) showCommentSheet = false
    }

    SongEncyclopediaStateContent(
        state = state.forSongKey(songKey),
        songKey = songKey,
        song = song,
        commentSource = commentSource,
        onCommentClick = { showCommentSheet = true },
        onEnterPlaylist = onEnterPlaylist,
        onEnterArtist = onEnterArtist,
        onPlaySongs = onPlaySongs,
        onRetry = { viewModel.retryEncyclopediaLoad(song) },
        onDismiss = onDismiss
    )
    if (showCommentSheet && commentSource != null) {
        CommentSheet(
            source = commentSource,
            offlineMode = offlineMode,
            onDismissRequest = { showCommentSheet = false }
        )
    }
}

@Composable
private fun SongEncyclopediaStateContent(
    state: SongEncyclopediaUiState,
    songKey: String,
    song: SongItem,
    commentSource: CommentSource?,
    onCommentClick: () -> Unit,
    onEnterPlaylist: (PlaylistSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit,
    onPlaySongs: (List<SongItem>, Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetScrollGuard()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(SONG_ENCYCLOPEDIA_TEST_TAG)
    ) {
        SongEncyclopediaHeader(onDismiss)
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
                        onCommentClick = onCommentClick,
                        onEnterPlaylist = onEnterPlaylist,
                        onEnterArtist = onEnterArtist,
                        onPlaySongs = onPlaySongs
                    )
                } else {                    SongEncyclopediaLoading()
                }
            }
        }
    }
}

@Composable
private fun SongEncyclopediaHeader(onDismiss: () -> Unit) {
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
    onCommentClick: () -> Unit,
    onEnterPlaylist: (PlaylistSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit,
    onPlaySongs: (List<SongItem>, Int) -> Unit
) {
    SongEncyclopediaSongDetailSection(data)
    SongEncyclopediaListeningSection(song)
    data.artist?.let { artist ->
        SongEncyclopediaArtistSection(
            artist = artist,
            onOpenArtist = { onEnterArtist(NeteaseArtistSummary(artist.artistId, artist.name)) }
        )
    }
    // 模块六 评论入口：解析不到评论来源（非 B 站/网易云音源）时整段隐藏。
    if (commentSource != null) {
        SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_comments)
        SongEncyclopediaCommentEntry(onClick = onCommentClick)
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
    SongEncyclopediaSourceNote()
}

/** 模块六 评论入口行：副标题复用「评论」标题资源，点开直接进 [CommentSheet]。 */
@Composable
private fun SongEncyclopediaCommentEntry(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.comment_title)) },
        supportingContent = {
            Text(
                text = stringResource(CoreCommonR.string.encyclopedia_section_comments),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .encyclopediaClickable(onClick)
    )
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

/** 供相邻文件复用的「展开全部 / 收起」入口。 */
@Composable
internal fun SongEncyclopediaExpandAction(expanded: Boolean, onToggle: () -> Unit) {
    Text(
        text = stringResource(
            if (expanded) CoreCommonR.string.encyclopedia_action_collapse
            else CoreCommonR.string.encyclopedia_action_expand
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp)
    )
}

@Composable
internal fun SongEncyclopediaSubsectionTitle(titleRes: Int) {
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
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
