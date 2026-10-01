package moe.ouom.neriplayer.ui.screen.tab.settings.download.migration

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressFromWorkData
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

internal interface DownloadDirectoryMigrationRecoveryGateway {
    fun readSnapshot(): PersistedMigrationUiSnapshot
    fun findWorkInfo(workId: String): WorkInfo?
    suspend fun resumePersistedRequestIfNeeded(): String?
    suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int)
}

private class MigrationRecoveryEffects(
    private val controller: DownloadDirectoryMigrationRecoveryController,
    private val processingState: State<ManagedLibraryProcessingState>
) {
    val observeProgress: suspend CoroutineScope.() -> Unit = {
        controller.observeSharedProcessingProgress(processingState)
    }
    val recoverStartup: suspend CoroutineScope.() -> Unit = { controller.recoverStartup() }
    val watchWork: suspend CoroutineScope.() -> Unit = { controller.watchActiveWork() }
}

private class AndroidDownloadDirectoryMigrationRecoveryGateway(
    private val context: Context
) : DownloadDirectoryMigrationRecoveryGateway {
    override fun readSnapshot(): PersistedMigrationUiSnapshot =
        readPersistedMigrationUiSnapshot(context)

    override fun findWorkInfo(workId: String): WorkInfo? = WorkManager.getInstance(context)
        .getWorkInfoById(UUID.fromString(workId))
        .get()

    override suspend fun resumePersistedRequestIfNeeded(): String? =
        ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(context)

    override suspend fun updateSharedProgress(operationId: String, processed: Int, total: Int) {
        ManagedLibraryProcessingCoordinator.updateProgress(context, operationId, processed, total)
    }
}

