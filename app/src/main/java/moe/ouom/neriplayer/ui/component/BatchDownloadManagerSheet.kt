package moe.ouom.neriplayer.ui.component

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.hasActiveDownloadTasks
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDownloadManagerSheet(
    batchDownloadProgress: AudioDownloadManager.BatchDownloadProgress?,
    downloadTasks: List<DownloadTask>,
    progressSummaryText: String,
    onDismiss: () -> Unit
) {
    val pendingTaskCount = remember(downloadTasks) {
        countPendingDownloadTasks(downloadTasks)
    }
    val hasActiveTasks = remember(downloadTasks) {
        hasActiveDownloadTasks(downloadTasks)
    }
    val visibleProgress = batchDownloadProgress?.takeIf { hasActiveTasks }
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

            if (visibleProgress != null || pendingTaskCount > 0) {
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
                                text = progressSummaryText,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (hasActiveTasks) {
                                HapticTextButton(onClick = { GlobalDownloadManager.cancelAllDownloadTasks() }) {
                                    Text(
                                        text = stringResource(R.string.action_cancel),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (
                            visibleProgress?.currentProgress?.stage ==
                            AudioDownloadManager.DownloadStage.FINALIZING
                        ) {
                            Text(
                                text = stringResource(R.string.download_finalizing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (visibleProgress != null) {
                            Text(
                                text = stringResource(
                                    R.string.download_overall_progress,
                                    visibleProgress.percentage
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = {
                                    (visibleProgress.percentage / 100f).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (pendingTaskCount > 0) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

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
