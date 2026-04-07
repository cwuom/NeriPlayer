package moe.ouom.neriplayer.ui.screen

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
 * File: moe.ouom.neriplayer.ui.screen/DownloadProgressScreen
 * Updated: 2026/3/23
 */


import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
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
                pluralStringResource(
                    R.plurals.download_tasks_count,
                    downloadTasks.size,
                    downloadTasks.size
                ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                items(
                    items = downloadTasks,
                    key = { it.song.stableKey() },
                    contentType = { task -> task.status }
                ) { task ->
                    val songKey = task.song.stableKey()
                    val canDismiss = task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.CANCELLED

                    if (canDismiss) {
                        val dismissState = rememberSwipeToDismissBoxState()

                        LaunchedEffect(dismissState.currentValue, songKey) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                context.performHapticFeedback()
                                GlobalDownloadManager.removeDownloadTask(songKey)
                            }
                        }

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
                                    GlobalDownloadManager.cancelDownloadTask(songKey)
                                },
                                onResume = {
                                    context.performHapticFeedback()
                                    GlobalDownloadManager.resumeDownloadTask(context, songKey)
                                }
                            )
                        }
                    } else {
                        DownloadTaskItem(
                            task = task,
                            onCancel = {
                                context.performHapticFeedback()
                                GlobalDownloadManager.cancelDownloadTask(songKey)
                            },
                            onResume = {
                                context.performHapticFeedback()
                                GlobalDownloadManager.resumeDownloadTask(context, songKey)
                            },
                            modifier = Modifier
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
    modifier: Modifier = Modifier,
    onResume: () -> Unit = {}
) {
    val songName = remember(task.song) { task.song.displayName() }
    val songArtist = remember(task.song) { task.song.displayArtist() }
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
                DownloadTaskStatusIcon(task.status)

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = songName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = songArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DownloadTaskActionButton(
                    status = task.status,
                    onCancel = onCancel,
                    onResume = onResume
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DownloadTaskProgressSection(task = task)
        }
    }
}

@Composable
private fun DownloadTaskStatusIcon(status: DownloadStatus) {
    Icon(
        imageVector = when (status) {
            DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload
            DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
            DownloadStatus.FAILED -> Icons.Default.Error
            DownloadStatus.CANCELLED -> Icons.Default.Cancel
        },
        contentDescription = null,
        tint = when (status) {
            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
            DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
            DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun DownloadTaskActionButton(
    status: DownloadStatus,
    onCancel: () -> Unit,
    onResume: () -> Unit
) {
    when (status) {
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
                    contentDescription = stringResource(R.string.download_to_local),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun DownloadTaskProgressSection(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.DOWNLOADING -> {
            val progress = task.progress
            if (progress == null) {
                DownloadTaskIndeterminateProgress()
                return
            }
            if (progress.stage == AudioDownloadManager.DownloadStage.FINALIZING) {
                Text(
                    text = stringResource(R.string.download_finalizing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                DownloadTaskIndeterminateProgress()
                return
            }
            if (progress.totalBytes <= 0L) {
                DownloadTaskIndeterminateProgress()
                return
            }

            val progressFraction = remember(progress.bytesRead, progress.totalBytes) {
                (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                    .coerceIn(0f, 1f)
            }
            val progressText = remember(progress.percentage) { "${progress.percentage}%" }
            val sizeText = remember(progress.bytesRead, progress.totalBytes) {
                "${formatFileSize(progress.bytesRead)} / ${formatFileSize(progress.totalBytes)}"
            }
            val speedText = remember(progress.speedBytesPerSec) {
                "${formatFileSize(progress.speedBytesPerSec)}/s"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = speedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Composable
private fun DownloadTaskIndeterminateProgress() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}
