package moe.ouom.neriplayer.ui.component.download

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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.formatDownloadTransferProgress
import moe.ouom.neriplayer.core.download.visibleDownloadProgressTasks
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey

internal fun downloadStageLabelResource(
    stage: AudioDownloadManager.DownloadStage
): Int? {
    return when (stage) {
        AudioDownloadManager.DownloadStage.WAITING_HOST -> R.string.download_waiting_host
        AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP ->
            R.string.download_waiting_delete_cleanup
        AudioDownloadManager.DownloadStage.RESOLVING_SOURCE -> R.string.download_resolving_source
        AudioDownloadManager.DownloadStage.PREPARING_STORAGE ->
            R.string.download_preparing_storage
        AudioDownloadManager.DownloadStage.VERIFYING_AUDIO -> R.string.download_verifying_audio
        AudioDownloadManager.DownloadStage.COMMITTING_CORE -> R.string.download_committing_core
        AudioDownloadManager.DownloadStage.ASSETS_ENRICHING ->
            R.string.download_assets_enriching
        AudioDownloadManager.DownloadStage.TRANSFERRING,
        AudioDownloadManager.DownloadStage.WAITING_RETRY,
        AudioDownloadManager.DownloadStage.FINALIZING -> null
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
                            AudioDownloadManager.DownloadStage.TRANSFERRING &&
                            progress.stage != AudioDownloadManager.DownloadStage.FINALIZING &&
                            progress.stage != AudioDownloadManager.DownloadStage.WAITING_RETRY -> {
                            val stageLabel = downloadStageLabelResource(progress.stage)
                                ?: R.string.download_progress
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

                        progress?.stage == AudioDownloadManager.DownloadStage.FINALIZING -> {
                            Text(
                                text = stringResource(R.string.download_finalizing),
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

                        progress?.stage == AudioDownloadManager.DownloadStage.WAITING_RETRY -> {
                            Text(
                                text = stringResource(R.string.download_waiting_network_recovery),
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
                                        DownloadStatus.QUEUED -> R.string.download_queued_status
                                        DownloadStatus.WAITING_NETWORK ->
                                            R.string.download_waiting_network_recovery
                                        DownloadStatus.DOWNLOADING -> R.string.download_waiting_host
                                        else -> R.string.download_progress
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
