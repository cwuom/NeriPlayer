package moe.ouom.neriplayer.core.download.execution

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 管理用户下载的持久调度和 operation 身份 */
interface DownloadExecutionHost {
    fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule

    fun cancel(
        context: Context,
        operationId: String
    )

    fun cancelForSong(
        context: Context,
        songKey: String
    )

    fun cancelAll(
        context: Context,
        operationIds: Collection<String>
    )

    fun stopForSong(
        context: Context,
        songKey: String,
        preventReschedule: Boolean = false
    )

    fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean = true
    )

    fun externallyStoppedSongKeys(
        context: Context
    ): Set<String>

    fun requiresExplicitResume(
        context: Context,
        operationId: String?
    ): Boolean

    fun operationIdForSong(
        context: Context,
        songKey: String
    ): String?

    fun markUserRequestedProcessExitOperations(
        context: Context
    ): Set<String>

    fun isExecuting(operationId: String): Boolean = false

    suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult

    /** 从持久 operation 表接管一小批任务，供唯一 WorkManager 泵使用 */
    suspend fun pump(
        context: Context
    ): DownloadExecutionPumpResult = DownloadExecutionPumpResult.Completed
}

data class DownloadExecutionRequest(
    val operationId: String,
    val song: SongItem,
    val preserveStaging: Boolean = false,
    val requiresWifiNetwork: Boolean = true,
    val attemptId: Long? = null,
    val artifactLeaseId: String = UUID.randomUUID().toString(),
    val userInitiated: Boolean = true,
    val downloadAudioQuality: DownloadAudioQualitySelection? = null
) {
    init {
        require(normalizeDownloadOperationId(operationId) == operationId) {
            "operationId must be a safe, non-empty identifier"
        }
        require(artifactLeaseId.isNotBlank()) {
            "artifactLeaseId must be non-empty"
        }
    }
}

sealed interface DownloadExecutionSchedule {
    data class Scheduled(val backend: Backend) : DownloadExecutionSchedule

    /** operation 保持持久状态，取得有限宿主槽位后再重试 */
    data class Deferred(val reason: String) : DownloadExecutionSchedule

    data class Rejected(
        val reason: String,
        val retryable: Boolean = false
    ) : DownloadExecutionSchedule

    enum class Backend {
        UIDT_JOB,
        FOREGROUND_WORK
    }
}

sealed interface DownloadExecutionResult {
    data object Accepted : DownloadExecutionResult
    data object AlreadyHandled : DownloadExecutionResult
    data object MissingOperation : DownloadExecutionResult
    data object Retry : DownloadExecutionResult
    data object NetworkPolicyWaiting : DownloadExecutionResult
    data object Cancelled : DownloadExecutionResult
    data object UserStopped : DownloadExecutionResult
    data object UserActionRequired : DownloadExecutionResult
    data class Failed(val error: Throwable) : DownloadExecutionResult
}

enum class DownloadExecutionPumpResult {
    Completed,
    Retry
}

fun interface DownloadOperationEntryPoint {
    suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult
}

private object ExistingDownloadOperationEntryPoint : DownloadOperationEntryPoint {
    override suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult {
        return GlobalDownloadManager.startDownload(
            context = context,
            song = request.song,
            operationId = request.operationId,
            preserveStaging = request.preserveStaging,
            preparedAttemptId = request.attemptId
        )
    }
}

