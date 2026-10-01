package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard

internal fun managedLibraryProcessingTitleId(reason: ManagedLibraryProcessingReason?): Int = when (reason) {
    ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE -> CoreCommonR.string.managed_library_processing_upgrade_title
    ManagedLibraryProcessingReason.DIRECTORY_CHANGE -> CoreCommonR.string.managed_library_processing_directory_title
    null -> CoreCommonR.string.settings_download_directory_migrating
}

internal fun managedLibraryProcessingStageId(
    stage: ManagedDownloadStorage.MigrationStage?,
    phase: ManagedLibraryProcessingPhase?
): Int = if (stage == null) managedLibraryProcessingPhaseId(phase) else migrationStageLabelId(stage)

internal fun migrationStageLabelId(stage: ManagedDownloadStorage.MigrationStage): Int = when (stage) {
    ManagedDownloadStorage.MigrationStage.PREPARING -> CoreCommonR.string.settings_download_directory_migrating_stage_preparing
    ManagedDownloadStorage.MigrationStage.COPYING -> CoreCommonR.string.settings_download_directory_migrating_stage_copying
    ManagedDownloadStorage.MigrationStage.REWRITING_METADATA -> CoreCommonR.string.settings_download_directory_migrating_stage_rewriting
    ManagedDownloadStorage.MigrationStage.VERIFYING -> CoreCommonR.string.settings_download_directory_migrating_stage_verifying
    ManagedDownloadStorage.MigrationStage.CLEANING_UP -> CoreCommonR.string.settings_download_directory_migrating_stage_cleanup
    ManagedDownloadStorage.MigrationStage.FINALIZING -> CoreCommonR.string.settings_download_directory_migrating
}

private fun managedLibraryProcessingPhaseId(phase: ManagedLibraryProcessingPhase?): Int = when (phase) {
    ManagedLibraryProcessingPhase.UPGRADING_DATABASE -> CoreCommonR.string.managed_library_processing_upgrade_title
    ManagedLibraryProcessingPhase.REBUILDING_INDEX -> CoreCommonR.string.settings_download_directory_preparing
    ManagedLibraryProcessingPhase.WAITING_FOR_RETRY -> CoreCommonR.string.managed_library_processing_retry
    null -> CoreCommonR.string.settings_download_directory_migrating_desc
}

internal fun managedLibraryProcessingCount(
    state: ManagedLibraryProcessingState,
    progress: ManagedDownloadStorage.MigrationProgress?
): Pair<Int, Int>? {
    val stageCount = progress?.let(::migrationFileCount)
    if (stageCount != null) return stageCount.first.coerceAtLeast(0) to stageCount.second
    val processed = state.processed ?: return null
    val total = state.total?.takeIf { it > 0 } ?: return null
    return processed.coerceAtLeast(0) to total
}

private fun migrationFileCount(progress: ManagedDownloadStorage.MigrationProgress): Pair<Int, Int>? =
    when {
        progress.stageTotal > 0 -> progress.stageProcessed to progress.stageTotal
        progress.totalFiles > 0 -> progress.processedFiles to progress.totalFiles
        else -> null
    }

internal fun managedLibraryProcessingFraction(
    progress: ManagedDownloadStorage.MigrationProgress?,
    count: Pair<Int, Int>?
): Float? = progress?.fraction?.coerceIn(0f, 1f)
    ?: count?.let { (processed, total) -> (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) }

internal data class ManagedLibraryProcessingCardPresentation(
    val titleId: Int,
    val descriptionId: Int,
    val stageId: Int,
    val count: Pair<Int, Int>?,
    val fraction: Float?,
    val waitingForRetry: Boolean,
    val currentFileName: String?
)

