package moe.ouom.neriplayer.ui.component.download

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.download.DownloadStage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.core.download.presentation.formatDownloadTransferProgress
import moe.ouom.neriplayer.core.download.presentation.hasDownloadTaskStartedWork
import moe.ouom.neriplayer.core.download.presentation.visibleDownloadProgressTasks
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey

internal fun downloadStageLabelResource(
    stage: DownloadStage
): Int? {
    return when (stage) {
        DownloadStage.WAITING_HOST -> CoreCommonR.string.download_waiting_host
        DownloadStage.WAITING_DELETE_CLEANUP ->
            CoreCommonR.string.download_waiting_delete_cleanup
        DownloadStage.RESOLVING_SOURCE -> CoreCommonR.string.download_resolving_source
        DownloadStage.PREPARING_STORAGE ->
            CoreCommonR.string.download_preparing_storage
        DownloadStage.VERIFYING_AUDIO -> CoreCommonR.string.download_verifying_audio
        DownloadStage.COMMITTING_CORE -> CoreCommonR.string.download_committing_core
        DownloadStage.ASSETS_ENRICHING ->
            CoreCommonR.string.download_assets_enriching
        DownloadStage.WAITING_RETRY -> CoreCommonR.string.download_waiting_retry
        DownloadStage.TRANSFERRING,
        DownloadStage.FINALIZING -> null
    }
}

@Composable
fun ActiveDownloadTaskList(
    tasks: List<DownloadTask>,
    modifier: Modifier = Modifier,
    maxVisibleTasks: Int = AudioDownloadManager.DEFAULT_MAX_CONCURRENT_DOWNLOADS,
    maxHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val visibleTasks = remember(tasks, maxVisibleTasks) {
        visibleDownloadProgressTasks(tasks)
            .filter(::hasDownloadTaskStartedWork)
            .take(maxVisibleTasks)
    }
    if (visibleTasks.isEmpty()) {
        return
    }

    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        visibleTasks.forEach { task ->
            key(task.song.stableKey(), task.attemptId) {
                val progress = task.progress
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = task.song.displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    when {
                        progress != null && progress.stage !=
                            DownloadStage.TRANSFERRING &&
                            progress.stage != DownloadStage.FINALIZING &&
                            progress.stage != DownloadStage.WAITING_RETRY -> {
                            val stageLabel = downloadStageLabelResource(progress.stage)
                                ?: CoreCommonR.string.download_progress
                            Text(
                                text = stringResource(stageLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDownloadTransferProgress(progress, showSpeed = false),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }

                        progress?.stage == DownloadStage.FINALIZING -> {
                            Text(
                                text = stringResource(CoreCommonR.string.download_finalizing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }

                        progress?.stage == DownloadStage.WAITING_RETRY -> {
                            Text(
                                text = stringResource(
                                    if (task.status == DownloadStatus.WAITING_NETWORK) {
                                        CoreCommonR.string.download_waiting_network_recovery
                                    } else {
                                        CoreCommonR.string.download_waiting_retry
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDownloadTransferProgress(progress, showSpeed = false),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (progress.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = {
                                        (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }

                        progress != null -> {
                            Text(
                                text = formatDownloadTransferProgress(progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (progress.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = {
                                        (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }

                        else -> {
                            Text(
                                text = stringResource(
                                    when (task.status) {
                                        DownloadStatus.QUEUED -> CoreCommonR.string.download_queued_status
                                        DownloadStatus.WAITING_NETWORK ->
                                            CoreCommonR.string.download_waiting_network_recovery
                                        DownloadStatus.DOWNLOADING -> CoreCommonR.string.download_waiting_host
                                        else -> CoreCommonR.string.download_progress
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            }
        }
    }
}
