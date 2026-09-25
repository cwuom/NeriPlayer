package moe.ouom.neriplayer.ui.component.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.model.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.batchDownloadProgressForDisplay
import moe.ouom.neriplayer.core.download.model.countFailedDownloadTasks
import moe.ouom.neriplayer.core.download.model.countPendingDownloadTasks
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDownloadManagerSheet(
    downloadTasks: List<DownloadTask>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val taskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsStateWithLifecycle()
    val activeDownloadOperations by GlobalDownloadManager.activeDownloadOperationsFlow.collectAsStateWithLifecycle()
    val batchDownloadProgress by GlobalDownloadManager.batchDownloadProgressFlow
        .collectAsStateWithLifecycle()
    val visibleBatchDownloadProgress = batchDownloadProgressForDisplay(batchDownloadProgress)
    val taskListPendingCount = remember(downloadTasks) {
        countPendingDownloadTasks(downloadTasks)
    }
    val taskListFailedCount = remember(downloadTasks) {
        countFailedDownloadTasks(downloadTasks)
    }
    val pendingTaskCount = maxOf(taskSummary.pendingTaskCount, taskListPendingCount)
    val failedTaskCount = maxOf(taskSummary.failedTaskCount, taskListFailedCount)
    val maxVisibleTaskCards = maxVisibleDownloadTaskCards(
        currentDownloadParallelism(context)
    )
    val hasPendingBatchSongs = batchDownloadProgress?.hasPendingSongs == true
    val canCancelDownloads = canCancelBatchDownload(
        hasPendingBatchSongs = hasPendingBatchSongs,
        pendingTaskCount = pendingTaskCount,
        hasActiveDownloadOperations = activeDownloadOperations
    )
    val stableProgressSummaryText = when {
        visibleBatchDownloadProgress != null -> stringResource(
            R.string.download_progress_with_percentage,
            visibleBatchDownloadProgress.completedSongs,
            visibleBatchDownloadProgress.totalSongs,
            visibleBatchDownloadProgress.percentage
        )

        pendingTaskCount > 0 -> pluralStringResource(
            R.plurals.download_tasks_count,
            pendingTaskCount,
            pendingTaskCount
        )

        activeDownloadOperations -> stringResource(
            R.string.download_execution_notification_content
        )

        else -> pluralStringResource(
            R.plurals.download_failed_songs_count,
            failedTaskCount,
            failedTaskCount
        )
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = false,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.download_manager),
                    style = MaterialTheme.typography.titleLarge
                )
                HapticIconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close)
                    )
                }
            }

            if (
                visibleBatchDownloadProgress != null ||
                pendingTaskCount > 0 ||
                failedTaskCount > 0 ||
                activeDownloadOperations
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stableProgressSummaryText,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (canCancelDownloads) {
                                HapticTextButton(
                                    onClick = { GlobalDownloadManager.cancelAllDownloadTasks() }
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_cancel),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (visibleBatchDownloadProgress != null) {
                            Text(
                                text = stringResource(
                                    R.string.download_overall_progress,
                                    visibleBatchDownloadProgress.percentage
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = {
                                    visibleBatchDownloadProgress.fraction
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (pendingTaskCount > 0 || activeDownloadOperations) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        ActiveDownloadTaskList(
                            tasks = downloadTasks,
                            maxVisibleTasks = maxVisibleTaskCards,
                            maxHeight = 320.dp
                        )

                        FailedDownloadTaskList(
                            tasks = downloadTasks,
                            onRetry = { songKey ->
                                GlobalDownloadManager.resumeDownloadTask(context, songKey)
                            },
                            onClearFailed = GlobalDownloadManager::clearFailedDownloadTasks
                        )

                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.Download,
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.download_select_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

internal fun maxVisibleDownloadTaskCards(configuredParallelism: Int): Int {
    return configuredParallelism.coerceAtLeast(1) + 1
}

internal fun canCancelBatchDownload(
    hasPendingBatchSongs: Boolean,
    pendingTaskCount: Int,
    hasActiveDownloadOperations: Boolean
): Boolean {
    return hasPendingBatchSongs || pendingTaskCount > 0 || hasActiveDownloadOperations
}
