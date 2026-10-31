package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.resolver.netease.refreshCurrentSongAfterBiliBindingChange
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_ALBUM
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_PLAYLIST
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_SONG
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack
import moe.ouom.neriplayer.data.model.bilibili.binding.biliAlbumBindingKey
import moe.ouom.neriplayer.data.model.bilibili.binding.biliPlaylistBindingKey
import moe.ouom.neriplayer.data.model.bilibili.binding.biliSongBindingKey
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.platform.bilibili.binding.matchSongsToPages
import moe.ouom.neriplayer.platform.bilibili.binding.resolveBiliCollectionVideoId
import moe.ouom.neriplayer.platform.bilibili.binding.scoreTrackPage
import moe.ouom.neriplayer.platform.bilibili.binding.toBindingTrack
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.common.R as CoreCommonR

/**
 * 绑定目标：一首歌、一张专辑或一个歌单。
 *
 * [key] 是落库用的稳定标识，[name] 同时作为 B 站搜索框的默认关键字。
 */
data class BiliBindingTarget(
    val type: String,
    val key: String,
    val id: Long,
    val name: String,
    val subtitle: String = ""
)

fun biliBindingTargetForAlbum(albumId: Long, name: String, artist: String = "") =
    BiliBindingTarget(
        type = BILI_BINDING_TARGET_ALBUM,
        key = biliAlbumBindingKey(albumId, name),
        id = albumId,
        name = name,
        subtitle = artist
    )

fun biliBindingTargetForPlaylist(playlistId: Long, name: String) =
    BiliBindingTarget(
        type = BILI_BINDING_TARGET_PLAYLIST,
        key = biliPlaylistBindingKey(playlistId, name),
        id = playlistId,
        name = name
    )

fun biliBindingTargetForSong(song: SongItem) =
    BiliBindingTarget(
        type = BILI_BINDING_TARGET_SONG,
        key = biliSongBindingKey(song.id),
        id = song.id,
        name = song.originalName ?: song.name,
        subtitle = song.artist
    )

/**
 * 单曲 / 专辑 / 歌单 → B 站多 P 视频的绑定面板。
 *
 * 打开即用目标名字搜索 B 站，选中视频后按标题与时长自动配对分 P；
 * 也可以贴 BV 号或分享短链（b23.tv 会自动跟随跳转）。
 */