internal fun restoredMigrationProgress(
    snapshot: PersistedMigrationUiSnapshot,
    workerProgress: ManagedDownloadStorage.MigrationProgress?,
    currentProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedDownloadStorage.MigrationProgress? = snapshot.progress ?: workerProgress ?: currentProgress

internal fun activeMigrationWorkId(snapshot: PersistedMigrationUiSnapshot): String? {
    val workId = snapshot.activeWorkId ?: return null
    val state = snapshot.activeWorkState ?: return null
    return workId.takeUnless { state.isFinished }
}

internal fun shouldKeepMigrationLoading(
    wasMigrating: Boolean,
    hasProgress: Boolean,
    snapshot: PersistedMigrationUiSnapshot
): Boolean = wasMigrating || hasProgress || snapshot.activeWorkId != null ||
    snapshot.requestAutoResume || snapshot.journalPhase != null

private class MigrationSnapshotReadTracker {
    private var consecutiveFailures = 0
    private var previousSnapshot: PersistedMigrationUiSnapshot? = null

    fun canContinue(snapshot: PersistedMigrationUiSnapshot?): Boolean {
        consecutiveFailures = if (snapshot == null || snapshot.checkpointReadFailed)
            consecutiveFailures + 1 else 0
        return shouldRetryMigrationSnapshotRead(consecutiveFailures)
    }

    fun changed(snapshot: PersistedMigrationUiSnapshot): Boolean = previousSnapshot != snapshot

    fun remember(snapshot: PersistedMigrationUiSnapshot) {
        previousSnapshot = snapshot
    }
}

internal fun shouldPollMigrationRecovery(
    snapshot: PersistedMigrationUiSnapshot,
    activeWorkId: String?
): Boolean = snapshot.activeWorkId == null && activeWorkId == null &&
    (snapshot.shouldResume || snapshot.checkpointReadFailed)

internal enum class MigrationRecoveryLoopDecision { STOP, CLEAR_UI, RETRY }

internal fun migrationRecoveryLoopDecision(
    preservedUi: Boolean,
    shouldPoll: Boolean,
    needsRecovery: Boolean,
    snapshotChanged: Boolean,
    autoResumeAttempted: Boolean
): MigrationRecoveryLoopDecision {
    if (!preservedUi || !shouldPoll) return MigrationRecoveryLoopDecision.STOP
    if (shouldStopMigrationRecoveryAfterNoProgress(
            shouldPreserveUi = preservedUi,
            needsRecovery = needsRecovery,
            snapshotChanged = snapshotChanged,
            autoResumeAttempted = autoResumeAttempted
        )
    ) return MigrationRecoveryLoopDecision.CLEAR_UI
    return MigrationRecoveryLoopDecision.RETRY
}

internal data class MigrationSharedProcessingProgress(
    val operationId: String,
    val processed: Int,
    val total: Int
)

private data class RecoveryProgressObservation(
    val liveProgress: ManagedDownloadStorage.MigrationProgress?,
    val persistedProgress: ManagedDownloadStorage.MigrationProgress?,
    val processing: ManagedLibraryProcessingState
)

internal fun migrationSharedProcessingProgress(
    state: ManagedLibraryProcessingState,
    progress: ManagedDownloadStorage.MigrationProgress?
): MigrationSharedProcessingProgress? {
    val operationId = state.operationId?.takeIf(String::isNotBlank) ?: return null
    if (state.reason != ManagedLibraryProcessingReason.DIRECTORY_CHANGE || progress == null) return null
    return MigrationSharedProcessingProgress(
        operationId,
        progress.processedFiles.coerceAtLeast(0),
        progress.totalFiles.coerceAtLeast(0)
    )
}

internal class DownloadDirectoryMigrationRecoveryController(
    private val resources: Resources,
    private val gateway: DownloadDirectoryMigrationRecoveryGateway,
    private val ioDispatcher: CoroutineDispatcher,
    private val onInlineMessageChange: (String?) -> Unit,
    private val isMigratingMutableState: MutableState<Boolean>,
    val liveProgressState: State<ManagedDownloadStorage.MigrationProgress?>,
    private val persistedProgressMutableState: MutableState<ManagedDownloadStorage.MigrationProgress?>,
    private val activeWorkIdMutableState: MutableState<String?>,
    private val autoResumeAttemptedMutableState: MutableState<Boolean>
) {
    val isMigratingState: State<Boolean> get() = isMigratingMutableState
    val persistedProgressState: State<ManagedDownloadStorage.MigrationProgress?>
        get() = persistedProgressMutableState

    var isMigrating by isMigratingMutableState
        private set
    var persistedMigrationProgress by persistedProgressMutableState
        private set
    var activeMigrationWorkId by activeWorkIdMutableState
        private set
    private var migrationAutoResumeAttempted by autoResumeAttemptedMutableState

    fun beginMigration() {
        migrationAutoResumeAttempted = false
        isMigrating = true
    }

    fun recordActiveWorkId(workId: String) {
        activeMigrationWorkId = workId
    }

    fun failMigration() {
        isMigrating = false
    }

    fun clearPersistedMigrationUi() {
        // 这里只清理设置页状态，保留 checkpoint 和 journal 供后台恢复
        persistedMigrationProgress = null
        isMigrating = false
        activeMigrationWorkId = null
        migrationAutoResumeAttempted = false
    }

    suspend fun applyPersistedMigrationSnapshot(
        snapshot: PersistedMigrationUiSnapshot,
        fallbackProgress: ManagedDownloadStorage.MigrationProgress? = null,
        autoResumeAttempted: Boolean
    ): PersistedMigrationSnapshotApplyResult {
        if (!snapshot.shouldPreserveUi) {
            // 终态请求不再保留旧进度，避免冷启动残留迁移弹窗
            clearPersistedMigrationUi()
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = false,
                attemptedAutoResume = false
            )
        }
        restoreSnapshotProgress(snapshot, fallbackProgress)
        if (adoptActiveSnapshotWork(snapshot)) {
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        if (shouldKeepMigrationLoading(isMigrating, persistedMigrationProgress != null, snapshot)) {
            isMigrating = true
        }
        return resumePersistedMigration(snapshot, autoResumeAttempted)
    }

    private fun restoreSnapshotProgress(
        snapshot: PersistedMigrationUiSnapshot,
        fallbackProgress: ManagedDownloadStorage.MigrationProgress?
    ) {
        val restoredProgress = restoredMigrationProgress(snapshot, fallbackProgress, persistedMigrationProgress)
        restoredProgress?.let { persistedMigrationProgress = it }
    }

    private fun adoptActiveSnapshotWork(snapshot: PersistedMigrationUiSnapshot): Boolean {
        val workId = activeMigrationWorkId(snapshot) ?: return false
        activeMigrationWorkId = workId
        isMigrating = true
        return true
    }

    private suspend fun resumePersistedMigration(
        snapshot: PersistedMigrationUiSnapshot,
        autoResumeAttempted: Boolean
    ): PersistedMigrationSnapshotApplyResult {
        if (!snapshot.shouldResume) {
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "迁移 checkpoint 读取暂不可恢复，保留进度等待下次检查"
            )
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        if (!shouldAttemptMigrationAutoResume(snapshot.shouldResume, autoResumeAttempted)) {
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "迁移持久请求自动恢复预算已用尽，停止设置页轮询"
            )
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        val resumedWorkId = runCatching {
            gateway.resumePersistedRequestIfNeeded()
        }.onFailure { error ->
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "设置页恢复持久迁移请求失败，保留进度: ${error.message}",
                error
            )
        }.getOrNull()
        if (resumedWorkId != null) {
            activeMigrationWorkId = resumedWorkId
        }
        return PersistedMigrationSnapshotApplyResult(
            preservedUi = true,
            attemptedAutoResume = true
        )
    }

    suspend fun recoverStartup() {
        val tracker = MigrationSnapshotReadTracker()
        while (true) {
            val snapshot = readSnapshot("读取迁移 WorkManager/checkpoint 快照失败，保留现有界面")
            if (!tracker.canContinue(snapshot)) {
                clearPersistedMigrationUi()
                return
            }
            if (snapshot != null && !reconcileStartupSnapshot(snapshot, tracker)) return
            delay(MIGRATION_CHECKPOINT_RETRY_DELAY_MS.milliseconds)
        }
    }

    private suspend fun reconcileStartupSnapshot(
        snapshot: PersistedMigrationUiSnapshot,
        tracker: MigrationSnapshotReadTracker
    ): Boolean {
        val snapshotChanged = tracker.changed(snapshot)
        val applyResult = applyPersistedMigrationSnapshot(
            snapshot = snapshot,
            autoResumeAttempted = migrationAutoResumeAttempted
        )
        return continueRecoveryAfterApply(
            snapshot, tracker, applyResult, snapshotChanged,
            shouldPollMigrationRecovery(snapshot, activeMigrationWorkId)
        )
    }

    private suspend fun readSnapshot(failureMessage: String): PersistedMigrationUiSnapshot? =
        withContext(ioDispatcher) { readSnapshotSafely(failureMessage) }

    private fun readSnapshotSafely(failureMessage: String): PersistedMigrationUiSnapshot? =
        runCatching { gateway.readSnapshot() }
            .onFailure { error ->
                NPLogger.w("ManagedDownloadMigrationSettings", "$failureMessage: ${error.message}", error)
            }
            .getOrNull()

    suspend fun watchActiveWork() {
        val workId = activeMigrationWorkId ?: return
        val tracker = MigrationSnapshotReadTracker()
        while (true) {
            val nextDelay = inspectActiveWork(workId, tracker) ?: return
            delay(nextDelay.milliseconds)
        }
    }

    private suspend fun inspectActiveWork(
        workId: String,
        tracker: MigrationSnapshotReadTracker
    ): Long? {
        val workInfo = withContext(ioDispatcher) { runCatching { gateway.findWorkInfo(workId) }.getOrNull() }
        return if (workInfo == null || workInfo.state.isFinished)
            inspectFinishedWork(workInfo, tracker)
        else observeRunningWork(workInfo)
    }

    private fun observeRunningWork(workInfo: WorkInfo): Long {
        persistedMigrationProgress = migrationProgressFromWorkData(workInfo.progress)
        isMigrating = true
        return 500L
    }

    private suspend fun inspectFinishedWork(
        workInfo: WorkInfo?,
        tracker: MigrationSnapshotReadTracker
    ): Long? {
        val durableSnapshot = readSnapshot("迁移任务结束后读取持久 checkpoint 失败")
        if (!tracker.canContinue(durableSnapshot)) {
            clearPersistedMigrationUi()
            return null
        }
        if (durableSnapshot == null) return MIGRATION_CHECKPOINT_RETRY_DELAY_MS
        if (durableSnapshot.shouldPreserveUi)
            return reconcileFinishedWork(workInfo, durableSnapshot, tracker)
        completeFinishedWork(workInfo)
        return null
    }

    private suspend fun reconcileFinishedWork(
        workInfo: WorkInfo?,
        snapshot: PersistedMigrationUiSnapshot,
        tracker: MigrationSnapshotReadTracker
    ): Long? {
        val previousWorkId = activeMigrationWorkId
        val snapshotChanged = tracker.changed(snapshot)
        val applyResult = applyPersistedMigrationSnapshot(
            snapshot = snapshot,
            fallbackProgress = workInfo?.let { migrationProgressFromWorkData(it.progress) },
            autoResumeAttempted = migrationAutoResumeAttempted
        )
        val shouldPoll = shouldPollFinishedMigrationRecovery(snapshot, previousWorkId)
        return if (continueRecoveryAfterApply(snapshot, tracker, applyResult, snapshotChanged, shouldPoll))
            MIGRATION_CHECKPOINT_RETRY_DELAY_MS else null
    }

    private fun continueRecoveryAfterApply(
        snapshot: PersistedMigrationUiSnapshot,
        tracker: MigrationSnapshotReadTracker,
        applyResult: PersistedMigrationSnapshotApplyResult,
        snapshotChanged: Boolean,
        shouldPoll: Boolean
    ): Boolean {
        migrationAutoResumeAttempted = migrationAutoResumeAttempted || applyResult.attemptedAutoResume
        val decision = migrationRecoveryLoopDecision(
            preservedUi = applyResult.preservedUi,
            shouldPoll = shouldPoll,
            needsRecovery = snapshot.shouldResume || snapshot.checkpointReadFailed,
            snapshotChanged = snapshotChanged,
            autoResumeAttempted = migrationAutoResumeAttempted
        )
        if (decision == MigrationRecoveryLoopDecision.CLEAR_UI) clearPersistedMigrationUi()
        if (decision != MigrationRecoveryLoopDecision.RETRY) return false
        tracker.remember(snapshot)
        return true
    }

    internal fun shouldPollFinishedMigrationRecovery(
        snapshot: PersistedMigrationUiSnapshot,
        previousWorkId: String?
    ): Boolean = snapshot.activeWorkId == null && activeMigrationWorkId == previousWorkId &&
        (snapshot.shouldResume || snapshot.checkpointReadFailed)

    private fun completeFinishedWork(workInfo: WorkInfo?) {
        if (workInfo == null) {
            clearPersistedMigrationUi()
            onInlineMessageChange(resources.getQuantityString(CoreCommonR.plurals.settings_download_directory_migrate_failed, 1, 1))
            return
        }
        persistedMigrationProgress = migrationProgressFromWorkData(workInfo.progress)
        isMigrating = false
        onInlineMessageChange(migrationCompletionMessage(workInfo))
        clearPersistedMigrationUi()
    }

    private fun migrationCompletionMessage(workInfo: WorkInfo): String =
        if (workInfo.state == WorkInfo.State.SUCCEEDED) successfulMigrationMessage(workInfo)
        else failedMigrationMessage(workInfo)

    private fun successfulMigrationMessage(workInfo: WorkInfo): String {
        val movedFiles = workInfo.outputData.getInt(ManagedDownloadMigrationWorker.KEY_MOVED_FILES, 0)
        val cleanupFailedFiles = workInfo.outputData.getInt(
            ManagedDownloadMigrationWorker.KEY_CLEANUP_FAILED_FILES, 0
        )
        return if (cleanupFailedFiles > 0) {
            resources.getQuantityString(
                CoreCommonR.plurals.settings_download_directory_migrated_partial,
                movedFiles,
                movedFiles,
                cleanupFailedFiles
            )
        } else {
            resources.getQuantityString(CoreCommonR.plurals.settings_download_directory_migrated, movedFiles, movedFiles)
        }
    }

    private fun failedMigrationMessage(workInfo: WorkInfo): String {
        val skippedFiles = workInfo.outputData.getInt(ManagedDownloadMigrationWorker.KEY_SKIPPED_FILES, 0)
            .coerceAtLeast(1)
        return resources.getQuantityString(
            CoreCommonR.plurals.settings_download_directory_migrate_failed,
            skippedFiles,
            skippedFiles
        )
    }

    suspend fun updateSharedProcessingProgress(state: ManagedLibraryProcessingState) {
        val progress = liveProgressState.value ?: persistedProgressMutableState.value
        val shared = migrationSharedProcessingProgress(state, progress) ?: return
        gateway.updateSharedProgress(
            operationId = shared.operationId,
            processed = shared.processed,
            total = shared.total
        )
    }

    suspend fun observeSharedProcessingProgress(processingState: State<ManagedLibraryProcessingState>) {
        snapshotFlow {
            RecoveryProgressObservation(
                liveProgressState.value,
                persistedProgressMutableState.value,
                processingState.value
            )
        }.collectLatest { observation ->
            updateSharedProcessingProgress(observation.processing)
        }
    }
}

