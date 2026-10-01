package moe.ouom.neriplayer.ui.component.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeletePhase
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteProgress

@Composable
internal fun DownloadedSongDeleteProgressCard(
    progress: DownloadedSongDeleteProgress?,
    failureDismissed: Boolean,
    onDismissFailure: (Long) -> Unit,
    modifier: Modifier = Modifier,
    requestedSongCount: Int = 0
) {
    val phase = progress?.phase ?: DownloadedSongDeletePhase.PREPARING
    if (!shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount, failureDismissed)) return

    val phaseText = stringResource(
        when (phase) {
            DownloadedSongDeletePhase.PREPARING -> CoreCommonR.string.download_delete_phase_preparing
            DownloadedSongDeletePhase.WAITING_FOR_DIRECTORY ->
                CoreCommonR.string.download_delete_phase_waiting_directory
            DownloadedSongDeletePhase.STOPPING_DOWNLOADS ->
                CoreCommonR.string.download_delete_phase_stopping_downloads
            DownloadedSongDeletePhase.WAITING_FOR_DOWNLOADS ->
                CoreCommonR.string.download_delete_phase_waiting_downloads
            DownloadedSongDeletePhase.READING_DELETE_PLAN ->
                CoreCommonR.string.download_delete_phase_reading_plan
            DownloadedSongDeletePhase.DELETING_REFERENCES ->
                CoreCommonR.string.download_delete_phase_deleting_files
            DownloadedSongDeletePhase.VERIFYING_REFERENCES ->
                CoreCommonR.string.download_delete_phase_verifying
            DownloadedSongDeletePhase.FINALIZING -> CoreCommonR.string.download_delete_phase_finalizing
            DownloadedSongDeletePhase.COMPLETED -> CoreCommonR.string.download_delete_phase_completed
            DownloadedSongDeletePhase.FAILED -> CoreCommonR.string.download_delete_phase_failed
        }
    )
    val running = isDownloadedSongDeletionRunning(progress) ||
        (progress == null && requestedSongCount > 0)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = phaseText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (phase == DownloadedSongDeletePhase.FAILED) {
                    IconButton(onClick = { progress?.let { onDismissFailure(it.deleteId) } }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(CoreCommonR.string.action_close)
                        )
                    }
                }
            }
            if (running) {
                val fraction = progress?.let(::downloadedSongDeleteProgressFraction)
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            val totalReferences = progress?.totalReferenceCount
            if (progress != null && totalReferences != null && totalReferences > 0) {
                Text(
                    text = pluralStringResource(
                        CoreCommonR.plurals.download_clear_item_progress,
                        totalReferences,
                        progress.completedReferenceCount.coerceIn(0, totalReferences.coerceAtLeast(0)),
                        totalReferences.coerceAtLeast(0)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (running) {
                val songCount = progress?.requestedSongCount ?: requestedSongCount
                Text(
                    text = pluralStringResource(
                        CoreCommonR.plurals.download_delete_in_progress_message,
                        songCount,
                        songCount
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val failedCount = progress?.failedReferenceCount ?: 0
            if (failedCount > 0) {
                Text(
                    text = stringResource(CoreCommonR.string.download_delete_failed_files, failedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