@Composable
fun BiliCollectionBindingDialog(
    target: BiliBindingTarget,
    songs: List<SongItem>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = remember { AppContainer.biliCollectionBindingRepository }
    val biliClient = remember { AppContainer.biliClient }

    var query by rememberSaveable(target.key) { mutableStateOf(target.name) }
    var linkInput by rememberSaveable(target.key) { mutableStateOf("") }
    var showLinkField by rememberSaveable(target.key) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorResId by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<SearchVideoItem>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var videoInfo by remember { mutableStateOf<VideoBasicInfo?>(null) }
    // songId -> cid
    var assignments by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var hasExistingBinding by remember { mutableStateOf(false) }
    // 已保存的绑定留着，替换时要拿旧记录判断当前播放是否受这次改动影响
    var existingBinding by remember { mutableStateOf<BiliCollectionBinding?>(null) }

    suspend fun applyVideo(info: VideoBasicInfo) {
        videoInfo = info
        val matches = matchSongsToPages(songs, info.pages)
        assignments = matches.associate { it.songId to it.page.cid }
        // 单曲绑定：只有这一个 P 时直接用它，名字对不上也不用让用户再点一次
        val onlySong = songs.singleOrNull()
        if (onlySong != null && assignments.isEmpty()) {
            val usablePages = info.pages.filter { it.cid > 0L }
            if (usablePages.size == 1) {
                assignments = mapOf(onlySong.id to usablePages.first().cid)
            }
        }
    }

    suspend fun persist(info: VideoBasicInfo, tracks: List<BiliCollectionBindingTrack>): Boolean {
        if (tracks.isEmpty()) return false
        val binding = BiliCollectionBinding(
            targetKey = target.key,
            targetType = target.type,
            targetId = target.id,
            targetName = target.name,
            targetSubtitle = target.subtitle,
            bvid = info.bvid,
            avid = info.aid,
            videoTitle = info.title,
            tracks = tracks,
            updatedAt = System.currentTimeMillis()
        )
        val ok = repository.save(binding)
        if (ok) {
            PlayerManager.refreshCurrentSongAfterBiliBindingChange(
                changed = binding,
                previous = existingBinding
            )
        }
        return ok
    }

    /**
     * 单曲绑定：用户点中的视频就是他想要的源，直接挑最贴合的分 P 落库，
     * 不再走「已匹配 x/y」和二次确认。
     */
    suspend fun commitSingleSong(info: VideoBasicInfo): Boolean {
        val song = songs.singleOrNull() ?: return false
        val page = info.pages
            .filter { it.cid > 0L }
            .maxByOrNull { scoreTrackPage(song, it) }
            ?: return false
        return persist(info, listOf(page.toBindingTrack(song)))
    }

    LaunchedEffect(target.key) {
        val existing = repository.get(target.key) ?: return@LaunchedEffect
        hasExistingBinding = true
        existingBinding = existing
        searching = true
        runCatching { biliClient.getVideoBasicInfoByBvid(existing.bvid) }
            .getOrNull()
            ?.let { info ->
                videoInfo = info
                assignments = existing.tracks
                    .filter { track -> info.pages.any { it.cid == track.cid } }
                    .associate { it.songId to it.cid }
            }
        searching = false
    }

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        searching = true
        errorResId = null
        searched = false
        scope.launch {
            val page = runCatching { biliClient.searchVideos(keyword) }.getOrNull()
            searching = false
            searched = true
            results = page?.items.orEmpty().take(10)
            if (results.isEmpty()) {
                errorResId = CoreCommonR.string.bili_binding_search_empty
            }
        }
    }

    fun pickVideo(item: SearchVideoItem) {
        searching = true
        errorResId = null
        scope.launch {
            val info = runCatching { biliClient.getVideoBasicInfoByBvid(item.bvid) }.getOrNull()
            if (info == null) {
                searching = false
                errorResId = CoreCommonR.string.bili_collection_binding_parse_failed
                return@launch
            }
            results = emptyList()
            if (songs.size == 1) {
                val ok = commitSingleSong(info)
                searching = false
                if (ok) {
                    onDismiss()
                } else {
                    errorResId = CoreCommonR.string.bili_collection_binding_parse_failed
                }
            } else {
                searching = false
                applyVideo(info)
            }
        }
    }

    /** 贴链接 / BV 号 / 分享短链的兜底入口 */
    fun resolveLink() {
        val raw = linkInput.trim()
        if (raw.isEmpty()) return
        searching = true
        errorResId = null
        scope.launch {
            val videoId = resolveBiliCollectionVideoId(raw) { url ->
                biliClient.resolveShareLink(url)
            }
            if (videoId == null) {
                searching = false
                errorResId = CoreCommonR.string.bili_collection_binding_invalid_input
                return@launch
            }
            val info = runCatching {
                if (videoId.bvid.isNotBlank()) {
                    biliClient.getVideoBasicInfoByBvid(videoId.bvid)
                } else {
                    biliClient.getVideoBasicInfoByAvid(videoId.avid)
                }
            }.getOrNull()
            if (info == null) {
                searching = false
                errorResId = CoreCommonR.string.bili_collection_binding_parse_failed
                return@launch
            }
            results = emptyList()
            if (songs.size == 1) {
                val ok = commitSingleSong(info)
                searching = false
                if (ok) {
                    onDismiss()
                } else {
                    errorResId = CoreCommonR.string.bili_collection_binding_parse_failed
                }
            } else {
                searching = false
                applyVideo(info)
            }
        }
    }

    fun saveBinding() {
        val info = videoInfo ?: return
        val tracks = songs.mapNotNull { song ->
            val cid = assignments[song.id] ?: return@mapNotNull null
            val page = info.pages.firstOrNull { it.cid == cid } ?: return@mapNotNull null
            BiliCollectionBindingTrack(
                songId = song.id,
                songName = song.originalName ?: song.name,
                cid = cid,
                page = page.page,
                part = page.part,
                durationSec = page.durationSec
            )
        }
        if (tracks.isEmpty()) {
            errorResId = CoreCommonR.string.bili_collection_binding_save_failed
            return
        }
        saving = true
        scope.launch {
            val ok = persist(info, tracks)
            saving = false
            if (ok) {
                onDismiss()
            } else {
                errorResId = CoreCommonR.string.bili_collection_binding_save_failed
            }
        }
    }

    fun removeBinding() {
        saving = true
        scope.launch {
            val removed = repository.get(target.key)
            repository.remove(target.key)
            saving = false
            PlayerManager.refreshCurrentSongAfterBiliBindingChange(previous = removed)
            onDismiss()
        }
    }

    val busy = searching || saving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(bindingTitleRes(target.type)),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.bili_binding_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(CoreCommonR.string.bili_binding_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = ::runSearch, enabled = !busy && query.isNotBlank()) {
                        Text(stringResource(CoreCommonR.string.bili_binding_search))
                    }
                    TextButton(
                        onClick = { showLinkField = !showLinkField },
                        enabled = !busy
                    ) {
                        Text(stringResource(CoreCommonR.string.bili_binding_use_link))
                    }
                    if (hasExistingBinding) {
                        TextButton(onClick = ::removeBinding, enabled = !saving) {
                            Text(stringResource(CoreCommonR.string.bili_collection_binding_unbind))
                        }
                    }
                }

                if (showLinkField) {
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(stringResource(CoreCommonR.string.bili_collection_binding_input_hint))
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    TextButton(onClick = ::resolveLink, enabled = !busy && linkInput.isNotBlank()) {
                        Text(stringResource(CoreCommonR.string.bili_collection_binding_parse))
                    }
                }

                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(CoreCommonR.string.bili_collection_binding_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                errorResId?.let { messageRes ->
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (results.isNotEmpty()) {
                    Text(
                        text = stringResource(CoreCommonR.string.bili_binding_result_count, results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    results.forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pickVideo(item) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = item.titlePlain,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.author} · ${formatBiliDuration(item.durationSec)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val info = videoInfo
                if (info != null) {
                    HorizontalDivider()
                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            CoreCommonR.string.bili_collection_binding_matched_format,
                            assignments.size,
                            songs.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (info.pages.isEmpty()) {
                        Text(
                            text = stringResource(CoreCommonR.string.bili_collection_binding_no_pages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    songs.forEach { song ->
                        val selectedCid = assignments[song.id]
                        val selectedPage = info.pages.firstOrNull { it.cid == selectedCid }
                        BindingSongRow(
                            songName = song.name,
                            pageLabel = selectedPage?.let { page -> "P${page.page} · ${page.part}" },
                            pages = info.pages,
                            onSelect = { page ->
                                assignments = if (page == null) {
                                    assignments - song.id
                                } else {
                                    // 一个分 P 只能归一首歌，否则保存时会被去重静默丢掉一条
                                    assignments.filterValues { cid -> cid != page.cid } + (song.id to page.cid)
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::saveBinding,
                enabled = videoInfo != null && assignments.isNotEmpty() && !busy
            ) {
                Text(stringResource(CoreCommonR.string.bili_collection_binding_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.bili_collection_binding_cancel))
            }
        }
    )
}

private fun bindingTitleRes(type: String): Int = when (type) {
    BILI_BINDING_TARGET_SONG -> CoreCommonR.string.bili_binding_title_song
    BILI_BINDING_TARGET_PLAYLIST -> CoreCommonR.string.bili_binding_title_playlist
    else -> CoreCommonR.string.bili_binding_title_album
}

private fun formatBiliDuration(durationSec: Int): String {
    if (durationSec <= 0) return "--:--"
    val minutes = durationSec / 60
    val seconds = durationSec % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun BindingSongRow(
    songName: String,
    pageLabel: String?,
    pages: List<VideoPage>,
    onSelect: (VideoPage?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = songName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = pageLabel ?: stringResource(CoreCommonR.string.bili_collection_binding_unmatched),
                style = MaterialTheme.typography.bodySmall,
                color = if (pageLabel == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.bili_collection_binding_clear_match)) },
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            pages.forEach { page ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "P${page.page} · ${page.part}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(page)
                    }
                )
            }
        }
    }
}
