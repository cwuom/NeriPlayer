package moe.ouom.neriplayer.ui.screen

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.isLocalSong
import moe.ouom.neriplayer.data.displayAlbum
import moe.ouom.neriplayer.data.displayArtist
import moe.ouom.neriplayer.data.displayCoverUrl
import moe.ouom.neriplayer.data.displayName
import moe.ouom.neriplayer.data.sameIdentityAs
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback
import java.util.Date
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    val repo = AppContainer.playHistoryRepo
    val history by repo.historyFlow.collectAsState()

    // 可播放的 SongItem 列表
    val baseSongs: List<SongItem> = remember(history) {
        history.map {
            SongItem(
                id = it.id, name = it.name, artist = it.artist, albumId = it.albumId,
                album = it.album,
                durationMs = it.durationMs,
                coverUrl = it.coverUrl,
                mediaUri = it.localFilePath ?: it.mediaUri,
                matchedLyric = it.matchedLyric,
                originalName = it.originalName,
                originalArtist = it.originalArtist,
                localFileName = it.localFileName,
                localFilePath = it.localFilePath
            )
        }
    }

    val context = LocalContext.current
    rememberCoroutineScope()
    val mini = LocalMiniPlayerHeight.current

    // 搜索
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val displayedSongs = remember(baseSongs, query) {
        if (query.isBlank()) baseSongs
        else baseSongs.filter {
            it.name.contains(query, ignoreCase = true) ||
                    (it.localFileName?.contains(query, ignoreCase = true) == true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.displayAlbum(context).contains(query, ignoreCase = true)
        }
    }

    // 多选
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }
    fun toggleSelect(key: String) {
        val updated = if (selectedKeys.contains(key)) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
        if (selectionMode && updated.isEmpty()) {
            exitSelection()
        } else {
            selectedKeys = updated
        }
    }

    // 当前播放态
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val downloadedSongs by GlobalDownloadManager.downloadedSongs.collectAsState()
    val downloadedSongFilePaths = remember(downloadedSongs) { downloadedSongs.map { it.filePath } }

    // 清空确认
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.recent_title)) },
                    navigationIcon = {
                        HapticIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        HapticIconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) query = ""
                        }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search)) }

                        // 全部播放
                        HapticIconButton(
                            onClick = {
                                if (displayedSongs.isNotEmpty()) onSongClick(displayedSongs, 0)
                            },
                            enabled = displayedSongs.isNotEmpty()
                        ) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_play_all)) }

                        // 随机播放
                        HapticIconButton(
                            onClick = {
                                if (displayedSongs.isNotEmpty()) {
                                    val idx = Random.nextInt(displayedSongs.size)
                                    onSongClick(displayedSongs, idx)
                                }
                            },
                            enabled = displayedSongs.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = stringResource(R.string.cd_shuffle))
                        }

                        // 清空
                        HapticIconButton(
                            onClick = { if (history.isNotEmpty()) showClearConfirm = true },
                            enabled = history.isNotEmpty()
                        ) { Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.cd_clear)) }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                val allSelected = selectedKeys.size == displayedSongs.size && displayedSongs.isNotEmpty()
                TopAppBar(
                    title = { Text(stringResource(R.string.common_selected_count, selectedKeys.size)) },
                    navigationIcon = {
                        HapticIconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_exit_select))
                        }
                    },
                    actions = {
                        // 全选/取消全选
                        HapticTextButton(onClick = {
                            selectedKeys = if (allSelected) emptySet()
                            else displayedSongs.map { it.stableKey() }.toSet()
                        }) { Text(if (allSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) }

                        Spacer(Modifier.width(8.dp))

                        HapticIconButton(
                            enabled = selectedKeys.isNotEmpty(),
                            onClick = {
                                val selectedSongs =
                                    displayedSongs.filter { it.stableKey() in selectedKeys }
                                if (selectedSongs.isNotEmpty()) {
                                    pendingDeleteSongs = selectedSongs
                                    showDeleteConfirm = true
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(R.string.action_delete)
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // 播放所选
                        HapticTextButton(
                            enabled = selectedKeys.isNotEmpty(),
                            onClick = {
                                val list = displayedSongs.filter { it.stableKey() in selectedKeys }
                                if (list.isNotEmpty()) {
                                    onSongClick(list, 0)
                                    exitSelection()
                                }
                            }
                        ) { Text(stringResource(R.string.player_play_selected)) }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.recent_no_history)) }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(showSearch && !selectionMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_recent)) },
                    singleLine = true
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + mini)
            ) {
                itemsIndexed(
                    items = displayedSongs,
                    key = { index, s -> s.id to index }
                ) { index, song ->
                    RecentRowRich(
                        index = index + 1,
                        song = song,
                        downloadedSongFilePaths = downloadedSongFilePaths,
                        selectionMode = selectionMode,
                        selected = song.stableKey() in selectedKeys,
                        isCurrentSong = currentSong?.sameIdentityAs(song) == true,
                        isPlaying = currentSong?.sameIdentityAs(song) == true && isPlaying,
                        onToggleSelect = { toggleSelect(song.stableKey()) },
                        onLongPress = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedKeys = setOf(song.stableKey())
                            } else {
                                toggleSelect(song.stableKey())
                            }
                        },
                        onClick = {
                            context.performHapticFeedback()
                            if (selectionMode) {
                                toggleSelect(song.stableKey())
                            } else {
                                val pos = displayedSongs.indexOfFirst { it.sameIdentityAs(song) }
                                if (pos >= 0) onSongClick(displayedSongs, pos)
                            }
                        },
                        moreMenu = {
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.local_playlist_play_next)) },
                                        onClick = {
                                            PlayerManager.addToQueueNext(song)
                                            showMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.playlist_add_to_end)) },
                                        onClick = {
                                            PlayerManager.addToQueueEnd(song)
                                            showMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // 清空确认弹窗
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.recent_clear)) },
            text = { Text(stringResource(R.string.recent_clear_confirm)) },
            confirmButton = {
                HapticTextButton(onClick = {
                    repo.clear()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 删除确认弹窗（单条/多选共用）
    if (showDeleteConfirm) {
        val deleteCount = pendingDeleteSongs.size
        val deleteMessage = if (deleteCount <= 1) {
            val songName = pendingDeleteSongs.firstOrNull()?.displayName().orEmpty()
            stringResource(R.string.download_delete_confirm, songName)
        } else {
            stringResource(R.string.download_delete_selected_confirm, deleteCount)
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_confirm_delete)) },
            text = { Text(deleteMessage) },
            confirmButton = {
                HapticTextButton(onClick = {
                    if (pendingDeleteSongs.isNotEmpty()) {
                        repo.removeSongs(pendingDeleteSongs)
                        if (selectionMode) {
                            exitSelection()
                        }
                    }
                    pendingDeleteSongs = emptyList()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                HapticTextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 多选优先返回退出
    BackHandler(enabled = selectionMode) { exitSelection() }
}

@Composable
private fun RecentRowRich(
    index: Int,
    song: SongItem,
    downloadedSongFilePaths: List<String>,
    selectionMode: Boolean,
    selected: Boolean,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    moreMenu: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val coverUrl = song.displayCoverUrl(ctx)
    val primaryTitle = remember(song) {
        if (song.isLocalSong()) {
            song.localFileName?.takeIf { it.isNotBlank() } ?: song.displayName()
        } else {
            song.displayName()
        }
    }
    val secondaryText = remember(song) {
        buildList {
            if (song.isLocalSong()) {
                song.displayName()
                    .takeIf { it.isNotBlank() && it != primaryTitle }
                    ?.let(::add)
            }
            song.displayArtist().takeIf { it.isNotBlank() }?.let(::add)
            add(formatDuration(song.durationMs))
        }.joinToString(" · ")
    }
    val rowScale by animateFloatAsState(
        targetValue = if (isCurrentSong) 1.01f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "recent-row-scale"
    )
    val rowShape = RoundedCornerShape(18.dp)
    val rowContainerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
            .fillMaxWidth()
            .clip(rowShape)
            .background(rowContainerColor)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongPress
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号 / 播放指示
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else if (isCurrentSong) {
                PlayingIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    animate = isPlaying
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 封面
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(ctx, coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Spacer(Modifier.size(52.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    primaryTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                // 已下载标志
                if (remember(downloadedSongFilePaths, song) {
                        AudioDownloadManager.getLocalFilePath(ctx, song) != null
                    }) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.DownloadDone,
                        contentDescription = stringResource(R.string.cd_downloaded),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 右侧更多
        moreMenu()
    }
}


/** 播放中指示 */
@Composable
private fun PlayingIndicator(color: Color, animate: Boolean) {
    // 三条不同节奏/相位的无限动画
    val t = rememberInfiniteTransition(label = "playing")
    val flatHeight = 8f
    val transitionSpec: FiniteAnimationSpec<Float> =
        if (animate) snap() else tween(durationMillis = 180, easing = FastOutSlowInEasing)
    val animatedH1 by t.animateFloat(
        initialValue = 6f, targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val animatedH2 by t.animateFloat(
        initialValue = 10f, targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val animatedH3 by t.animateFloat(
        initialValue = 8f, targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing, delayMillis = 90),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val h1 by animateFloatAsState(
        targetValue = if (animate) animatedH1 else flatHeight,
        animationSpec = transitionSpec,
        label = "bar1Hold"
    )
    val h2 by animateFloatAsState(
        targetValue = if (animate) animatedH2 else flatHeight,
        animationSpec = transitionSpec,
        label = "bar2Hold"
    )
    val h3 by animateFloatAsState(
        targetValue = if (animate) animatedH3 else flatHeight,
        animationSpec = transitionSpec,
        label = "bar3Hold"
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(24.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .height(h1.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .width(3.dp)
                .height(h2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .width(3.dp)
                .height(h3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
    }
}


/** 简单时间格式 */
private fun formatDateTime(millis: Long): String {
    val pattern = "yyyy-MM-dd HH:mm"
    return DateFormat.format(pattern, Date(millis)).toString()
}
