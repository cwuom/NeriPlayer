package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** translates WorkManager events to the shared download host */
class ForegroundDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawOperationId = inputData.getString(OPERATION_ID_KEY)
        val operationId = rawOperationId?.let(::normalizeDownloadOperationId)
        if (rawOperationId != null && operationId == null) {
            return@withContext Result.failure()
        }
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                operationId?.let(AudioDownloadManager::pauseOperationDownloadForExecutionHost)
            }
        }
        if (
            shouldRetireLegacyPerOperationWork(
                hasOperationId = operationId != null,
                sdkInt = Build.VERSION.SDK_INT
            )
        ) {
            // API 34+ 已由 UIDT 和共享泵接管，旧版 per-operation Work 只能退出
            return@withContext Result.success()
        }
        val executionId = operationId ?: PUMP_OPERATION_ID
        val pumpGeneration = inputData.getLong(
            PUMP_GENERATION_KEY,
            LEGACY_PUMP_GENERATION
        )
        if (
            operationId == null &&
                (
                    PersistentDownloadClearFenceStore.isActive(applicationContext) ||
                        ManagedDownloadDirectoryMutationFence.isActiveFast(applicationContext) ||
                        ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(
                            applicationContext
                        ) ||
                        PersistentDownloadedSongDeleteIntentStore.hasPending(applicationContext)
                    )
        ) {
            pumpScheduleCoordinator.cancelUnclaimed(pumpGeneration)
            return@withContext Result.success()
        }
        if (
            operationId == null &&
                !pumpScheduleCoordinator.claimWorker(pumpGeneration)
        ) {
            return@withContext Result.success()
        }
        if (
            operationId == null &&
            (
                    PersistentDownloadClearFenceStore.isActive(applicationContext) ||
                    ManagedDownloadDirectoryMutationFence.isActiveFast(applicationContext) ||
                    ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(
                        applicationContext
                    ) ||
                    PersistentDownloadedSongDeleteIntentStore.hasPending(applicationContext)
                )
        ) {
            markPumpFinished(
                context = applicationContext,
                generation = pumpGeneration,
                workWillRetry = false
            )
            return@withContext Result.success()
        }
        var enteredHostExecution = false
        val notificationOwner = "worker:${getId()}"
        var notificationAcquired = false
        try {
            DownloadExecutionNotificationController.acquire(
                context = applicationContext,
                owner = notificationOwner
            )
            notificationAcquired = true
            setForeground(createForegroundInfo(applicationContext, executionId))
            enteredHostExecution = true
            if (operationId == null) {
                val pumpResult = DownloadExecutionHosts.pump(applicationContext)
                val workerResult = pumpResult.toWorkerResult()
                markPumpFinished(
                    context = applicationContext,
                    generation = pumpGeneration,
                    workWillRetry = pumpResult == DownloadExecutionPumpResult.Retry,
                    continueSoon = pumpResult == DownloadExecutionPumpResult.ContinueSoon,
                    continueAfterRetry =
                        pumpResult == DownloadExecutionPumpResult.ContinueAfterRetry
                )
                return@withContext workerResult
            }
            DownloadExecutionHosts.default.execute(applicationContext, operationId)
        } catch (cancellation: CancellationException) {
            operationId?.let { normalizedId ->
                DownloadExecutionHosts.default.stop(
                    context = applicationContext,
                    operationId = normalizedId,
                    preventReschedule = false
                )
            }
            if (operationId == null) {
                markPumpFinished(
                    context = applicationContext,
                    generation = pumpGeneration,
                    workWillRetry = false,
                    continueAfterRetry = true
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            if (!enteredHostExecution && operationId != null) {
                DownloadExecutionHosts.releaseHandoffAdmissionIfIdle(
                    context = applicationContext,
                    operationId = operationId
                )
            }
            NPLogger.w(
                "NERI-DownloadWorker",
                "下载 Worker 执行异常: operationId=$executionId, error=${error.message}",
                error
            )
            if (operationId == null) {
                markPumpFinished(
                    context = applicationContext,
                    generation = pumpGeneration,
                    workWillRetry = true
                )
            }
            return@withContext Result.retry()
        } finally {
            if (notificationAcquired) {
                DownloadExecutionNotificationController.release(
                    context = applicationContext,
                    owner = notificationOwner
                )
            }
        }.toWorkerResult()
    }

    companion object {
        internal const val OPERATION_ID_KEY = "operation_id"
        internal const val PUMP_WORK_NAME = "download_execution_pump"
        internal const val PUMP_OPERATION_ID = "download-pump"
        internal const val PUMP_GENERATION_KEY = "pump_generation"
        private const val PUMP_WORK_TAG = "download_execution_pump"
        private const val PUMP_RETRY_BACKOFF_MS = 10_000L
        private const val PUMP_ENQUEUE_RETRY_DELAY_MS = 10_000L
        private const val PUMP_OPERATION_RETRY_DELAY_MS = 1_000L
        private const val LEGACY_PUMP_GENERATION = 0L
        private val PUMP_SUCCESSOR_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
        private const val WORK_NAME_PREFIX = "download_execution_"
        private const val WORK_TAG_PREFIX = "download_execution_operation_"
        private const val ALL_DOWNLOAD_WORK_TAG = "download_execution_all"
        internal val fallbackExistingWorkPolicy = ExistingWorkPolicy.KEEP
        internal val pumpExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
        private val pumpScheduleCoordinator = DownloadPumpScheduleCoordinator()
        private val pumpRetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val pumpCallbackExecutor = Executor { runnable -> runnable.run() }

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            normalizeDownloadOperationId(operationId) ?: return false
            return schedulePump(context)
        }

        /** keeps compatibility with per-operation fallback work queued by older versions */
        fun scheduleFallback(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            if (PersistentDownloadClearFenceStore.isActive(context.applicationContext)) {
                return false
            }
            if (shouldRouteFallbackToSharedPump(Build.VERSION.SDK_INT)) {
                return schedulePump(
                    context = context,
                    initialDelayMs = UIDT_START_GRACE_MS
                )
            }
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        fallbackWorkName(normalizedId),
                        fallbackExistingWorkPolicy,
                        buildFallbackRequest(normalizedId)
                    )
                true
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-DownloadWorker",
                    "UIDT fallback 调度失败，保留 Room operation 等待恢复: " +
                        "operationId=$normalizedId, error=${error.message}",
                    error
                )
            }.getOrDefault(false)
        }

        /** 所有新下载共用一个持久泵，operation 载荷始终保存在 Room */
        internal fun schedulePump(
            context: Context,
            initialDelayMs: Long = 0L
        ): Boolean {
            return schedulePumpWithPolicy(
                context = context,
                existingWorkPolicy = pumpExistingWorkPolicy,
                initialDelayMs = initialDelayMs
            )
        }

        private fun schedulePumpWithPolicy(
            context: Context,
            existingWorkPolicy: ExistingWorkPolicy,
            initialDelayMs: Long
        ): Boolean {
            val appContext = context.applicationContext
            if (isPumpBlocked(appContext)) {
                return false
            }
            val generation = pumpScheduleCoordinator.request() ?: return true
            if (isPumpBlocked(appContext)) {
                pumpScheduleCoordinator.cancelUnclaimed(generation)
                return false
            }
            return enqueuePumpGeneration(
                context = appContext,
                generation = generation,
                existingWorkPolicy = existingWorkPolicy,
                initialDelayMs = initialDelayMs
            )
        }

        /** 预留同一个协调器代次，供进程内泵和持久 Worker 共同完成 */
        internal fun reservePumpGeneration(): Long? {
            return pumpScheduleCoordinator.reserveImmediate()
        }

        /** 为已预留代次入队，入队失败仍由协调器保留有界重试入口 */
        internal fun schedulePumpGeneration(
            context: Context,
            generation: Long,
            existingWorkPolicy: ExistingWorkPolicy = pumpExistingWorkPolicy,
            initialDelayMs: Long = 0L
        ): Boolean {
            val appContext = context.applicationContext
            if (isPumpBlocked(appContext)) {
                pumpScheduleCoordinator.cancelUnclaimed(generation)
                return false
            }
            return enqueuePumpGeneration(
                context = appContext,
                generation = generation,
                existingWorkPolicy = existingWorkPolicy,
                initialDelayMs = initialDelayMs
            )
        }

        /** 进程内泵结束时必须提交同一代次，避免留下永久 active generation */
        internal fun completeImmediatePump(
            context: Context,
            generation: Long,
            result: DownloadExecutionPumpResult
        ) {
            // 进程内泵没有 WorkManager 的 retry 归属。即使同时入队的 Worker
            // 还未启动或随后入队失败，也必须释放当前代次并安排新的持久泵，
            // 否则 active generation 会永久挡住后续下载
            val completion = pumpScheduleCoordinator.completeImmediate(generation, result)
            if (completion == DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR) {
                val successorDelayMs = when (result) {
                    DownloadExecutionPumpResult.ContinueSoon -> UIDT_SHARED_PUMP_GRACE_MS
                    DownloadExecutionPumpResult.Retry,
                    DownloadExecutionPumpResult.ContinueAfterRetry ->
                        PUMP_OPERATION_RETRY_DELAY_MS

                    DownloadExecutionPumpResult.Completed -> 0L
                }
                schedulePumpSuccessor(
                    context = context.applicationContext,
                    initialDelayMs = successorDelayMs
                )
            }
        }

        private fun enqueuePumpGeneration(
            context: Context,
            generation: Long,
            existingWorkPolicy: ExistingWorkPolicy,
            initialDelayMs: Long
        ): Boolean {
            val delayMs = initialDelayMs.coerceAtLeast(0L)
            return runCatching {
                val operation = WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        PUMP_WORK_NAME,
                        existingWorkPolicy,
                        buildPumpRequest(
                            initialDelayMs = delayMs,
                            generation = generation
                        )
                    )
                observePumpEnqueue(
                    context = context,
                    generation = generation,
                    operation = operation
                )
                true
            }.onFailure { error ->
                handlePumpEnqueueFailure(
                    context = context,
                    generation = generation,
                    error = error
                )
            }.getOrDefault(false)
        }

        internal fun isPumpBlocked(context: Context): Boolean {
            val appContext = context.applicationContext
            return PersistentDownloadClearFenceStore.isActive(appContext) ||
                ManagedDownloadDirectoryMutationFence.isActiveFast(appContext) ||
                ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(appContext) ||
                PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
        }

        private fun markPumpFinished(
            context: Context,
            generation: Long,
            workWillRetry: Boolean,
            continueSoon: Boolean = false,
            continueAfterRetry: Boolean = false
        ) {
            val shouldScheduleSuccessor = continueSoon || continueAfterRetry
            val completion = if (shouldScheduleSuccessor) {
                pumpScheduleCoordinator.completeWithSuccessor(generation)
            } else {
                pumpScheduleCoordinator.complete(
                    generation = generation,
                    workWillRetry = workWillRetry
                )
            }
            if (completion == DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR) {
                val successorDelayMs = when {
                    continueAfterRetry -> PUMP_OPERATION_RETRY_DELAY_MS
                    continueSoon -> UIDT_SHARED_PUMP_GRACE_MS
                    else -> 0L
                }
                schedulePumpSuccessor(
                    context = context,
                    initialDelayMs = successorDelayMs
                )
            }
        }

        private fun schedulePumpSuccessor(
            context: Context,
            initialDelayMs: Long = 0L
        ): Boolean {
            return schedulePumpWithPolicy(
                context = context,
                existingWorkPolicy = PUMP_SUCCESSOR_WORK_POLICY,
                initialDelayMs = initialDelayMs
            )
        }

        private fun observePumpEnqueue(
            context: Context,
            generation: Long,
            operation: Operation
        ) {
            operation.result.addListener(
                Runnable {
                    val error = runCatching { operation.result.get() }.exceptionOrNull()
                        ?: return@Runnable
                    handlePumpEnqueueFailure(
                        context = context,
                        generation = generation,
                        error = error
                    )
                },
                pumpCallbackExecutor
            )
        }

        private fun handlePumpEnqueueFailure(
            context: Context,
            generation: Long,
            error: Throwable
        ) {
            if (!pumpScheduleCoordinator.failEnqueue(generation)) {
                return
            }
            NPLogger.w(
                "NERI-DownloadWorker",
                "全局下载泵调度失败，保留 Room operation 等待恢复: ${error.message}",
                error
            )
            pumpRetryScope.launch {
                delay(PUMP_ENQUEUE_RETRY_DELAY_MS)
                if (pumpScheduleCoordinator.canRetry(generation)) {
                    schedulePump(context)
                }
            }
        }

        fun cancel(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(uniqueWorkName(normalizedId))
                workManager.cancelUniqueWork(fallbackWorkName(normalizedId))
            }
        }

        fun cancelAll(
            context: Context,
            operationIds: Collection<String>
        ) {
            val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
            if (normalizedIds.isEmpty()) return
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                normalizedIds.forEach { operationId ->
                    workManager.cancelUniqueWork(uniqueWorkName(operationId))
                    workManager.cancelUniqueWork(fallbackWorkName(operationId))
                }
            }
        }

        fun cancelAllOwned(context: Context) {
            pumpScheduleCoordinator.invalidate()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelAllWorkByTag(ALL_DOWNLOAD_WORK_TAG)
            }
        }

        internal fun cancelFallback(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(uniqueWorkName(normalizedId))
                workManager.cancelUniqueWork(fallbackWorkName(normalizedId))
            }
        }

        internal fun buildRequest(operationId: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setInputData(workDataOf(OPERATION_ID_KEY to operationId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG_PREFIX + operationId)
                .addTag(ALL_DOWNLOAD_WORK_TAG)
                .build()
        }

        internal fun buildFallbackRequest(operationId: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setInitialDelay(UIDT_START_GRACE_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(OPERATION_ID_KEY to operationId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG_PREFIX + "fallback_" + operationId)
                .addTag(ALL_DOWNLOAD_WORK_TAG)
                .build()
        }

        internal fun buildPumpRequest(
            initialDelayMs: Long = 0L,
            generation: Long = LEGACY_PUMP_GENERATION
        ): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    PUMP_RETRY_BACKOFF_MS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(workDataOf(PUMP_GENERATION_KEY to generation))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(PUMP_WORK_TAG)
                .addTag(ALL_DOWNLOAD_WORK_TAG)
            if (initialDelayMs > 0L) {
                builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            return builder.build()
        }

        internal fun uniqueWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + operationId
        }

        internal fun fallbackWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + "fallback_" + operationId
        }

        private const val UIDT_START_GRACE_MS = 250L
    }
}

