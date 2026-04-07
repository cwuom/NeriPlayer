package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.displayName

@Composable
fun ActiveDownloadTaskList(
    tasks: List<DownloadTask>,
    modifier: Modifier = Modifier,
    maxVisibleTasks: Int = 2
) {
    val visibleTasks = remember(tasks, maxVisibleTasks) {
        tasks.filter { it.status == DownloadStatus.DOWNLOADING }
            .take(maxVisibleTasks)
    }
    if (visibleTasks.isEmpty()) {
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        visibleTasks.forEach { task ->
            val progress = task.progress
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = task.song.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                when {
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

                    progress != null && progress.totalBytes > 0L -> {
                        Text(
                            text = stringResource(
                                R.string.download_current_file_progress,
                                progress.percentage,
                                progress.speedBytesPerSec / 1024
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    }

                    else -> {
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