internal fun managedLibraryProcessingCardPresentation(
    state: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedLibraryProcessingCardPresentation {
    val waitingForRetry = state is ManagedLibraryProcessingState.WaitingForRetry
    val count = managedLibraryProcessingCount(state, migrationProgress)
    return ManagedLibraryProcessingCardPresentation(
        titleId = managedLibraryProcessingTitleId(state.reason),
        descriptionId = managedLibraryProcessingDescriptionId(waitingForRetry),
        stageId = managedLibraryProcessingStageId(migrationProgress?.stage, state.phase),
        count = count,
        fraction = managedLibraryProcessingFraction(migrationProgress, count),
        waitingForRetry = waitingForRetry,
        currentFileName = migrationProgress?.currentFileName
    )
}

@Composable
internal fun ManagedLibraryProcessingDetailsCard(
    state: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
) {
    val presentation = managedLibraryProcessingCardPresentation(state, migrationProgress)
    MiuixSettingsSectionCard {
        ManagedLibraryProcessingCardContent(presentation)
    }
}

@Composable
private fun ManagedLibraryProcessingCardContent(
    presentation: ManagedLibraryProcessingCardPresentation
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(presentation.titleId),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(presentation.descriptionId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(presentation.stageId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        ManagedLibraryProcessingCountLine(presentation.count)
        ManagedLibraryProcessingActiveProgress(
            presentation.waitingForRetry,
            presentation.fraction,
            presentation.currentFileName
        )
    }
}

private fun managedLibraryProcessingDescriptionId(waitingForRetry: Boolean): Int =
    if (waitingForRetry) CoreCommonR.string.managed_library_processing_retry
    else CoreCommonR.string.settings_download_directory_migrating_desc

@Composable
private fun ManagedLibraryProcessingCountLine(count: Pair<Int, Int>?) {
    if (count == null) return
    Text(
        text = stringResource(CoreCommonR.string.managed_library_processing_progress, count.first, count.second),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ManagedLibraryProcessingActiveProgress(
    waitingForRetry: Boolean,
    fraction: Float?,
    currentFileName: String?
) {
    if (waitingForRetry) return
    ManagedLibraryProcessingProgressBar(fraction)
    ManagedLibraryProcessingCurrentFile(currentFileName)
}

@Composable
private fun ManagedLibraryProcessingProgressBar(fraction: Float?) {
    DeterminateLibraryProcessingProgressBarIfAvailable(fraction)
    IndeterminateLibraryProcessingProgressBarIfNeeded(fraction)
}

@Composable
private fun DeterminateLibraryProcessingProgressBarIfAvailable(fraction: Float?) {
    if (fraction == null) return
    DeterminateLibraryProcessingProgressBar(fraction)
}

@Composable
private fun DeterminateLibraryProcessingProgressBar(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun IndeterminateLibraryProcessingProgressBarIfNeeded(fraction: Float?) {
    if (fraction != null) return
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ManagedLibraryProcessingCurrentFile(fileName: String?) {
    visibleProcessingFileName(fileName)?.let { ManagedLibraryProcessingCurrentFileText(it) }
}

internal fun visibleProcessingFileName(fileName: String?): String? =
    fileName?.takeIf(String::isNotBlank)

@Composable
private fun ManagedLibraryProcessingCurrentFileText(visibleName: String) {
    Text(
        text = stringResource(CoreCommonR.string.settings_download_directory_migrating_current, visibleName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun DownloadDirectoryDialogs(controller: DownloadDirectorySettingsController) {
    DownloadDirectorySwitchWarningDialog(controller)
    DownloadDirectoryPendingChangeDialog(controller)
    DownloadDirectoryPreparationDialog(controller)
    DownloadDirectoryMigrationDialog(controller)
}

@Composable
private fun DownloadDirectorySwitchWarningDialog(controller: DownloadDirectorySettingsController) {
    if (!controller.showSwitchWarning) return
    MiuixSettingsDialog(
        onDismissRequest = controller.onDismissSwitchWarning,
        title = { Text(stringResource(CoreCommonR.string.settings_download_directory_switch_warning_title)) },
        text = { Text(stringResource(CoreCommonR.string.settings_download_directory_switch_warning_message)) },
        confirmButton = {
            MiuixSettingsTextButton(
                enabled = controller.changeEnabled,
                onClick = controller.onConfirmSwitchWarning
            ) {
                Text(stringResource(CoreCommonR.string.settings_download_directory_switch_warning_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = controller.onDismissSwitchWarning) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DownloadDirectoryPendingChangeDialog(controller: DownloadDirectorySettingsController) {
    val change = controller.pendingChange ?: return
    DownloadDirectoryPendingChangeDialogForChange(controller, change)
}

@Composable
private fun DownloadDirectoryPendingChangeDialogForChange(
    controller: DownloadDirectorySettingsController,
    change: PendingDownloadDirectoryChange
) {
    DownloadDirectoryPendingChangeDialogContent(pendingDownloadDirectoryDialogPort(controller, change))
}

private fun pendingDownloadDirectoryDialogPort(
    controller: DownloadDirectorySettingsController,
    change: PendingDownloadDirectoryChange
): PendingDownloadDirectoryDialogPort = PendingDownloadDirectoryDialogPort(
    targetSummary = change.targetSummary,
    showTargetConflictWarning = change.shouldShowTargetConflictWarning,
    confirmEnabled = controller.changeEnabled,
    onCancel = { controller.onCancelPendingChange(change) },
    onSkip = { controller.onSkipPendingChange(change) },
    onConfirm = { controller.onConfirmPendingChange(change) }
)

private class PendingDownloadDirectoryDialogPort(
    val targetSummary: String,
    val showTargetConflictWarning: Boolean,
    val confirmEnabled: Boolean,
    val onCancel: () -> Unit,
    val onSkip: () -> Unit,
    val onConfirm: () -> Unit
)

@Composable
private fun DownloadDirectoryPendingChangeDialogContent(
    port: PendingDownloadDirectoryDialogPort
) {
    MiuixSettingsDialog(
        onDismissRequest = port.onCancel,
        title = { Text(stringResource(CoreCommonR.string.settings_download_directory_migrate_title)) },
        text = { DownloadDirectoryPendingChangeDescription(port) },
        confirmButton = {
            MiuixSettingsTextButton(
                enabled = port.confirmEnabled,
                onClick = port.onConfirm
            ) {
                Text(stringResource(CoreCommonR.string.settings_download_directory_migrate_confirm))
            }
        },
        dismissButton = { DownloadDirectoryPendingChangeActions(port) }
    )
}

@Composable
private fun DownloadDirectoryPendingChangeDescription(port: PendingDownloadDirectoryDialogPort) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(CoreCommonR.string.settings_download_directory_migrate_message, port.targetSummary))
        DownloadDirectoryTargetConflictWarning(port.showTargetConflictWarning)
    }
}

@Composable
private fun DownloadDirectoryTargetConflictWarning(visible: Boolean) {
    if (visible) {
        Text(
            text = stringResource(CoreCommonR.string.settings_download_directory_migrate_conflict_warning),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun DownloadDirectoryPendingChangeActions(
    port: PendingDownloadDirectoryDialogPort
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        DownloadDirectoryCancelPendingChangeButton(port)
        DownloadDirectorySkipPendingChangeButton(port)
    }
}

@Composable
private fun DownloadDirectoryCancelPendingChangeButton(
    port: PendingDownloadDirectoryDialogPort
) {
    MiuixSettingsTextButton(onClick = port.onCancel) {
        Text(stringResource(CoreCommonR.string.settings_download_directory_migrate_cancel))
    }
}

@Composable
private fun DownloadDirectorySkipPendingChangeButton(
    port: PendingDownloadDirectoryDialogPort
) {
    MiuixSettingsTextButton(onClick = port.onSkip) {
        Text(stringResource(CoreCommonR.string.settings_download_directory_migrate_skip))
    }
}

@Composable
private fun DownloadDirectoryPreparationDialog(controller: DownloadDirectorySettingsController) {
    if (!controller.processingPresentation.showPreparation) return
    MiuixSettingsDialog(
        onDismissRequest = controller.onCancelPreparation,
        title = { Text(stringResource(CoreCommonR.string.settings_download_directory_preparing)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(CoreCommonR.string.settings_download_directory_preparing_desc))
            }
        },
        confirmButton = {},
        dismissButton = {
            MiuixSettingsTextButton(onClick = controller.onCancelPreparation) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

internal data class DownloadDirectoryMigrationBytes(
    val messageId: Int,
    val processedBytes: Long,
    val totalBytes: Long
)

internal fun downloadDirectoryMigrationBytes(
    progress: ManagedDownloadStorage.MigrationProgress?
): DownloadDirectoryMigrationBytes? {
    if (progress == null) return null
    return if (progress.stage == ManagedDownloadStorage.MigrationStage.VERIFYING)
        verificationMigrationBytes(progress) else copiedMigrationBytes(progress)
}

private fun verificationMigrationBytes(
    progress: ManagedDownloadStorage.MigrationProgress
): DownloadDirectoryMigrationBytes? = progress.takeIf { it.verificationBytesTotal > 0L }?.let {
    DownloadDirectoryMigrationBytes(
        CoreCommonR.string.settings_download_directory_migrating_verification_progress_bytes,
        it.verifiedBytes.coerceAtLeast(0L),
        it.verificationBytesTotal
    )
}

private fun copiedMigrationBytes(
    progress: ManagedDownloadStorage.MigrationProgress
): DownloadDirectoryMigrationBytes? = progress.takeIf { it.totalBytes > 0L }?.let {
    DownloadDirectoryMigrationBytes(
        CoreCommonR.string.settings_download_directory_migrating_progress_bytes,
        it.copiedBytes.coerceAtLeast(0L),
        it.totalBytes
    )
}

internal fun downloadDirectoryMigrationStageId(stage: ManagedDownloadStorage.MigrationStage?): Int =
    if (stage == null) CoreCommonR.string.settings_download_directory_migrating_desc
    else migrationStageLabelId(stage)

@Composable
private fun DownloadDirectoryMigrationDialog(controller: DownloadDirectorySettingsController) {
    if (!controller.processingPresentation.showMigration) return
    val presentation = downloadDirectoryMigrationDialogPresentation(controller.migrationProgress)
    MiuixSettingsDialog(
        onDismissRequest = {},
        title = { Text(stringResource(CoreCommonR.string.settings_download_directory_migrating)) },
        text = { DownloadDirectoryMigrationDialogContent(presentation) },
        confirmButton = {}
    )
}

internal data class DownloadDirectoryMigrationDialogPresentation(
    val stage: ManagedDownloadStorage.MigrationStage?,
    val fraction: Float,
    val progress: ManagedDownloadStorage.MigrationProgress?,
    val currentFileName: String?
)

internal fun downloadDirectoryMigrationDialogPresentation(
    progress: ManagedDownloadStorage.MigrationProgress?
): DownloadDirectoryMigrationDialogPresentation = DownloadDirectoryMigrationDialogPresentation(
    stage = progress?.stage,
    fraction = progress?.fraction?.coerceIn(0f, 1f) ?: 0f,
    progress = progress,
    currentFileName = progress?.currentFileName
)

@Composable
private fun DownloadDirectoryMigrationDialogContent(presentation: DownloadDirectoryMigrationDialogPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DownloadDirectoryMigrationStageLine(presentation.stage)
        DownloadDirectoryMigrationProgressBar(presentation.fraction)
        DownloadDirectoryMigrationFileCount(presentation.progress)
        DownloadDirectoryMigrationByteCount(presentation.progress)
        ManagedLibraryProcessingCurrentFile(presentation.currentFileName)
    }
}

@Composable
private fun DownloadDirectoryMigrationStageLine(stage: ManagedDownloadStorage.MigrationStage?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(stringResource(downloadDirectoryMigrationStageId(stage)))
    }
}

@Composable
private fun DownloadDirectoryMigrationProgressBar(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DownloadDirectoryMigrationFileCount(progress: ManagedDownloadStorage.MigrationProgress?) {
    if (progress == null) return
    val resources = LocalResources.current
    Text(
        text = resources.getQuantityString(
            CoreCommonR.plurals.settings_download_directory_migrating_progress_files,
            progress.stageTotal.coerceAtLeast(0),
            progress.stageProcessed.coerceAtLeast(0),
            progress.stageTotal.coerceAtLeast(0)
        ),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun DownloadDirectoryMigrationByteCount(progress: ManagedDownloadStorage.MigrationProgress?) {
    val bytes = downloadDirectoryMigrationBytes(progress) ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    Text(
        text = resources.getString(
            bytes.messageId,
            Formatter.formatShortFileSize(context, bytes.processedBytes),
            Formatter.formatShortFileSize(context, bytes.totalBytes)
        ),
        style = MaterialTheme.typography.bodyMedium
    )
}
