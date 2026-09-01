package moe.ouom.neriplayer.core.download.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val UIDT_SHARED_PUMP_GRACE_MS = 3_000L

 /** 把 API 34 的用户发起任务接入共享下载宿主 */
 // 任务编号保留在宿主专用范围，WorkManager 使用另一段编号
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UidtDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()
    private val completionGates = ConcurrentHashMap<Int, UidtJobCompletionGate>()

    override fun onStartJob(params: JobParameters): Boolean {
        val operationId = params.extras
            .getString(OPERATION_ID_KEY)
            ?.let(::normalizeDownloadOperationId)
        if (operationId == null) {
            jobFinished(params, false)
            return false
        }
        markJobStarted(operationId, params.jobId)
        NPLogger.d(TAG, "UIDT 下载任务开始: operationId=$operationId, jobId=${params.jobId}")
        try {
            setNotification(
                params,
                DownloadExecutionNotificationIds.uidt(operationId),
                buildNotification(operationId),
                JOB_END_NOTIFICATION_POLICY_REMOVE
            )
        } catch (error: Throwable) {
            NPLogger.e(
                TAG,
                "UIDT 前台通知初始化失败: operationId=$operationId, error=${error.message}",
                error
            )
            val completionGate = UidtJobCompletionGate()
            lateinit var failureJob: Job
            failureJob = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    DownloadExecutionHosts.releaseHandoffAdmissionIfIdle(
                        context = applicationContext,
                        operationId = operationId
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        runningJobs.remove(params.jobId, failureJob)
                        completionGates.remove(params.jobId, completionGate)
                        markJobFinished(operationId, params.jobId)
                        if (completionGate.shouldReportCompletion()) {
                            jobFinished(params, true)
                        }
                    }
                }
            }
            completionGates[params.jobId] = completionGate
            runningJobs[params.jobId] = failureJob
            failureJob.start()
            return true
        }
        val completionGate = UidtJobCompletionGate()
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var wantsReschedule = false
            try {
                val result = DownloadExecutionHosts.default.execute(applicationContext, operationId)
                wantsReschedule = shouldRescheduleUidtExecution(result)
                if (
                    shouldCancelUidtFallback(
                        result = result,
                        fallbackExecuting = DownloadExecutionHosts.default.isExecuting(
                            operationId
                        )
                    )
                ) {
                    ForegroundDownloadWorker.cancelFallback(
                        context = applicationContext,
                        operationId = operationId
                    )
                }
                NPLogger.d(TAG, "UIDT 下载任务结束: operationId=$operationId, result=$result")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                wantsReschedule = true
                NPLogger.e(
                    TAG,
                    "UIDT 下载任务异常: operationId=$operationId, " +
                        "error=${error.message}",
                    error
                )
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    runningJobs.remove(params.jobId, job)
                    completionGates.remove(params.jobId, completionGate)
                    markJobFinished(operationId, params.jobId)
                    if (completionGate.shouldReportCompletion()) {
                        jobFinished(params, wantsReschedule)
                    }
                }
            }
        }
        completionGates[params.jobId] = completionGate
        runningJobs[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val stopAction = resolveUidtStopAction(
            stopReason = params.stopReason,
            sdkInt = Build.VERSION.SDK_INT
        )
        val userStopped = shouldMarkUidtJobStopped(
            stopReason = params.stopReason,
            sdkInt = Build.VERSION.SDK_INT
        )
        val operationId = params.extras
            .getString(OPERATION_ID_KEY)
            ?.let(::normalizeDownloadOperationId)
        operationId?.let { normalizedId ->
            DownloadExecutionHosts.prepareSchedulerStop(
                operationId = normalizedId,
                preventReschedule = userStopped
            )
        }
        completionGates[params.jobId]?.markSchedulerStopped()
        runningJobs.remove(params.jobId)?.cancel(CancellationException("UIDT job stopped"))
        operationId?.let { markJobFinished(it, params.jobId) }
        if (operationId != null) {
            if (stopAction == UidtStopAction.STOP_AND_CANCEL_BACKENDS) {
                ForegroundDownloadWorker.cancelFallback(
                    context = applicationContext,
                    operationId = operationId
                )
            }
            val accepted = UidtStopCoordinators.default.enqueue(
                UidtStopRequest(
                    context = applicationContext,
                    operationId = operationId,
                    action = stopAction,
                    preventReschedule = userStopped
                )
            )
            if (!accepted) {
                NPLogger.e(TAG, "UIDT 停止收敛队列不可用: operationId=$operationId")
            }
        }
        return stopAction == UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS
    }

    override fun onDestroy() {
        sealUidtCompletionGates(completionGates.values)
        serviceScope.cancel()
        runningJobs.clear()
        completionGates.clear()
        super.onDestroy()
    }

    private fun buildNotification(operationId: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_execution_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(getString(R.string.download_execution_notification_title))
            .setContentText(
                getString(
                    R.string.download_execution_notification_content,
                    operationId
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "NERI-DownloadUidt"
        internal const val OPERATION_ID_KEY = "operation_id"
        private const val CHANNEL_ID = "download_execution"
        internal const val UIDT_JOB_ID_MIN = 100_000
        internal const val UIDT_JOB_ID_MAX = 900_099_999
        internal const val UIDT_SCHEDULED_AT_ELAPSED_MS_KEY = "scheduled_at_elapsed_ms"
        private val scheduleLock = Any()
        /** JobScheduler.allPendingJobs 需要 Binder 往返，短期进程缓存可避免大批歌曲重复列举
         * 缓存保留所有已占用 ID，包括附加数据损坏的任务，真正调度前仍会做最后碰撞检查
         */
        private const val PENDING_JOB_INDEX_TTL_MS = 500L
        private const val UIDT_PENDING_JOB_LIMIT = MAX_DOWNLOAD_PARALLELISM
        private data class PendingJobIndex(
            val jobIdsByOperation: Map<String, Set<Int>> = emptyMap(),
            val occupiedJobIds: Set<Int> = emptySet(),
            val ownedJobCount: Int = 0
        )

        private var pendingJobIndexAtElapsedMs: Long? = null
        private var pendingJobIndexSnapshot = PendingJobIndex()
        private val reservedJobIdsByOperation = mutableMapOf<String, MutableSet<Int>>()
        private val reservedJobIds = linkedSetOf<Int>()

        private fun pendingJobIndex(
            scheduler: JobScheduler,
            component: ComponentName,
            forceRefresh: Boolean = false
        ): PendingJobIndex {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val capturedAtElapsedMs = pendingJobIndexAtElapsedMs
            if (
                !forceRefresh &&
                    capturedAtElapsedMs != null &&
                    nowElapsedMs >= capturedAtElapsedMs &&
                    nowElapsedMs - capturedAtElapsedMs <= PENDING_JOB_INDEX_TTL_MS
            ) {
                return pendingJobIndexSnapshot
            }
            val idsByOperation = linkedMapOf<String, MutableSet<Int>>()
            val occupiedIds = linkedSetOf<Int>()
            var ownedJobCount = 0
            val pendingJobs = runCatching { scheduler.allPendingJobs.orEmpty() }
                .getOrElse { error ->
                    NPLogger.w(
                        TAG,
                        "读取 UIDT pending jobs 失败，交给 fallback: ${error.message}",
                        error
                    )
                    throw error
                }
            pendingJobs.forEach { job ->
                occupiedIds += job.id
                if (job.service == component) {
                    ownedJobCount++
                    job.extras.getString(OPERATION_ID_KEY)
                        ?.let(::normalizeDownloadOperationId)
                        ?.let { operationId ->
                            idsByOperation.getOrPut(operationId) { linkedSetOf() } += job.id
                        }
                }
            }
            occupiedIds += reservedJobIds
            pendingJobIndexSnapshot = PendingJobIndex(
                jobIdsByOperation = idsByOperation.mapValues { (_, ids) -> ids.toSet() },
                occupiedJobIds = occupiedIds.toSet(),
                ownedJobCount = ownedJobCount
            )
            pendingJobIndexAtElapsedMs = nowElapsedMs
            return pendingJobIndexSnapshot
        }

        private fun invalidatePendingJobIndex() {
            pendingJobIndexAtElapsedMs = null
        }

        private fun matchesPendingJob(
            scheduler: JobScheduler,
            component: ComponentName,
            jobId: Int,
            operationId: String
        ): Boolean {
            return pendingJobForOperation(
                scheduler = scheduler,
                component = component,
                jobId = jobId,
                operationId = operationId
            ) != null
        }

        private fun pendingJobForOperation(
            scheduler: JobScheduler,
            component: ComponentName,
            jobId: Int,
            operationId: String
        ): JobInfo? {
            return runCatching { scheduler.getPendingJob(jobId) }
                .getOrNull()
                ?.takeIf { job ->
                    job.service == component &&
                        job.extras.getString(OPERATION_ID_KEY)
                            ?.let(::normalizeDownloadOperationId) == operationId
                }
        }

        private fun pendingJobForOperation(
            scheduler: JobScheduler,
            component: ComponentName,
            operationId: String
        ): JobInfo? {
            val reservedIds = reservedJobIdsByOperation[operationId].orEmpty()
            reservedIds.forEach { jobId ->
                pendingJobForOperation(
                    scheduler = scheduler,
                    component = component,
                    jobId = jobId,
                    operationId = operationId
                )?.let { return it }
            }
            if (reservedIds.isNotEmpty()) {
                forgetScheduledJob(operationId)
            }
            var indexed = pendingJobIndex(scheduler, component)
            fun indexedPendingJob(): JobInfo? {
                return indexed.jobIdsByOperation[operationId]
                    .orEmpty()
                    .firstNotNullOfOrNull { jobId ->
                        pendingJobForOperation(
                            scheduler = scheduler,
                            component = component,
                            jobId = jobId,
                            operationId = operationId
                        )
                    }
            }
            indexedPendingJob()?.let { return it }
            if (indexed.jobIdsByOperation[operationId].orEmpty().isNotEmpty()) {
                indexed = pendingJobIndex(
                    scheduler = scheduler,
                    component = component,
                    forceRefresh = true
                )
                return indexedPendingJob()
            }
            return null
        }

        private fun rememberScheduledJob(operationId: String, jobId: Int) {
            reservedJobIdsByOperation
                .getOrPut(operationId) { linkedSetOf() }
                .add(jobId)
            reservedJobIds += jobId
            val idsByOperation = pendingJobIndexSnapshot.jobIdsByOperation.toMutableMap()
            idsByOperation[operationId] =
                idsByOperation[operationId].orEmpty() + jobId
            pendingJobIndexSnapshot = PendingJobIndex(
                jobIdsByOperation = idsByOperation,
                occupiedJobIds = pendingJobIndexSnapshot.occupiedJobIds + jobId,
                ownedJobCount = pendingJobIndexSnapshot.ownedJobCount + 1
            )
            pendingJobIndexAtElapsedMs = SystemClock.elapsedRealtime()
        }

        private fun forgetScheduledJob(operationId: String, jobId: Int? = null) {
            if (jobId == null) {
                reservedJobIdsByOperation.remove(operationId)?.let { rememberedJobIds ->
                    reservedJobIds.removeAll(rememberedJobIds)
                }
            } else {
                val rememberedJobIds = reservedJobIdsByOperation[operationId]
                if (rememberedJobIds?.remove(jobId) == true) {
                    reservedJobIds.remove(jobId)
                    if (rememberedJobIds.isEmpty()) {
                        reservedJobIdsByOperation.remove(operationId)
                    }
                }
            }
            invalidatePendingJobIndex()
        }

        internal fun markJobStarted(operationId: String, jobId: Int) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            synchronized(scheduleLock) {
                rememberScheduledJob(normalizedId, jobId)
            }
        }

        internal fun markJobFinished(operationId: String, jobId: Int) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            synchronized(scheduleLock) {
                forgetScheduledJob(normalizedId, jobId)
            }
        }

        fun schedule(
            context: Context,
            operationId: String,
            pendingJobLimit: Int = currentDownloadParallelism(context)
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            val boundedPendingJobLimit = pendingJobLimit.coerceIn(1, UIDT_PENDING_JOB_LIMIT)
            return scheduleUidtWithSharedPump(
                scheduleUidt = {
                    synchronized(scheduleLock) {
                        if (reservedJobIdsByOperation[normalizedId].orEmpty().isNotEmpty()) {
                            val stillScheduled = reservedJobIdsByOperation[normalizedId]
                                .orEmpty()
                                .any { jobId ->
                                    matchesPendingJob(
                                        scheduler = scheduler,
                                        component = component,
                                        jobId = jobId,
                                        operationId = normalizedId
                                    )
                                }
                            if (stillScheduled) return@synchronized true
                            forgetScheduledJob(normalizedId)
                        }
                        var pendingIndex = pendingJobIndex(scheduler, component)
                        fun hasIndexedJob(): Boolean {
                            return pendingIndex.jobIdsByOperation[normalizedId]
                                .orEmpty()
                                .any { jobId ->
                                    matchesPendingJob(
                                        scheduler = scheduler,
                                        component = component,
                                        jobId = jobId,
                                        operationId = normalizedId
                                    )
                                }
                        }
                        if (hasIndexedJob()) {
                            return@synchronized true
                        }
                        if (pendingIndex.jobIdsByOperation[normalizedId].orEmpty().isNotEmpty()) {
                            pendingIndex = pendingJobIndex(
                                scheduler = scheduler,
                                component = component,
                                forceRefresh = true
                            )
                            if (hasIndexedJob()) {
                                return@synchronized true
                            }
                        }
                        if (
                            pendingIndex.ownedJobCount >= boundedPendingJobLimit
                        ) {
                            NPLogger.w(
                                TAG,
                                "UIDT pending 数量达到上限，交给全局下载泵: " +
                                    "operationId=$normalizedId, " +
                                    "limit=$boundedPendingJobLimit"
                            )
                            return@synchronized false
                        }
                        val collisionJobIds = linkedSetOf<Int>()
                        repeat(4) {
                            val occupiedJobIds = pendingIndex.occupiedJobIds +
                                reservedJobIds + collisionJobIds
                            val selectedJobId = selectAvailableUidtJobId(
                                normalizedId,
                                occupiedJobIds
                            ) ?: return@repeat
                            if (
                                selectedJobId in reservedJobIds ||
                                    runCatching { scheduler.getPendingJob(selectedJobId) }
                                        .getOrNull() != null
                            ) {
                                collisionJobIds += selectedJobId
                                pendingIndex = pendingJobIndex(
                                    scheduler = scheduler,
                                    component = component,
                                    forceRefresh = true
                                )
                                return@repeat
                            }
                            val jobInfo = buildJobInfo(
                                jobId = selectedJobId,
                                component = component,
                                operationId = normalizedId
                            )
                            rememberScheduledJob(normalizedId, selectedJobId)
                            val scheduled = runCatching {
                                scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
                            }.getOrDefault(false)
                            if (scheduled) {
                                return@synchronized true
                            }
                            forgetScheduledJob(normalizedId, selectedJobId)
                            invalidatePendingJobIndex()
                            pendingIndex = pendingJobIndex(
                                scheduler = scheduler,
                                component = component,
                                forceRefresh = true
                            )
                        }
                        false
                    }
                },
                scheduleSharedPump = {
                    ForegroundDownloadWorker.schedulePump(
                        context = context,
                        initialDelayMs = UIDT_SHARED_PUMP_GRACE_MS
                    )
                }
            )
        }

        /** 启动时压缩遗留 UIDT 任务，保留 Room operation 交给全局泵恢复 */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        internal fun trimPendingJobs(context: Context): Int {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return 0
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            return synchronized(scheduleLock) {
                val pendingJobs = runCatching { scheduler.allPendingJobs.orEmpty() }
                    .getOrElse { error ->
                        NPLogger.w(TAG, "启动清理 UIDT 任务读取失败: ${error.message}", error)
                        return@synchronized 0
                    }
                    .filter { job -> job.service == component }
                    .sortedBy { job -> job.id }
                val pendingJobLimit = currentDownloadParallelism(context)
                    .coerceIn(1, UIDT_PENDING_JOB_LIMIT)
                if (pendingJobs.size <= pendingJobLimit) return@synchronized 0
                var cancelled = 0
                pendingJobs.drop(pendingJobLimit).forEach { job ->
                    val operationId = job.extras.getString(OPERATION_ID_KEY)
                        ?.let(::normalizeDownloadOperationId)
                    val cancelledNow = runCatching {
                        scheduler.cancel(job.id)
                        true
                    }.onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "启动清理 UIDT 任务失败: jobId=${job.id}, " +
                                "error=${error.message}",
                            error
                        )
                    }.getOrDefault(false)
                    if (cancelledNow) {
                        cancelled++
                        operationId?.let { forgetScheduledJob(it, job.id) }
                    }
                }
                if (cancelled > 0) {
                    NPLogger.w(
                        TAG,
                        "启动清理过量 UIDT 任务: cancelled=$cancelled, " +
                            "pending=${pendingJobs.size}, limit=$pendingJobLimit"
                    )
                    ForegroundDownloadWorker.schedulePump(context)
                }
                cancelled
            }
        }

        internal fun buildJobInfo(
            jobId: Int,
            component: ComponentName,
            operationId: String,
            scheduledAtElapsedMs: Long = SystemClock.elapsedRealtime()
        ): JobInfo {
            val extras = PersistableBundle().apply {
                putString(OPERATION_ID_KEY, operationId)
                putLong(
                    UIDT_SCHEDULED_AT_ELAPSED_MS_KEY,
                    scheduledAtElapsedMs.coerceAtLeast(0L)
                )
            }
            return JobInfo.Builder(jobId, component)
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(
                    JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                    JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
                )
                .setExtras(extras)
                .build()
        }

        fun cancel(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            val pendingJobs = runCatching { scheduler.allPendingJobs.orEmpty() }
                .getOrElse { error ->
                    NPLogger.w(TAG, "取消 UIDT 任务读取 pending 失败: ${error.message}", error)
                    emptyList()
                }
            pendingJobs
                .filter { job ->
                    job.service == component &&
                        job.extras.getString(OPERATION_ID_KEY)
                            ?.let(::normalizeDownloadOperationId) == normalizedId
                }
                .forEach { job ->
                    runCatching { scheduler.cancel(job.id) }
                        .onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "取消 UIDT 任务失败: operationId=$normalizedId, " +
                                    "jobId=${job.id}, error=${error.message}",
                                error
                            )
                        }
                }
            synchronized(scheduleLock) {
                forgetScheduledJob(normalizedId)
            }
            val legacyJob = runCatching {
                scheduler.getPendingJob(jobIdFor(normalizedId))
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "读取 UIDT 兼容任务失败: operationId=$normalizedId, " +
                        "error=${error.message}",
                    error
                )
            }.getOrNull()
            if (
                legacyJob?.service == component &&
                    legacyJob.extras.getString(OPERATION_ID_KEY)
                        ?.let(::normalizeDownloadOperationId) == normalizedId
            ) {
                runCatching { scheduler.cancel(legacyJob.id) }
                    .onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "取消 UIDT 兼容任务失败: operationId=$normalizedId, " +
                                "jobId=${legacyJob.id}, error=${error.message}",
                            error
                        )
                    }
            }
        }

        fun cancelAll(
            context: Context,
            operationIds: Collection<String>
        ) {
            val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
            if (normalizedIds.isEmpty()) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            val pendingJobs = runCatching { scheduler.allPendingJobs.orEmpty() }
                .getOrElse { error ->
                    NPLogger.w(TAG, "批量取消 UIDT 任务读取 pending 失败: ${error.message}", error)
                    emptyList()
                }
            pendingJobs
                .asSequence()
                .filter { job ->
                    job.service == component &&
                        job.extras.getString(OPERATION_ID_KEY)
                            ?.let(::normalizeDownloadOperationId) in normalizedIds
                }
                .forEach { job ->
                    runCatching { scheduler.cancel(job.id) }
                        .onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "批量取消 UIDT 任务失败: jobId=${job.id}, " +
                                    "error=${error.message}",
                                error
                            )
                        }
                }
            synchronized(scheduleLock) {
                normalizedIds.forEach { operationId -> forgetScheduledJob(operationId) }
            }
        }

        fun cancelAllOwned(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            val pendingJobs = runCatching { scheduler.allPendingJobs.orEmpty() }
                .getOrElse { error ->
                    NPLogger.w(TAG, "清空 UIDT 任务读取 pending 失败: ${error.message}", error)
                    emptyList()
                }
            pendingJobs
                .asSequence()
                .filter { job -> job.service == component }
                .forEach { job ->
                    runCatching { scheduler.cancel(job.id) }
                        .onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "清空 UIDT 任务失败: jobId=${job.id}, error=${error.message}",
                                error
                            )
                        }
                }
            synchronized(scheduleLock) {
                reservedJobIdsByOperation.clear()
                reservedJobIds.clear()
                invalidatePendingJobIndex()
            }
        }

        fun hasPendingJob(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            return runCatching {
                synchronized(scheduleLock) {
                    pendingJobForOperation(
                        scheduler = scheduler,
                        component = component,
                        operationId = normalizedId
                    ) != null
                }
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "检查 UIDT pending 任务失败，交给持久化恢复: " +
                        "operationId=$normalizedId, error=${error.message}",
                    error
                )
            }.getOrDefault(false)
        }

        fun shouldYieldToPendingJob(
            context: Context,
            operationId: String
        ): Boolean {
            return pendingJobGraceRemainingMs(context, operationId) > 0L
        }

        fun pendingJobGraceRemainingMs(
            context: Context,
            operationId: String
        ): Long {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return 0L
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return 0L
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            return runCatching {
                synchronized(scheduleLock) {
                    val scheduledAtElapsedMs = pendingJobForOperation(
                        scheduler = scheduler,
                        component = component,
                        operationId = normalizedId
                    )?.extras?.getLong(UIDT_SCHEDULED_AT_ELAPSED_MS_KEY, 0L) ?: 0L
                    pendingUidtGraceRemainingMs(
                        scheduledAtElapsedMs = scheduledAtElapsedMs,
                        nowElapsedMs = SystemClock.elapsedRealtime()
                    )
                }
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "读取 UIDT 接管窗口失败，交给持久化下载泵: " +
                        "operationId=$normalizedId, error=${error.message}",
                    error
                )
            }.getOrDefault(0L)
        }

        internal fun jobIdFor(operationId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(operationId.toByteArray(Charsets.UTF_8))
            var value = 0
            repeat(4) { index ->
                value = (value shl 8) or (digest[index].toInt() and 0xff)
            }
            return UIDT_JOB_ID_MIN +
                (value and Int.MAX_VALUE) % (UIDT_JOB_ID_MAX - UIDT_JOB_ID_MIN + 1)
        }

        internal fun selectAvailableUidtJobId(
            operationId: String,
            occupiedJobIds: Set<Int>
        ): Int? {
            val rangeSize = UIDT_JOB_ID_MAX - UIDT_JOB_ID_MIN + 1
            val baseOffset = jobIdFor(operationId) - UIDT_JOB_ID_MIN
            repeat(rangeSize) { probe ->
                val candidate = UIDT_JOB_ID_MIN + (baseOffset + probe) % rangeSize
                if (candidate !in occupiedJobIds) {
                    return candidate
                }
            }
            return null
        }
    }
}

