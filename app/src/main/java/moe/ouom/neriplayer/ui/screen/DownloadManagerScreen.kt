package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.DownloadManagerViewModel
import moe.ouom.neriplayer.util.formatDate
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: DownloadManagerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                DownloadManagerViewModel(app)
            }
        }
    )

    LaunchedEffect(Unit) {
        viewModel.refreshDownloadedSongs()
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSongs by remember { mutableStateOf(setOf<Long>()) }

    // State for deletion confirmation dialogs
    var showSingleDeleteDialog by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<DownloadedSong?>(null) }
    var showMultiDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Column {
                    Text(
                        "下载管理",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "管理音乐下载和本地文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            actions = {
                if (selectionMode) {
                    // 多选模式下的操作按钮
                    Text(
                        text = "已选 ${selectedSongs.size} 首",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    // 全选/取消全选按钮
                    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
                    val allSelected = selectedSongs.size == downloadedSongs.size && downloadedSongs.isNotEmpty()
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            if (allSelected) {
                                selectedSongs = emptySet()
                            } else {
                                selectedSongs = downloadedSongs.map { it.id }.toSet()
                            }
                        }
                    ) {
                        Icon(
                            if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (allSelected) "取消全选" else "全选"
                        )
                    }
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            if (selectedSongs.isNotEmpty()) {
                                showMultiDeleteDialog = true // Show confirmation dialog
                            }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除所选")
                    }
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            selectedSongs = emptySet()
                            selectionMode = false
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "退出多选")
                    }
                } else {
                    // 正常模式下的操作按钮
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            viewModel.refreshDownloadedSongs()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            selectionMode = true
                        }
                    ) {
                        Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "多选")
                    }
                }
            }
        )

        // 下载统计信息
        val downloadedSongs by viewModel.downloadedSongs.collectAsState()
        val totalSize = remember(downloadedSongs) {
            downloadedSongs.sumOf { it.fileSize }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = downloadedSongs.size.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "已下载歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(
                    modifier = Modifier
                        .height(32.dp)
                        .width(1.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatFileSize(totalSize),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "占用空间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索已下载的歌曲...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        fun exitSelectionMode() {
            selectionMode = false
            selectedSongs = emptySet()
        }

        // 多选优先退出
        BackHandler(enabled = selectionMode) { exitSelectionMode() }

        // 已下载歌曲列表
        DownloadedSongsList(
            viewModel = viewModel,
            searchQuery = searchQuery,
            selectionMode = selectionMode,
            selectedSongs = selectedSongs,
            onSelectionChanged = { selectedSongs = it },
            onSelectionModeChanged = { selectionMode = it },
            onDeleteRequest = { song ->
                songToDelete = song
                showSingleDeleteDialog = true
            }
        )
    }

    // Single song delete confirmation dialog
    if (showSingleDeleteDialog && songToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showSingleDeleteDialog = false
                songToDelete = null
            },
            title = { Text("确认删除") },
            text = { Text("确定要删除歌曲 \"${songToDelete?.name}\" 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        songToDelete?.let { viewModel.deleteDownloadedSong(it) }
                        showSingleDeleteDialog = false
                        songToDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSingleDeleteDialog = false
                        songToDelete = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Multiple songs delete confirmation dialog
    if (showMultiDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showMultiDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedSongs.size} 首歌曲吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val songsToDelete = viewModel.downloadedSongs.value.filter { selectedSongs.contains(it.id) }
                        songsToDelete.forEach { viewModel.deleteDownloadedSong(it) }

                        selectedSongs = emptySet()
                        selectionMode = false
                        showMultiDeleteDialog = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMultiDeleteDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DownloadedSongsList(
    viewModel: DownloadManagerViewModel,
    searchQuery: String,
    selectionMode: Boolean,
    selectedSongs: Set<Long>,
    onSelectionChanged: (Set<Long>) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit,
    onDeleteRequest: (DownloadedSong) -> Unit
) {
    val context = LocalContext.current
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // 过滤搜索结果
    val filteredSongs = remember(downloadedSongs, searchQuery) {
        if (searchQuery.isBlank()) {
            downloadedSongs
        } else {
            downloadedSongs.filter { song ->
                song.name.contains(searchQuery, ignoreCase = true) ||
                        song.artist.contains(searchQuery, ignoreCase = true) ||
                        song.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (filteredSongs.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (searchQuery.isBlank()) "暂无已下载歌曲" else "未找到匹配的歌曲",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (searchQuery.isBlank()) "下载的歌曲会显示在这里" else "尝试使用其他关键词搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp + miniPlayerHeight
                ),
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    DownloadedSongItem(
                        song = song,
                        isSelected = selectedSongs.contains(song.id),
                        selectionMode = selectionMode,
                        onPlay = { viewModel.playDownloadedSong(context, song) },
                        onDelete = { onDeleteRequest(song) },
                        onSelectionChanged = { selected ->
                            if (selected) {
                                onSelectionChanged(selectedSongs + song.id)
                            } else {
                                onSelectionChanged(selectedSongs - song.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                onSelectionModeChanged(true)
                                onSelectionChanged(setOf(song.id))
                            }
                        }
                    )
                }
            }
        }

        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(24.dp)
                    )
            )
        }
    }
}

@Composable
private fun DownloadedSongItem(
    song: DownloadedSong,
    isSelected: Boolean,
    selectionMode: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSelectionChanged: (Boolean) -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            onSelectionChanged(!isSelected)
                        } else {
                            onPlay()
                        }
                    },
                    onLongClick = onLongClick
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选复选框
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionChanged(it) },
                    modifier = Modifier.padding(end = 12.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            // 封面或音乐图标
            if (song.coverPath != null) {
                // 显示封面
                AsyncImage(
                    model = java.io.File(song.coverPath).toURI().toString(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    error = painterResource(id = R.drawable.ic_launcher_foreground)
                )
            } else {
                // 显示默认音乐图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 歌曲信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(song.fileSize)} • ${formatDate(song.downloadTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))


            // 操作按钮
            if (!selectionMode) {
                Row {
                    IconButton(onClick = onPlay) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}