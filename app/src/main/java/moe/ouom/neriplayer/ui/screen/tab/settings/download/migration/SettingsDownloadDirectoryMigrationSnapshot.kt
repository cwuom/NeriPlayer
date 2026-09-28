package moe.ouom.neriplayer.ui.screen.tab.settings.download.migration

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressCheckpointIds
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressFromWorkData
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest
import moe.ouom.neriplayer.core.download.storage.migration.progress.mergeMigrationProgressFloor
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectActiveMigrationWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectMigrationProgressCheckpoint
import moe.ouom.neriplayer.core.download.storage.migration.progress.shouldPreserveMigrationUiAfterWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.progress.shouldResumePersistedMigrationAfterWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore

internal const val MIGRATION_CHECKPOINT_RETRY_DELAY_MS = 1_000L
internal const val MIGRATION_SNAPSHOT_READ_RETRY_LIMIT = 3

internal data class PersistedMigrationUiSnapshot(
    val activeWorkId: String?,
    val activeWorkState: WorkInfo.State?,
    val progress: ManagedDownloadStorage.MigrationProgress?,
    val requestAutoResume: Boolean,
    val hasPersistedRequest: Boolean,
    val journalPhase: ManagedMigrationReplacementJournalPhase?,
    val checkpointReadFailed: Boolean
) {
    val shouldPreserveUi: Boolean
        get() = shouldPreserveMigrationUiAfterWorkInfo(
            workInfoState = activeWorkState,
            requestAutoResume = requestAutoResume,
            journalPhase = journalPhase,
            hasPersistedRequest = hasPersistedRequest,
            checkpointReadFailed = checkpointReadFailed
        )

    val shouldResume: Boolean
        get() = shouldResumePersistedMigrationAfterWorkInfo(
            workInfoState = activeWorkState,
            requestAutoResume = requestAutoResume,
            journalPhase = journalPhase,
            hasPersistedRequest = hasPersistedRequest,
            checkpointReadFailed = checkpointReadFailed
        )
}

internal data class PersistedMigrationSnapshotApplyResult(
    val preservedUi: Boolean,
    val attemptedAutoResume: Boolean
)

internal fun shouldRetryMigrationSnapshotRead(
    consecutiveFailures: Int,
    retryLimit: Int = MIGRATION_SNAPSHOT_READ_RETRY_LIMIT
): Boolean = consecutiveFailures < retryLimit

internal fun shouldAttemptMigrationAutoResume(
    shouldResume: Boolean,
    autoResumeAttempted: Boolean
): Boolean = shouldResume && !autoResumeAttempted

internal fun shouldStopMigrationRecoveryAfterNoProgress(
    shouldPreserveUi: Boolean,
    needsRecovery: Boolean,
    snapshotChanged: Boolean,
    autoResumeAttempted: Boolean
): Boolean = shouldPreserveUi && needsRecovery && autoResumeAttempted && !snapshotChanged

/** 同时读取 WorkManager 和持久检查点，避免缺少任务行时抹掉界面状态 */
internal fun readPersistedMigrationUiSnapshot(context: Context): PersistedMigrationUiSnapshot {
    val appContext = context.applicationContext
    val checkpointStore = ManagedDownloadMigrationCheckpointStore(appContext)
    val requestResult = readMigrationRequest(checkpointStore)
    val journalResult = readMigrationJournal(checkpointStore)
    val workInfoResult = readMigrationWorkInfos(appContext)
    return composePersistedMigrationReadResults(
        requestResult, journalResult, workInfoResult, checkpointStore::readProgress
    )
}

internal fun composePersistedMigrationReadResults(
    requestResult: Result<ManagedMigrationRequest?>,
    journalResult: Result<ManagedMigrationReplacementJournal?>,
    workInfoResult: Result<List<WorkInfo>>,
    readProgress: (String) -> ManagedDownloadStorage.MigrationProgress?
): PersistedMigrationUiSnapshot {
    return composePersistedMigrationUiSnapshot(
        request = requestResult.getOrNull(),
        journal = journalResult.getOrNull(),
        workInfos = workInfoResult.getOrDefault(emptyList()),
        readProgress = readProgress,
        checkpointReadFailed = requestResult.isFailure or journalResult.isFailure or workInfoResult.isFailure
    )
}

private fun readMigrationRequest(
    checkpointStore: ManagedDownloadMigrationCheckpointStore
): Result<ManagedMigrationRequest?> = runCatching { checkpointStore.readRequest() }

private fun readMigrationJournal(
    checkpointStore: ManagedDownloadMigrationCheckpointStore
): Result<ManagedMigrationReplacementJournal?> = runCatching { checkpointStore.readReplacementJournal() }

private fun readMigrationWorkInfos(context: Context): Result<List<WorkInfo>> = runCatching {
    WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(ManagedDownloadMigrationWorker.WORK_NAME)
        .get()
}

internal fun composePersistedMigrationUiSnapshot(
    request: ManagedMigrationRequest?,
    journal: ManagedMigrationReplacementJournal?,
    workInfos: List<WorkInfo>,
    readProgress: (String) -> ManagedDownloadStorage.MigrationProgress?,
    checkpointReadFailed: Boolean
): PersistedMigrationUiSnapshot {
    val activeWork = selectActiveMigrationWorkInfo(
        workInfos = workInfos,
        preferredWorkId = preferredMigrationWorkId(request),
        fallbackWorkId = fallbackMigrationWorkId(request, journal)
    )
    val progress = selectMigrationProgressCheckpoint(
        checkpointIds = migrationProgressCheckpointIds(
            currentWorkId = selectedMigrationWorkId(activeWork, request, journal),
            inputCheckpointWorkId = preferredMigrationCheckpointId(request),
            persistedRequest = request,
            persistedJournal = journal
        ),
        readProgress = readProgress
    )
    return PersistedMigrationUiSnapshot(
        activeWorkId = activeWork?.id?.toString(),
        activeWorkState = activeWork?.state,
        progress = mergeMigrationUiProgress(progress, activeWork),
        requestAutoResume = requestsMigrationAutoResume(request),
        hasPersistedRequest = request != null,
        journalPhase = journal?.phase,
        checkpointReadFailed = checkpointReadFailed
    )
}

private fun preferredMigrationWorkId(request: ManagedMigrationRequest?): String? = request?.workId

private fun preferredMigrationCheckpointId(request: ManagedMigrationRequest?): String? = request?.checkpointWorkId

private fun fallbackMigrationWorkId(
    request: ManagedMigrationRequest?,
    journal: ManagedMigrationReplacementJournal?
): String? = request?.checkpointWorkId ?: journal?.workId

private fun requestsMigrationAutoResume(request: ManagedMigrationRequest?): Boolean = request?.autoResume == true

private fun selectedMigrationWorkId(
    activeWork: WorkInfo?,
    request: ManagedMigrationRequest?,
    journal: ManagedMigrationReplacementJournal?
): String = activeWork?.id?.toString() ?: request?.workId ?: journal?.workId.orEmpty()

private fun mergeMigrationUiProgress(
    durable: ManagedDownloadStorage.MigrationProgress?,
    activeWork: WorkInfo?
): ManagedDownloadStorage.MigrationProgress? {
    if (activeWork == null) return durable
    val workerProgress = migrationProgressFromWorkData(activeWork.progress) ?: return durable
    return if (durable == null) workerProgress
    else mergeMigrationProgressFloor(floor = durable, current = workerProgress)
}
