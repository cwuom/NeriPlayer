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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** bridges API 34 user initiated jobs to the shared download host */
// job ids stay in a host-owned range because WorkManager uses its own range
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
        if (operationId != null) {
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
        private val scheduleLock = Any()

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            return scheduleUidtKeepingFallback(
                scheduleFallback = {
                    ForegroundDownloadWorker.scheduleFallback(context, normalizedId)
                },
                scheduleUidt = {
                    synchronized(scheduleLock) {
                        val pendingJobs = scheduler.allPendingJobs
                        val existingJob = pendingJobs.firstOrNull { job ->
                            job.service == component &&
                                job.extras.getString(OPERATION_ID_KEY) == normalizedId
                        }
                        if (existingJob != null) {
                            return@synchronized true
                        }
                        val occupiedJobIds = pendingJobs
                            .asSequence()
                            .filter { job -> job.service == component }
                            .mapTo(linkedSetOf()) { job -> job.id }
                        val selectedJobId = selectAvailableUidtJobId(
                            normalizedId,
                            occupiedJobIds
                        ) ?: return@synchronized false
                        val jobInfo = buildJobInfo(
                            jobId = selectedJobId,
                            component = component,
                            operationId = normalizedId
                        )
                        val scheduled = runCatching {
                            scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
                        }.getOrDefault(false)
                        if (!scheduled) {
                            scheduler.cancel(selectedJobId)
                        }
                        scheduled
                    }
                },
                cancelFallback = {
                    ForegroundDownloadWorker.cancelFallback(context, normalizedId)
                }
            )
        }

        internal fun buildJobInfo(
            jobId: Int,
            component: ComponentName,
            operationId: String
        ): JobInfo {
            val extras = PersistableBundle().apply {
                putString(OPERATION_ID_KEY, operationId)
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
            scheduler.allPendingJobs
                .filter { job ->
                    job.service == component &&
                        job.extras.getString(OPERATION_ID_KEY) == normalizedId
                }
                .forEach { job -> scheduler.cancel(job.id) }
            val legacyJob = scheduler.getPendingJob(jobIdFor(normalizedId))
            if (
                legacyJob?.service == component &&
                    legacyJob.extras.getString(OPERATION_ID_KEY) == normalizedId
            ) {
                scheduler.cancel(legacyJob.id)
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
            scheduler.allPendingJobs
                .asSequence()
                .filter { job ->
                    job.service == component &&
                        job.extras.getString(OPERATION_ID_KEY) in normalizedIds
                }
                .forEach { job -> scheduler.cancel(job.id) }
        }

        fun cancelAllOwned(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            scheduler.allPendingJobs
                .asSequence()
                .filter { job -> job.service == component }
                .forEach { job -> scheduler.cancel(job.id) }
        }

        fun hasPendingJob(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            return scheduler.allPendingJobs.any { job ->
                job.service == component &&
                    job.extras.getString(OPERATION_ID_KEY) == normalizedId
            }
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

internal fun scheduleUidtKeepingFallback(
    scheduleFallback: () -> Unit,
    scheduleUidt: () -> Boolean,
    cancelFallback: () -> Unit
): Boolean {
    scheduleFallback()
    val scheduled = scheduleUidt()
    if (!scheduled) {
        cancelFallback()
    }
    return scheduled
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