class DefaultDownloadExecutionHost(
    private val operationStore: DownloadExecutionOperationStore =
        DownloadExecutionOperationStore(),
    private val entryPoint: DownloadOperationEntryPoint =
        ExistingDownloadOperationEntryPoint,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DownloadExecutionHost {
    private val operationIdsBySongKey = ConcurrentHashMap<String, String>()
    private val executingOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val systemRetryStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val explicitSchedulerStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val executionAdmissionLock = Any()
    private val deferredRequests = DeferredDownloadScheduleQueue()
    private val deferredSchedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredSchedulingRunning = AtomicBoolean(false)

    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
        return PersistentDownloadClearFenceStore.withSchedulingPermit(
            context = appContext,
            onFenceActive = {
                DownloadExecutionSchedule.Rejected("download clear is in progress")
            }
        ) {
            runCatching {
                val songKey = request.song.stableKey()
                // Room 日志负责按根目录调度，内存状态可能跨目录切换残留
                val existingOperationId = operationStore.findOperationIdForSong(
                    appContext,
                    songKey
                )
                val existingState = existingOperationId?.let { id ->
                    operationStore.currentState(appContext, id)
                }
                val existingReadable = existingOperationId?.let { id ->
                    operationStore.read(appContext, id) != null
                } == true
                if (existingOperationId != null &&
                    existingOperationId != request.operationId &&
                    existingState in BLOCKING_SCHEDULING_STATES &&
                    existingReadable
                ) {
                    return@runCatching DownloadExecutionSchedule.Rejected(
                        "download operation already scheduled"
                    )
                }
                val currentState = operationStore.currentState(appContext, request.operationId)
                if (!canScheduleDownloadOperation(currentState)) {
                    return@runCatching DownloadExecutionSchedule.Rejected(
                        "operation is no longer schedulable: $currentState"
                    )
                }
                operationStore.save(appContext, request)
                if (!tryAcquireHostAdmission(appContext, request.operationId)) {
                    enqueueDeferredSchedule(appContext, request)
                    return@runCatching DownloadExecutionSchedule.Deferred(
                        "download host admission window is full"
                    )
                }
                val selectedBackend = selectDownloadExecutionBackend(
                    sdkInt = sdkInt,
                    userInitiated = request.userInitiated
                )
                val scheduledBackend = when (selectedBackend) {
                    DownloadExecutionSchedule.Backend.UIDT_JOB -> {
                        if (scheduleUidtIfSupported(appContext, request.operationId, sdkInt)) {
                            DownloadExecutionSchedule.Backend.UIDT_JOB
                        } else if (ForegroundDownloadWorker.schedule(appContext, request.operationId)) {
                            DownloadExecutionSchedule.Backend.FOREGROUND_WORK
                        } else {
                            null
                        }
                    }

                    DownloadExecutionSchedule.Backend.FOREGROUND_WORK -> {
                        ForegroundDownloadWorker.schedule(appContext, request.operationId)
                            .takeIf { it }
                            ?.let { DownloadExecutionSchedule.Backend.FOREGROUND_WORK }
                    }
                }
                if (scheduledBackend == null) {
                    releaseHostAdmissionIfIdle(appContext, request.operationId)
                    enqueueDeferredSchedule(appContext, request)
                    return@runCatching DownloadExecutionSchedule.Deferred(
                        "${selectedBackend.name} host temporarily rejected operation"
                    )
                }
                operationIdsBySongKey[songKey] = request.operationId
                deferredRequests.remove(request)
                DownloadExecutionSchedule.Scheduled(
                    scheduledBackend
                )
            }.getOrElse { error ->
                releaseHostAdmissionIfIdle(appContext, request.operationId)
                enqueueDeferredSchedule(appContext, request)
                DownloadExecutionSchedule.Deferred(
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private companion object {
        private const val HOST_ADMISSION_CAPACITY = 6
        private const val PUMP_QUERY_LIMIT = 64
        private const val PUMP_MAX_BATCHES_PER_RUN = 256
        private val SCHEDULABLE_OPERATION_STATES = setOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE"
        )
        private val ACTIVE_SCHEDULING_STATES = SCHEDULABLE_OPERATION_STATES +
            INTERRUPTED_DOWNLOAD_OPERATION_STATES
        private val BLOCKING_SCHEDULING_STATES = ACTIVE_SCHEDULING_STATES +
            WAITING_STORAGE_MUTATION_OPERATION_STATE
    }

    override fun cancel(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        deferredRequests.remove(normalizedId)
        WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
        val request = operationStore.read(appContext, normalizedId)
        val cancelAccepted = request != null &&
            operationStore.requestCancel(appContext, normalizedId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        if (cancelAccepted) {
            request.song.stableKey().let(GlobalDownloadManager::cancelDownloadOperationFromHost)
        }
        request?.song?.stableKey()?.let { songKey ->
            operationIdsBySongKey.remove(songKey, normalizedId)
        }
        releaseHostAdmissionIfIdle(appContext, normalizedId)
    }

    override fun cancelForSong(
        context: Context,
        songKey: String
    ) {
        val appContext = context.applicationContext
        buildList {
            operationIdsBySongKey[songKey]?.let(::add)
            addAll(operationStore.findOperationIdsForSong(appContext, songKey))
        }.distinct().forEach { operationId ->
            cancel(appContext, operationId)
        }
    }

    override fun cancelAll(
        context: Context,
        operationIds: Collection<String>
    ) {
        val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
        if (normalizedIds.isEmpty()) return
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            UidtDownloadJobService.cancelAll(appContext, normalizedIds)
        }
        ForegroundDownloadWorker.cancelAll(appContext, normalizedIds)
        WifiBoundDownloadWakeWorker.cancelAll(appContext, normalizedIds)
        operationIdsBySongKey.entries.removeIf { entry -> entry.value in normalizedIds }
        deferredRequests.removeAll(normalizedIds)
        val idleOperationIds = normalizedIds.filterNot(executingOperationIds::contains)
        if (idleOperationIds.isNotEmpty()) {
            operationStore.releaseHostAdmissions(appContext, idleOperationIds)
            triggerDeferredSchedules(appContext)
        }
    }

    internal fun cancelAllOwned(context: Context) {
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            UidtDownloadJobService.cancelAllOwned(appContext)
        }
        ForegroundDownloadWorker.cancelAllOwned(appContext)
        WifiBoundDownloadWakeWorker.cancelAllOwned(appContext)
        val ownedOperationIds = operationIdsBySongKey.values + deferredRequests.operationIds()
        val idleOperationIds = ownedOperationIds.filterNot(executingOperationIds::contains)
        operationStore.releaseHostAdmissions(appContext, idleOperationIds)
        operationIdsBySongKey.clear()
        deferredRequests.clear()
    }

    override fun stopForSong(
        context: Context,
        songKey: String,
        preventReschedule: Boolean
    ) {
        val appContext = context.applicationContext
        buildList {
            operationIdsBySongKey[songKey]?.let(::add)
            addAll(operationStore.findOperationIdsForSong(appContext, songKey))
        }.distinct().forEach { operationId ->
            stop(appContext, operationId, preventReschedule)
        }
    }

    override fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean
    ) {
        stopInternal(
            context = context,
            operationId = operationId,
            preventReschedule = preventReschedule,
            cancelExecutionBackends = true
        )
    }

    internal fun stopForSystemRetry(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        prepareSchedulerStop(normalizedId, preventReschedule = false)
        stopInternal(
            context = context,
            operationId = normalizedId,
            preventReschedule = false,
            cancelExecutionBackends = false
        )
    }

    internal fun prepareSchedulerStop(
        operationId: String,
        preventReschedule: Boolean
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        synchronized(executionAdmissionLock) {
            if (!executingOperationIds.contains(normalizedId)) return
            if (preventReschedule) {
                explicitSchedulerStopOperationIds.add(normalizedId)
                systemRetryStopOperationIds.remove(normalizedId)
            } else if (!explicitSchedulerStopOperationIds.contains(normalizedId)) {
                systemRetryStopOperationIds.add(normalizedId)
            }
        }
    }

    private fun stopInternal(
        context: Context,
        operationId: String,
        preventReschedule: Boolean,
        cancelExecutionBackends: Boolean
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        val request = operationStore.read(appContext, normalizedId) ?: return
        if (PersistentDownloadClearFenceStore.isActive(appContext)) {
            cancel(appContext, normalizedId)
            return
        }
        val currentState = operationStore.currentState(appContext, normalizedId)
        if (!shouldHandleHostStop(currentState)) {
            if (preventReschedule) {
                WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
            }
            if (cancelExecutionBackends) {
                cancelExecutionBackends(appContext, normalizedId)
            }
            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return
        }
        operationIdsBySongKey[request.song.stableKey()] = normalizedId
        val rescheduleBlocked = shouldBlockHostReschedule(
            preventReschedule = preventReschedule,
            alreadyStoppedByUser = operationStore.isStopped(appContext, normalizedId)
        )
        if (rescheduleBlocked) {
            WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
        }
        val retryPrepared = if (rescheduleBlocked) {
            operationStore.markStopped(appContext, normalizedId)
            false
        } else {
            // 让暂停的 operation 成为队列刷新时唯一可恢复的任务
            operationStore.updateState(
                context = appContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = "HOST_STOPPED"
            )
        }
        if (cancelExecutionBackends) {
            cancelExecutionBackends(appContext, normalizedId)
        }
        GlobalDownloadManager.stopDownloadOperation(
            context = appContext,
            songKey = request.song.stableKey(),
            rememberForRetry = retryPrepared
        )
        if (rescheduleBlocked || !retryPrepared) {
            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
        }
        releaseHostAdmissionIfIdle(appContext, normalizedId)
    }

    private fun cancelExecutionBackends(context: Context, operationId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(context, operationId)
        }
        ForegroundDownloadWorker.cancel(context, operationId)
    }

    override fun externallyStoppedSongKeys(context: Context): Set<String> {
        return operationStore.stoppedSongKeys(context.applicationContext)
    }

    override fun requiresExplicitResume(
        context: Context,
        operationId: String?
    ): Boolean {
        val normalizedId = operationId?.let(::normalizeDownloadOperationId) ?: return false
        val appContext = context.applicationContext
        val request = operationStore.read(appContext, normalizedId) ?: return false
        if (!request.userInitiated || sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        val state = operationStore.currentState(appContext, normalizedId)
        return shouldRequireExplicitResume(
            userInitiated = request.userInitiated,
            state = state,
            hasPendingUidtJob = hasPendingUidtJob(appContext, normalizedId),
            stopRequestedByUser = operationStore.isStopped(appContext, normalizedId),
            cancellationRequestedByUser = operationStore.isUserCancellationRequested(
                appContext,
                normalizedId
            ),
            resumePending = operationStore.isExplicitResumePending(appContext, normalizedId)
        )
    }

    override fun operationIdForSong(context: Context, songKey: String): String? {
        return operationStore.findOperationIdForSong(
            context.applicationContext,
            songKey
        )
    }

    override fun isExecuting(operationId: String): Boolean {
        return executingOperationIds.contains(operationId)
    }

    override fun markUserRequestedProcessExitOperations(context: Context): Set<String> {
        if (sdkInt < Build.VERSION_CODES.R) return emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return emptySet()
        val latestExit = latestProcessExit(activityManager, context.packageName) ?: return emptySet()
        // 从最近任务移除和强行停止都可能报告 REASON_USER_REQUESTED，这属于进程生命周期
        // 变化而不是取消下载，持久 operation 要等下次打开应用后恢复
        if (!isUserRequestedProcessExitReason(latestExit.reason)) {
            return emptySet()
        }
        val preferences = context.getSharedPreferences(
            PROCESS_EXIT_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val lastHandledTimestamp = preferences.getLong(PROCESS_EXIT_TIMESTAMP_KEY, 0L)
        if (latestExit.timestamp <= lastHandledTimestamp) {
            return emptySet()
        }
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            preferences.edit {
                putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
            }
            return emptySet()
        }
        val activeStates = listOf("PENDING_QUEUE", "QUEUED", "RETRYABLE") +
            INTERRUPTED_DOWNLOAD_OPERATION_STATES
        val entries = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                DownloadExecutionRoomStore.listByStatesAnyLibrary(context, activeStates)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "读取用户停止进程的 durable operation 失败，保留退出标记待下次重试: " +
                    error.message,
                error
            )
            return emptySet()
        }
        val stoppedKeys = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                DownloadExecutionRoomStore.markUserRequestedProcessExitOperations(
                    context = context.applicationContext,
                    entries = entries
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "写入用户停止标记失败，保留退出时间戳待下次重试: ${error.message}",
                error
            )
            return emptySet()
        }
        val expectedUserInitiatedCount = entries.count { it.request.userInitiated }
        if (stoppedKeys.size < expectedUserInitiatedCount) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "部分下载 operation 未写入用户停止标记，保留退出时间戳待下次重试"
            )
            return emptySet()
        }
        preferences.edit {
            putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
        }
        return stoppedKeys
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val appContext = context.applicationContext
        if (PersistentDownloadClearFenceStore.isActive(appContext)) {
            runCatching {
                operationStore.requestCancel(appContext, normalizedId)
            }
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext DownloadExecutionResult.Cancelled
        }
        val initialRequest = operationStore.read(appContext, normalizedId)
            ?: run {
                operationStore.updateState(
                    context = appContext,
                    operationId = normalizedId,
                    state = "INVALID",
                    errorCode = "INVALID_OPERATION_PAYLOAD"
                )
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "NERI-DownloadHost",
                    "下载 operation 读取失败: operationId=$normalizedId, reason=missing_or_unreadable"
                )
                releaseHostAdmissionIfIdle(appContext, normalizedId)
                return@withContext DownloadExecutionResult.MissingOperation
            }
        if (operationStore.isStopped(appContext, normalizedId)) {
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext DownloadExecutionResult.UserStopped
        }
        resolvePreExecutionResult(
            operationStore.currentState(appContext, normalizedId)
        )?.let { result ->
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext result
        }
        val claimResult = synchronized(executionAdmissionLock) {
            when {
                !tryAcquireHostAdmission(appContext, normalizedId) ->
                    resolvePreExecutionResult(
                        operationStore.currentState(appContext, normalizedId)
                    ) ?: DownloadExecutionResult.Retry
                !executingOperationIds.add(normalizedId) -> resolveConcurrentExecutionResult(
                    systemRetryStopPending = systemRetryStopOperationIds.contains(normalizedId)
                )
                else -> null
            }
        }
        if (claimResult != null) {
            return@withContext claimResult
        }
        try {
            if (!operationStore.tryStart(
                    context = appContext,
                    operationId = normalizedId,
                    allowExistingRunning = true
                )
            ) {
                return@withContext DownloadExecutionResult.AlreadyHandled
            }
            val request = operationStore.read(appContext, normalizedId)
                ?.takeIf { latest ->
                    latest.operationId == initialRequest.operationId &&
                        latest.song.stableKey() == initialRequest.song.stableKey()
                }
                ?: return@withContext DownloadExecutionResult.MissingOperation
            if (operationStore.isStopped(appContext, normalizedId)) {
                return@withContext DownloadExecutionResult.UserStopped
            }
            when (operationStore.currentState(appContext, normalizedId)) {
                "CANCEL_REQUESTED",
                "CANCELLED" -> return@withContext DownloadExecutionResult.Cancelled
                "RUNNING",
                "CORE_COMMITTED",
                "ASSETS_ENRICHING",
                "DEGRADED_COMPLETE" -> Unit
                else -> return@withContext DownloadExecutionResult.AlreadyHandled
            }
            operationIdsBySongKey[request.song.stableKey()] = normalizedId
            val result = entryPoint.start(
                context = context.applicationContext,
                request = request
            )
            var returnedResult = result
            when (result) {
                DownloadExecutionResult.Accepted -> {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "COMPLETED"
                    )
                    operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    operationStore.pruneTerminalOperations(
                        context = context.applicationContext,
                        cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                        limit = TERMINAL_OPERATION_PRUNE_LIMIT
                    )
                }
                DownloadExecutionResult.AlreadyHandled -> Unit
                DownloadExecutionResult.Cancelled -> {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "CANCELLED",
                        errorCode = "USER_CANCELLED"
                    )
                    operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    operationStore.pruneTerminalOperations(
                        context = context.applicationContext,
                        cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                        limit = TERMINAL_OPERATION_PRUNE_LIMIT
                    )
                }
                DownloadExecutionResult.UserStopped -> {
                    operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                }
                DownloadExecutionResult.UserActionRequired -> {
                    val persisted = operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = METADATA_ACTION_REQUIRED_OPERATION_STATE,
                        errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
                    )
                    if (!persisted) {
                        return@withContext DownloadExecutionResult.Retry
                    }
                    operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                }
                is DownloadExecutionResult.Failed -> {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "RETRYABLE",
                        errorCode = result.error.javaClass.simpleName
                    )
                }
                DownloadExecutionResult.Retry -> {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "RETRYABLE"
                    )
                }
                DownloadExecutionResult.NetworkPolicyWaiting -> {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "RETRYABLE",
                        errorCode = "NETWORK_POLICY_WAITING"
                    )
                    val wakeRearmed = !request.requiresWifiNetwork ||
                        WifiBoundDownloadWakeWorker.rearmAfterNetworkPolicyWait(
                            context = context.applicationContext,
                            operationId = normalizedId
                        )
                    operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    if (!wakeRearmed) {
                        returnedResult = DownloadExecutionResult.Retry
                    }
                }
                DownloadExecutionResult.MissingOperation -> Unit
            }
            returnedResult
        } catch (cancellation: CancellationException) {
            if (systemRetryStopOperationIds.contains(normalizedId)) {
                throw cancellation
            }
            val latestState = operationStore.currentState(
                context.applicationContext,
                normalizedId
            )
            if (shouldHandleHostStop(latestState)) {
                val explicitlyStopped =
                    explicitSchedulerStopOperationIds.contains(normalizedId) ||
                        operationStore.isStopped(
                            context.applicationContext,
                            normalizedId
                        )
                val retryPrepared = if (!explicitlyStopped) {
                    operationStore.updateState(
                        context = context.applicationContext,
                        operationId = normalizedId,
                        state = "RETRYABLE",
                        errorCode = "HOST_CANCELLED"
                    )
                } else {
                    false
                }
                GlobalDownloadManager.stopDownloadOperation(
                    context = context.applicationContext,
                    songKey = initialRequest.song.stableKey(),
                    rememberForRetry = retryPrepared
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            operationStore.updateState(
                context = context.applicationContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = error.javaClass.simpleName
            )
            DownloadExecutionResult.Failed(error)
        } finally {
            synchronized(executionAdmissionLock) {
                executingOperationIds.remove(normalizedId)
                systemRetryStopOperationIds.remove(normalizedId)
                explicitSchedulerStopOperationIds.remove(normalizedId)
            }
            releaseHostAdmissionIfIdle(appContext, normalizedId)
        }
    }

    override suspend fun pump(
        context: Context
    ): DownloadExecutionPumpResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var completedBatches = 0
        while (completedBatches < PUMP_MAX_BATCHES_PER_RUN) {
            val requests = operationStore.listSchedulableForPump(
                context = appContext,
                limit = PUMP_QUERY_LIMIT
            )
            if (requests.isEmpty()) {
                return@withContext DownloadExecutionPumpResult.Completed
            }
            val candidates = requests
                .asSequence()
                .distinctBy(DownloadExecutionRequest::operationId)
                .filterNot { request -> shouldLeaveForPendingUidt(appContext, request) }
                .take(HOST_ADMISSION_CAPACITY)
                .toList()
            if (candidates.isEmpty()) {
                // UIDT 仍在系统队列时先让系统执行，延迟重试可覆盖任务意外丢失
                return@withContext DownloadExecutionPumpResult.Retry
            }
            val results = coroutineScope {
                candidates.map { request ->
                    async(Dispatchers.IO) {
                        execute(appContext, request.operationId)
                    }
                }.awaitAll()
            }
            completedBatches++
            if (results.any(::requiresPumpRetry)) {
                return@withContext DownloadExecutionPumpResult.Retry
            }
        }
        DownloadExecutionPumpResult.Retry
    }

    private fun shouldLeaveForPendingUidt(
        context: Context,
        request: DownloadExecutionRequest
    ): Boolean {
        if (
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                !request.userInitiated
        ) {
            return false
        }
        return UidtDownloadJobService.hasPendingJob(context, request.operationId)
    }

    private fun tryAcquireHostAdmission(context: Context, operationId: String): Boolean {
        return operationStore.tryAcquireHostAdmission(
            context = context,
            operationId = operationId,
            capacity = HOST_ADMISSION_CAPACITY
        )
    }

    private fun enqueueDeferredSchedule(
        context: Context,
        request: DownloadExecutionRequest
    ) {
        deferredRequests.enqueue(request)
        triggerDeferredSchedules(context.applicationContext)
    }

    private fun triggerDeferredSchedules(context: Context) {
        if (!deferredSchedulingRunning.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        deferredSchedulingScope.launch {
            var deferredRetryCount = 0
            try {
                while (true) {
                    val request = deferredRequests.poll()
                    if (request == null) {
                        if (deferredRequests.isEmpty()) {
                            return@launch
                        }
                        delay(HOST_ADMISSION_RETRY_DELAY_MS)
                        continue
                    }
                    when (val result = schedule(appContext, request)) {
                        is DownloadExecutionSchedule.Scheduled -> {
                            deferredRequests.remove(request)
                            deferredRetryCount = 0
                        }

                        is DownloadExecutionSchedule.Deferred -> {
                            deferredRequests.requeue(request)
                            deferredRetryCount++
                        }

                        is DownloadExecutionSchedule.Rejected -> {
                            if (result.retryable) {
                                deferredRequests.requeue(request)
                                deferredRetryCount++
                            } else {
                                deferredRequests.remove(request)
                                deferredRetryCount = 0
                            }
                        }
                    }
                    if (deferredRetryCount >= deferredRetryLimit()) {
                        deferredRetryCount = 0
                        delay(HOST_ADMISSION_RETRY_DELAY_MS)
                    }
                }
            } finally {
                deferredSchedulingRunning.set(false)
                if (!deferredRequests.isEmpty()) {
                    triggerDeferredSchedules(appContext)
                }
            }
        }
    }

    private fun deferredRetryLimit(): Int {
        return deferredRequests.size()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DEFERRED_SCHEDULES_PER_PASS)
    }

    internal fun releaseHandoffAdmissionIfIdle(
        context: Context,
        operationId: String
    ) {
        releaseHostAdmissionIfIdle(context.applicationContext, operationId)
    }

    private fun releaseHostAdmissionIfIdle(context: Context, operationId: String) {
        val released = synchronized(executionAdmissionLock) {
            if (executingOperationIds.contains(operationId)) {
                false
            } else {
                runCatching {
                    operationStore.releaseHostAdmission(context, operationId)
                }
                true
            }
        }
        if (released) {
            triggerDeferredSchedules(context.applicationContext)
        }
    }
}

