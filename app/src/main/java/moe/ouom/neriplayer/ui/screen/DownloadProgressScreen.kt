package moe.ouom.neriplayer.ui.screen

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadProgressScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.download_clear_confirm_title)) },
            text = { Text(stringResource(R.string.download_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.performHapticFeedback()
                        GlobalDownloadManager.clearCompletedTasks()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.download_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.download_cancel_action))
                }
            }
        )
    }

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
                        stringResource(R.string.download_progress),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.download_tasks_count, downloadTasks.size),
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
                IconButton(
                    onClick = {
                        context.performHapticFeedback()
                        showClearDialog = true
                    }
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = stringResource(R.string.download_clear_completed))
                }
            }
        )

        if (downloadTasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.download_no_tasks),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                )
            ) {
                items(downloadTasks, key = { it.song.id }) { task ->
                    val canDismiss = task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.CANCELLED

                    if (canDismiss) {
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                    context.performHapticFeedback()
                                    GlobalDownloadManager.removeDownloadTask(task.song.id)
                                    true
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { },
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 250),
                                fadeOutSpec = tween(durationMillis = 250),
                                placementSpec = tween(durationMillis = 250)
                            )
                        ) {
                            DownloadTaskItem(
                                task = task,
                                onCancel = {
                                    context.performHapticFeedback()
                                    GlobalDownloadManager.cancelDownloadTask(task.song.id)
                                },
                                onResume = {
                                    context.performHapticFeedback()
                                    GlobalDownloadManager.resumeDownloadTask(context, task.song.id)
                                }
                            )
                        }
                    } else {
                        DownloadTaskItem(
                            task = task,
                            onCancel = {
                                context.performHapticFeedback()
                                GlobalDownloadManager.cancelDownloadTask(task.song.id)
                            },
                            onResume = {
                                context.performHapticFeedback()
                                GlobalDownloadManager.resumeDownloadTask(context, task.song.id)
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 250),
                                fadeOutSpec = tween(durationMillis = 250),
                                placementSpec = tween(durationMillis = 250)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    onResume: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态图标
                Icon(
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload
                        DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                        DownloadStatus.FAILED -> Icons.Default.Error
                        DownloadStatus.CANCELLED -> Icons.Default.Cancel
                    },
                    contentDescription = null,
                    tint = when (task.status) {
                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                        DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 歌曲信息
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = task.song.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 取消/恢复按钮
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.download_cancel_download),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        IconButton(onClick = onResume) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.download_resume),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条和信息
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    task.progress?.let { progress ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${progress.percentage}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${formatFileSize(progress.bytesRead)} / ${formatFileSize(progress.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress.bytesRead.toFloat() / progress.totalBytes.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatFileSize(progress.speedBytesPerSec)}/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } ?: run {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
                DownloadStatus.COMPLETED -> {
                    Text(
                        text = stringResource(R.string.download_completed),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = stringResource(R.string.download_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                DownloadStatus.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.download_cancelled_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
