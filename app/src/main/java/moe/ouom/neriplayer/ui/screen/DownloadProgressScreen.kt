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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.BatchDownloadOverallProgress
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.ExplicitDownloadResumeCandidate
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.formatDownloadTransferProgress
import moe.ouom.neriplayer.core.download.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.download.visibleDownloadProgressTasks
import moe.ouom.neriplayer.core.download.visibleExplicitResumeCandidates
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.execution.loadExplicitDownloadResumeCandidates
import moe.ouom.neriplayer.core.download.execution.resumeExplicitDownload
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

private const val EXPLICIT_RESUME_REFRESH_ATTEMPTS = 3
private const val EXPLICIT_RESUME_REFRESH_DELAY_MS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun DownloadProgressScreen(
    onBack: () -> Unit,
    listState: LazyListState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val batchDownloadProgress by GlobalDownloadManager.batchDownloadProgressFlow
        .collectAsStateWithLifecycle()
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsStateWithLifecycle()
    val isClearingDownloadTasks by GlobalDownloadManager.isClearingDownloadTasks
        .collectAsStateWithLifecycle()
    var explicitResumeCandidates by remember {
        mutableStateOf<List<ExplicitDownloadResumeCandidate>>(emptyList())
    }
    val taskPresenceKey = remember(downloadTasks) {
        downloadTasks
            .filter { task ->
                task.status == DownloadStatus.QUEUED ||
                    task.status == DownloadStatus.DOWNLOADING ||
                    task.status == DownloadStatus.WAITING_NETWORK
            }
            .map { task -> task.song.stableKey() }
            .toSet()
    }
    LaunchedEffect(context, taskPresenceKey, isClearingDownloadTasks) {
        if (isClearingDownloadTasks) {
            explicitResumeCandidates = emptyList()
            return@LaunchedEffect
        }
        repeat(EXPLICIT_RESUME_REFRESH_ATTEMPTS) { attempt ->
            val candidates = runCatching {
                loadExplicitDownloadResumeCandidates(context)
            }.getOrDefault(emptyList())
            explicitResumeCandidates = visibleExplicitResumeCandidates(
                candidates = candidates,
                activeSongKeys = taskPresenceKey
            )
            if (explicitResumeCandidates.isNotEmpty() || attempt == EXPLICIT_RESUME_REFRESH_ATTEMPTS - 1) {
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(EXPLICIT_RESUME_REFRESH_DELAY_MS)
        }
    }
    val pendingTaskCount = remember(downloadTasks, isClearingDownloadTasks) {
        if (isClearingDownloadTasks) {
            0
        } else {
            downloadTasks.count { task ->
                task.status == DownloadStatus.QUEUED ||
                    task.status == DownloadStatus.DOWNLOADING ||
                    task.status == DownloadStatus.WAITING_NETWORK
            }
        }
    }
    val visibleBatchProgress = batchDownloadProgress?.takeUnless {
        isClearingDownloadTasks
    }
    val visibleTasks = if (isClearingDownloadTasks) {
        emptyList()
    } else {
        visibleDownloadProgressTasks(downloadTasks)
    }
    val displayedPendingTaskCount = pendingTaskCount + explicitResumeCandidates.size
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
                        explicitResumeCandidates = emptyList()
                        GlobalDownloadManager.clearAllDownloadTasks()
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
                        text = when {
                            visibleBatchProgress != null -> stringResource(
                                R.string.download_progress_with_percentage,
                                visibleBatchProgress.completedSongs,
                                visibleBatchProgress.totalSongs,
                                visibleBatchProgress.percentage
                            )

                            displayedPendingTaskCount > 0 -> pluralStringResource(
                                R.plurals.download_tasks_count,
                                displayedPendingTaskCount,
                                displayedPendingTaskCount
                            )

                            else -> stringResource(R.string.download_no_tasks)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

        if (isClearingDownloadTasks) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.download_clearing_tasks),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (
            visibleBatchProgress == null &&
                visibleTasks.isEmpty() &&
                explicitResumeCandidates.isEmpty() &&
                pendingTaskCount == 0
        ) {
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
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp + miniPlayerHeight
                )
            ) {
                visibleBatchProgress?.let { progress ->
                    item(key = "batch-overall-progress") {
                        BatchDownloadOverallProgressCard(progress = progress)
                    }
                }
                if (visibleBatchProgress == null && pendingTaskCount > 0 && visibleTasks.isEmpty()) {
                    item(key = "pending-download-summary") {
                        PendingDownloadSummaryCard(count = pendingTaskCount)
                    }
                }
                if (explicitResumeCandidates.isNotEmpty()) {
                    item(key = "explicit-resume-summary") {
                        ExplicitResumeSummaryCard(
                            count = explicitResumeCandidates.size
                        )
                    }
                    items(
                        items = explicitResumeCandidates,
                        key = { candidate -> candidate.operationId },
                        contentType = { "explicit-resume" }
                    ) { candidate ->
                        ExplicitResumeTaskItem(
                            candidate = candidate,
                            onResume = {
                                context.performHapticFeedback()
                                coroutineScope.launch {
                                    val schedule = runCatching {
                                        resumeExplicitDownload(context, candidate)
                                    }.getOrNull()
                                    if (schedule is moe.ouom.neriplayer.core.download.execution.DownloadExecutionSchedule.Scheduled) {
                                        explicitResumeCandidates = explicitResumeCandidates
                                            .filterNot { item -> item.operationId == candidate.operationId }
                                    }
                                }
                            }
                        )
                    }
                }
                items(
                    items = visibleTasks,
                    key = { it.song.stableKey() },
                    contentType = { task -> task.status }
                ) { task ->
                    val songKey = task.song.stableKey()
                    DownloadTaskItem(
                        task = task,
                        onCancel = {
                            context.performHapticFeedback()
                            GlobalDownloadManager.cancelDownloadTask(songKey)
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

@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onResume: () -> Unit = {}
) {
    val songName = remember(task.song) { task.song.displayName() }
    val songArtist = remember(task.song) { task.song.displayArtist() }
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
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
                    task = task,
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
private fun BatchDownloadOverallProgressCard(progress: BatchDownloadOverallProgress) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.download_progress_with_percentage,
                    progress.completedSongs,
                    progress.totalSongs,
                    progress.percentage
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun PendingDownloadSummaryCard(count: Int) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = pluralStringResource(R.plurals.download_tasks_count, count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ExplicitResumeSummaryCard(count: Int) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.download_explicit_resume_count,
                    count,
                    count
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.download_explicit_resume_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExplicitResumeTaskItem(
    candidate: ExplicitDownloadResumeCandidate,
    onResume: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PauseCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.song.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.download_explicit_resume_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = candidate.song.displayArtist(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.download_resume),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskStatusIcon(status: DownloadStatus) {
    Icon(
        imageVector = when (status) {
            DownloadStatus.QUEUED -> Icons.Default.Schedule
            DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload
            DownloadStatus.WAITING_NETWORK -> Icons.Default.Schedule
            DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
            DownloadStatus.FAILED -> Icons.Default.Error
            DownloadStatus.CANCELLED -> Icons.Default.Cancel
        },
        contentDescription = null,
        tint = when (status) {
            DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
            DownloadStatus.WAITING_NETWORK -> MaterialTheme.colorScheme.onSurfaceVariant
            DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
            DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun DownloadTaskActionButton(
    task: DownloadTask,
    onCancel: () -> Unit,
    onResume: () -> Unit
) {
    when (task.status) {
        DownloadStatus.QUEUED,
        DownloadStatus.WAITING_NETWORK,
        DownloadStatus.DOWNLOADING -> {
            val cancellable = isDownloadTaskCancellable(task)
            IconButton(onClick = onCancel, enabled = cancellable) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(
                        if (cancellable) R.string.download_cancel_download else R.string.download_finalizing
                    ),
                    tint = if (cancellable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        DownloadStatus.CANCELLED,
        DownloadStatus.FAILED -> {
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
        DownloadStatus.QUEUED -> {
            Text(
                text = stringResource(R.string.download_queued_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DownloadStatus.WAITING_NETWORK -> {
            Text(
                text = stringResource(R.string.download_waiting_network_recovery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            task.progress?.let { progress ->
                DownloadTaskRetainedProgress(progress)
            }
        }

        DownloadStatus.DOWNLOADING -> {
            val progress = task.progress
            if (progress == null) {
                DownloadTaskIndeterminateProgress()
                return
            }
            if (progress.stage == AudioDownloadManager.DownloadStage.WAITING_RETRY) {
                Text(
                    text = stringResource(R.string.download_waiting_network_recovery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadTaskRetainedProgress(progress)
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
            Text(
                text = formatDownloadTransferProgress(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (progress.totalBytes > 0L) {
                val progressFraction = remember(progress.bytesRead, progress.totalBytes) {
                    (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                DownloadTaskIndeterminateProgress()
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

@Composable
private fun DownloadTaskRetainedProgress(progress: AudioDownloadManager.DownloadProgress) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = formatDownloadTransferProgress(progress, showSpeed = false),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (progress.totalBytes > 0L) {
        val progressFraction = (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
            .coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    } else {
        DownloadTaskIndeterminateProgress()
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