internal fun shouldRetireLegacyPerOperationWork(
    hasOperationId: Boolean,
    sdkInt: Int
): Boolean {
    return hasOperationId && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

internal fun shouldRouteFallbackToSharedPump(sdkInt: Int): Boolean {
    return sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

internal enum class DownloadPumpCompletion {
    IGNORED,
    RETRYING,
    COMPLETED,
    COMPLETED_WITH_SUCCESSOR
}

/** keeps stale WorkManager callbacks from changing a newer pump request */
internal class DownloadPumpScheduleCoordinator {
    private val lock = Any()
    private var latestGeneration = 0L
    private var activeGeneration: Long? = null
    private var claimedGeneration: Long? = null
    /** 进程内泵完成后仍保护重试代次，直到 Worker 真正接管 */
    private var immediateGeneration: Long? = null
    private var successorRequested = false

    fun request(): Long? = synchronized(lock) {
        if (activeGeneration != null) {
            successorRequested = true
            return@synchronized null
        }
        latestGeneration += 1L
        latestGeneration.also { generation ->
            activeGeneration = generation
            claimedGeneration = null
        }
    }

    fun reserveImmediate(): Long? = synchronized(lock) {
        val generation = request() ?: return@synchronized null
        claimedGeneration = generation
        immediateGeneration = generation
        generation
    }

    fun claimWorker(generation: Long): Boolean = synchronized(lock) {
        if (generation < latestGeneration) {
            return@synchronized false
        }
        when (activeGeneration) {
            generation -> {
                if (claimedGeneration == generation) {
                    return@synchronized false
                }
                // 进程内泵的重试代次可以由持久 Worker 接管，接管后
                // 异步 enqueue 回调不能再把运行中的 Worker 清掉
                immediateGeneration = null
                claimedGeneration = generation
                true
            }
            null -> {
                latestGeneration = generation
                activeGeneration = generation
                claimedGeneration = generation
                immediateGeneration = null
                true
            }

            else -> false
        }
    }

    fun complete(
        generation: Long,
        workWillRetry: Boolean
    ): DownloadPumpCompletion = synchronized(lock) {
        if (activeGeneration != generation) {
            return@synchronized DownloadPumpCompletion.IGNORED
        }
        claimedGeneration = null
        if (workWillRetry) {
            return@synchronized DownloadPumpCompletion.RETRYING
        }
        immediateGeneration = null
        activeGeneration = null
        if (successorRequested) {
            successorRequested = false
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR
        } else {
            DownloadPumpCompletion.COMPLETED
        }
    }

    fun completeWithSuccessor(generation: Long): DownloadPumpCompletion = synchronized(lock) {
        if (activeGeneration != generation) {
            return@synchronized DownloadPumpCompletion.IGNORED
        }
        claimedGeneration = null
        immediateGeneration = null
        // 当前 worker 只是遇到 UIDT/并行 host 竞争，不属于真实失败。
        // 释放本代并把运行期间的新请求折叠进一个短延迟 successor。
        activeGeneration = null
        successorRequested = false
        DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR
    }

    fun completeImmediate(
        generation: Long,
        result: DownloadExecutionPumpResult
    ): DownloadPumpCompletion {
        return if (result == DownloadExecutionPumpResult.Completed) {
            complete(generation, workWillRetry = false)
        } else {
            // 进程内泵没有可依赖的 WorkManager retry owner，所有非完成结果
            // 都必须释放当前代次并交给新的持久 successor
            completeWithSuccessor(generation)
        }
    }

    fun failEnqueue(generation: Long): Boolean = synchronized(lock) {
        if (activeGeneration != generation) {
            return@synchronized false
        }
        // enqueue 的异步失败回调可能晚于进程内泵或 Worker 的 claim。
        // 这两种 owner 都必须自行提交完成结果，不能由回调释放代次
        if (claimedGeneration == generation || immediateGeneration == generation) {
            return@synchronized false
        }
        claimedGeneration = null
        immediateGeneration = null
        activeGeneration = null
        successorRequested = false
        true
    }

    fun canRetry(generation: Long): Boolean = synchronized(lock) {
        activeGeneration == null && latestGeneration == generation
    }

    fun cancelUnclaimed(generation: Long): Boolean = synchronized(lock) {
        if (activeGeneration != generation || claimedGeneration == generation) {
            return@synchronized false
        }
        immediateGeneration = null
        activeGeneration = null
        successorRequested = false
        true
    }

    fun invalidate() = synchronized(lock) {
        latestGeneration += 1L
        activeGeneration = null
        claimedGeneration = null
        immediateGeneration = null
        successorRequested = false
    }
}

private fun createForegroundInfo(
    context: Context,
    operationId: String
): ForegroundInfo {
    val notification = buildDownloadExecutionNotification(context)
    val notificationId = DownloadExecutionNotificationIds.foreground(operationId)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    } else {
        ForegroundInfo(notificationId, notification)
    }
}

internal object DownloadExecutionNotificationIds {
    /** 保留旧区间常量，供升级残留清理和兼容性检查使用 */
    internal const val FOREGROUND_MIN = LEGACY_FOREGROUND_NOTIFICATION_MIN
    internal const val FOREGROUND_MAX = LEGACY_FOREGROUND_NOTIFICATION_MAX
    internal const val UIDT_MIN = LEGACY_UIDT_NOTIFICATION_MIN
    internal const val UIDT_MAX = LEGACY_UIDT_NOTIFICATION_MAX

    fun foreground(@Suppress("UNUSED_PARAMETER") operationId: String): Int =
        DOWNLOAD_EXECUTION_NOTIFICATION_ID

    fun uidt(@Suppress("UNUSED_PARAMETER") operationId: String): Int =
        DOWNLOAD_EXECUTION_NOTIFICATION_ID
}

internal fun DownloadExecutionResult.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        DownloadExecutionResult.Accepted,
        DownloadExecutionResult.AlreadyHandled,
        DownloadExecutionResult.Cancelled,
        DownloadExecutionResult.UserStopped,
        DownloadExecutionResult.UserActionRequired,
        DownloadExecutionResult.NetworkPolicyWaiting,
        DownloadExecutionResult.MissingOperation -> ListenableWorker.Result.success()
        DownloadExecutionResult.Retry -> ListenableWorker.Result.retry()
        is DownloadExecutionResult.Failed -> ListenableWorker.Result.retry()
    }
}

internal fun DownloadExecutionPumpResult.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        DownloadExecutionPumpResult.Completed,
        DownloadExecutionPumpResult.ContinueSoon,
        DownloadExecutionPumpResult.ContinueAfterRetry -> ListenableWorker.Result.success()
        DownloadExecutionPumpResult.Retry -> ListenableWorker.Result.retry()
    }
}