@Composable
internal fun rememberDownloadDirectoryMigrationRecoveryController(
    context: Context,
    resources: Resources,
    libraryProcessingState: State<ManagedLibraryProcessingState>,
    onInlineMessageChange: (String?) -> Unit
): DownloadDirectoryMigrationRecoveryController {
    val isMigratingState = remember { mutableStateOf(false) }
    val liveProgressState = ManagedDownloadStorage.migrationProgressFlow.collectAsState()
    val persistedProgressState = remember {
        mutableStateOf<ManagedDownloadStorage.MigrationProgress?>(null)
    }
    val activeWorkIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val autoResumeAttemptedState = rememberSaveable { mutableStateOf(false) }
    val currentOnInlineMessageChange = rememberUpdatedState(onInlineMessageChange)
    val gateway = remember(context) { AndroidDownloadDirectoryMigrationRecoveryGateway(context) }
    val controller = remember(resources, gateway) {
        DownloadDirectoryMigrationRecoveryController(
            resources = resources,
            gateway = gateway,
            ioDispatcher = Dispatchers.IO,
            onInlineMessageChange = { currentOnInlineMessageChange.value(it) },
            isMigratingMutableState = isMigratingState,
            liveProgressState = liveProgressState,
            persistedProgressMutableState = persistedProgressState,
            activeWorkIdMutableState = activeWorkIdState,
            autoResumeAttemptedMutableState = autoResumeAttemptedState
        )
    }
    val effects = MigrationRecoveryEffects(controller, libraryProcessingState)
    LaunchedEffect(controller, block = effects.observeProgress)
    LaunchedEffect(controller, block = effects.recoverStartup)
    LaunchedEffect(controller.activeMigrationWorkId, block = effects.watchWork)
    return controller
}