internal fun scheduleUidtWithSharedPump(
    scheduleUidt: () -> Boolean,
    scheduleSharedPump: () -> Boolean
): Boolean {
    val scheduled = try {
        scheduleUidt()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-DownloadUidt",
            "UIDT 调度暂时失败，保留 fallback: ${error.message}",
            error
        )
        return false
    }
    if (!scheduled) {
        return false
    }
    if (!scheduleSharedPump()) {
        NPLogger.w(
            "NERI-DownloadUidt",
            "UIDT 已调度，但共享下载泵接管未提交，保留 UIDT 等待系统启动"
        )
        // 让上层走 deferred/fallback 重试，避免只剩一个可能长期 pending 的 UIDT
        return false
    }
    return true
}

internal fun shouldYieldToPendingUidt(
    scheduledAtElapsedMs: Long,
    nowElapsedMs: Long,
    graceMs: Long = UIDT_SHARED_PUMP_GRACE_MS
): Boolean {
    return pendingUidtGraceRemainingMs(
        scheduledAtElapsedMs = scheduledAtElapsedMs,
        nowElapsedMs = nowElapsedMs,
        graceMs = graceMs
    ) > 0L
}

internal fun pendingUidtGraceRemainingMs(
    scheduledAtElapsedMs: Long,
    nowElapsedMs: Long,
    graceMs: Long = UIDT_SHARED_PUMP_GRACE_MS
): Long {
    if (scheduledAtElapsedMs <= 0L || graceMs <= 0L) return 0L
    if (nowElapsedMs < scheduledAtElapsedMs) return 0L
    return (graceMs - (nowElapsedMs - scheduledAtElapsedMs)).coerceAtLeast(0L)
}

internal fun shouldRescheduleUidtExecution(result: DownloadExecutionResult): Boolean {
    return when (result) {
        DownloadExecutionResult.Retry,
        is DownloadExecutionResult.Failed -> true

        DownloadExecutionResult.Accepted,
        DownloadExecutionResult.AlreadyHandled,
        DownloadExecutionResult.MissingOperation,
        DownloadExecutionResult.Cancelled,
        DownloadExecutionResult.UserStopped,
        DownloadExecutionResult.UserActionRequired,
        DownloadExecutionResult.NetworkPolicyWaiting -> false
    }
}

/** 保留回退宿主的所有权，UIDT 进入终态后结束过期任务 */
internal fun shouldCancelUidtFallback(
    result: DownloadExecutionResult,
    fallbackExecuting: Boolean
): Boolean {
    return !fallbackExecuting && !shouldRescheduleUidtExecution(result)
}