internal fun shouldHandleHostStop(operationState: String?): Boolean {
    return operationState in setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE", "STOPPED") ||
        operationState in INTERRUPTED_DOWNLOAD_OPERATION_STATES
}

internal fun requiresPumpRetry(result: DownloadExecutionResult): Boolean {
    return result == DownloadExecutionResult.Retry || result is DownloadExecutionResult.Failed
}

/** 宿主被系统回收后重新排入同一个持久 operation */
internal fun canScheduleDownloadOperation(currentState: String?): Boolean {
    return currentState == null ||
        currentState in setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE") ||
        currentState in INTERRUPTED_DOWNLOAD_OPERATION_STATES
}

internal fun shouldBlockHostReschedule(
    preventReschedule: Boolean,
    alreadyStoppedByUser: Boolean
): Boolean = preventReschedule || alreadyStoppedByUser

internal fun resolveConcurrentExecutionResult(
    systemRetryStopPending: Boolean
): DownloadExecutionResult {
    return if (systemRetryStopPending) {
        DownloadExecutionResult.Retry
    } else {
        DownloadExecutionResult.AlreadyHandled
    }
}

internal fun resolvePreExecutionResult(
    currentState: String?
): DownloadExecutionResult? {
    return when (currentState) {
        null,
        "INVALID" -> DownloadExecutionResult.MissingOperation

        "CANCEL_REQUESTED",
        "CANCELLED" -> DownloadExecutionResult.Cancelled

        METADATA_ACTION_REQUIRED_OPERATION_STATE ->
            DownloadExecutionResult.UserActionRequired

        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "FINALIZED",
        "COMPLETED" -> DownloadExecutionResult.AlreadyHandled

        else -> null
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun hasPendingUidtJob(context: Context, operationId: String): Boolean {
    return UidtDownloadJobService.hasPendingJob(context, operationId)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun latestProcessExit(
    activityManager: ActivityManager,
    packageName: String
): ApplicationExitInfo? {
    return runCatching {
        activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)
            .firstOrNull()
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.R)
internal fun isUserRequestedProcessExitReason(reason: Int): Boolean {
    return reason == ApplicationExitInfo.REASON_USER_STOPPED
}

private const val PROCESS_EXIT_PREFERENCES = "download_execution_host"
private const val PROCESS_EXIT_TIMESTAMP_KEY = "last_user_requested_exit_timestamp"

object DownloadExecutionHosts {
    val default: DownloadExecutionHost = DefaultDownloadExecutionHost()

    internal suspend fun pump(context: Context): DownloadExecutionPumpResult {
        return default.pump(context)
    }

    fun cancelAllOwned(context: Context) {
        (default as? DefaultDownloadExecutionHost)?.cancelAllOwned(context)
    }

    internal fun releaseHandoffAdmissionIfIdle(
        context: Context,
        operationId: String
    ) {
        (default as? DefaultDownloadExecutionHost)?.releaseHandoffAdmissionIfIdle(
            context = context,
            operationId = operationId
        )
    }

    internal fun stopForSystemRetry(
        context: Context,
        operationId: String
    ) {
        val host = default
        if (host is DefaultDownloadExecutionHost) {
            host.stopForSystemRetry(context, operationId)
        } else {
            host.stop(
                context = context,
                operationId = operationId,
                preventReschedule = false
            )
        }
    }

    internal fun prepareSchedulerStop(
        operationId: String,
        preventReschedule: Boolean
    ) {
        (default as? DefaultDownloadExecutionHost)?.prepareSchedulerStop(
            operationId = operationId,
            preventReschedule = preventReschedule
        )
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun scheduleUidt(
    context: Context,
    operationId: String
): Boolean {
    return UidtDownloadJobService.schedule(context, operationId)
}

internal fun selectDownloadExecutionBackend(
    sdkInt: Int,
    userInitiated: Boolean
): DownloadExecutionSchedule.Backend {
    return if (
        userInitiated &&
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        DownloadExecutionSchedule.Backend.UIDT_JOB
    } else {
        DownloadExecutionSchedule.Backend.FOREGROUND_WORK
    }
}

private fun scheduleUidtIfSupported(
    context: Context,
    operationId: String,
    sdkInt: Int
): Boolean {
    if (
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        return false
    }
    return scheduleUidt(context, operationId)
}

private const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val TERMINAL_OPERATION_PRUNE_LIMIT = 64
private const val HOST_ADMISSION_RETRY_DELAY_MS = 200L
private const val MAX_DEFERRED_SCHEDULES_PER_PASS = 32

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun cancelUidt(
    context: Context,
    operationId: String
) {
    UidtDownloadJobService.cancel(context, operationId)
}

internal fun normalizeDownloadOperationId(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (normalized.length > 128) return null
    if (normalized == "." || normalized == "..") return null
    if (normalized.any { character -> character == '/' || character == '\\' }) {
        return null
    }
    if (normalized.any { character ->
            character.isWhitespace() ||
                character.code < 0x21 ||
                character.code > 0x7e
        }
    ) {
        return null
    }
    return normalized
}
