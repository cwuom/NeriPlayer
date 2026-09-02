package moe.ouom.neriplayer.core.download.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import java.security.MessageDigest
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
                !pumpScheduleCoordinator.claimWorker(pumpGeneration)
        ) {
            return@withContext Result.success()
        }
        var enteredHostExecution = false
        try {
            setForeground(createForegroundInfo(applicationContext, executionId))
            enteredHostExecution = true
            if (operationId == null) {
                val pumpResult = DownloadExecutionHosts.pump(applicationContext)
                val workerResult = pumpResult.toWorkerResult()
                markPumpFinished(
                    context = applicationContext,
                    generation = pumpGeneration,
                    workWillRetry = pumpResult == DownloadExecutionPumpResult.Retry,
                    continueSoon = pumpResult == DownloadExecutionPumpResult.ContinueSoon
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
                    workWillRetry = true
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
        private const val LEGACY_PUMP_GENERATION = 0L
        private val PUMP_SUCCESSOR_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
        private const val WORK_NAME_PREFIX = "download_execution_"
        private const val WORK_TAG_PREFIX = "download_execution_operation_"
        private const val ALL_DOWNLOAD_WORK_TAG = "download_execution_all"
        private const val CHANNEL_ID = "download_execution"
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
            val generation = pumpScheduleCoordinator.request() ?: return true
            val delayMs = initialDelayMs.coerceAtLeast(0L)
            return runCatching {
                val operation = WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        PUMP_WORK_NAME,
                        existingWorkPolicy,
                        buildPumpRequest(
                            initialDelayMs = delayMs,
                            generation = generation
                        )
                    )
                observePumpEnqueue(
                    context = context.applicationContext,
                    generation = generation,
                    operation = operation
                )
                true
            }.onFailure { error ->
                handlePumpEnqueueFailure(
                    context = context.applicationContext,
                    generation = generation,
                    error = error
                )
            }.getOrDefault(false)
        }

        private fun markPumpFinished(
            context: Context,
            generation: Long,
            workWillRetry: Boolean,
            continueSoon: Boolean = false
        ) {
            val completion = if (continueSoon) {
                pumpScheduleCoordinator.completeWithSuccessor(generation)
            } else {
                pumpScheduleCoordinator.complete(
                    generation = generation,
                    workWillRetry = workWillRetry
                )
            }
            if (completion == DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR) {
                schedulePumpSuccessor(
                    context = context,
                    initialDelayMs = if (continueSoon) UIDT_SHARED_PUMP_GRACE_MS else 0L
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
    private var successorRequested = false

    fun request(): Long? = synchronized(lock) {
        if (activeGeneration != null) {
            successorRequested = true
            return@synchronized null
        }
        latestGeneration += 1L
        latestGeneration.also { generation -> activeGeneration = generation }
    }

    fun claimWorker(generation: Long): Boolean = synchronized(lock) {
        if (generation < latestGeneration) {
            return@synchronized false
        }
        when (activeGeneration) {
            generation -> true
            null -> {
                latestGeneration = generation
                activeGeneration = generation
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
        if (workWillRetry) {
            return@synchronized DownloadPumpCompletion.RETRYING
        }
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
        // 当前 worker 只是遇到 UIDT/并行 host 竞争，不属于真实失败。
        // 释放本代并把运行期间的新请求折叠进一个短延迟 successor。
        activeGeneration = null
        successorRequested = false
        DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR
    }

    fun failEnqueue(generation: Long): Boolean = synchronized(lock) {
        if (activeGeneration != generation) {
            return@synchronized false
        }
        activeGeneration = null
        successorRequested = false
        true
    }

    fun canRetry(generation: Long): Boolean = synchronized(lock) {
        activeGeneration == null && latestGeneration == generation
    }

    fun invalidate() = synchronized(lock) {
        latestGeneration += 1L
        activeGeneration = null
        successorRequested = false
    }
}

private fun createForegroundInfo(
    context: Context,
    operationId: String
): ForegroundInfo {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
        NotificationChannel(
            "download_execution",
            context.getString(R.string.download_execution_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
    )
    val notification = NotificationCompat.Builder(context, "download_execution")
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(context.getString(R.string.download_execution_notification_title))
        .setContentText(
            context.getString(
                R.string.download_execution_notification_content,
                operationId
            )
        )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
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
    internal const val FOREGROUND_MIN = 0x4000_0000
    internal const val FOREGROUND_MAX = 0x4fff_ffff
    internal const val UIDT_MIN = 0x5000_0000
    internal const val UIDT_MAX = 0x5fff_ffff
    private const val PARTITION_MASK = 0x0fff_ffff

    fun foreground(operationId: String): Int {
        return inPartition(operationId, FOREGROUND_MIN)
    }

    fun uidt(operationId: String): Int {
        return inPartition(operationId, UIDT_MIN)
    }

    private fun inPartition(operationId: String, partitionStart: Int): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(operationId.toByteArray(Charsets.UTF_8))
        var value = 0
        repeat(4) { index ->
            value = (value shl 8) or (digest[index].toInt() and 0xff)
        }
        return partitionStart + (value and PARTITION_MASK)
    }
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
        DownloadExecutionPumpResult.ContinueSoon -> ListenableWorker.Result.success()
        DownloadExecutionPumpResult.Retry -> ListenableWorker.Result.retry()
    }
}
