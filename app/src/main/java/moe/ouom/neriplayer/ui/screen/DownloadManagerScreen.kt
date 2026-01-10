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
import androidx.compose.ui.res.stringResource
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
                        stringResource(R.string.download_manager_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.download_manager_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                        text = stringResource(R.string.download_selected_count, selectedSongs.size),
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
                            contentDescription = if (allSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)
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
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.download_delete_selected))
                    }
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            selectedSongs = emptySet()
                            selectionMode = false
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.download_exit_selection))
                    }
                } else {
                    // 正常模式下的操作按钮
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            viewModel.refreshDownloadedSongs()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(
                        onClick = {
                            context.performHapticFeedback()
                            selectionMode = true
                        }
                    ) {
                        Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = stringResource(R.string.action_multi_select))
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
                        text = stringResource(R.string.downloaded_songs),
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
                        text = stringResource(R.string.download_space_used),
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
            placeholder = { Text(stringResource(R.string.download_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search)) },
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
            title = { Text(stringResource(R.string.dialog_confirm_delete)) },
            text = { Text(stringResource(R.string.download_delete_confirm, songToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        songToDelete?.let { viewModel.deleteDownloadedSong(it) }
                        showSingleDeleteDialog = false
                        songToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSingleDeleteDialog = false
                        songToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Multiple songs delete confirmation dialog
    if (showMultiDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showMultiDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_confirm_delete)) },
            text = { Text(stringResource(R.string.download_delete_selected_confirm, selectedSongs.size)) },
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
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMultiDeleteDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
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
                    if (searchQuery.isBlank()) stringResource(R.string.download_no_songs) else stringResource(R.string.download_no_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (searchQuery.isBlank()) stringResource(R.string.download_songs_hint) else stringResource(R.string.download_try_other_keywords),
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
                            contentDescription = stringResource(R.string.download_play),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.download_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}