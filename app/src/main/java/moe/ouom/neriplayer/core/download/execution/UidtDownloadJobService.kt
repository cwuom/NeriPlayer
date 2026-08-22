package moe.ouom.neriplayer.core.download.execution

import android.annotation.SuppressLint
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** bridges API 34 user initiated jobs to the shared download host */
// job ids stay in a host-owned range because WorkManager uses its own range
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@SuppressLint("SpecifyJobSchedulerIdRange")
class UidtDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val operationId = params.extras
            .getString(OPERATION_ID_KEY)
            ?.let(::normalizeDownloadOperationId)
        if (operationId == null) {
            jobFinished(params, false)
            return false
        }
        setNotification(
            params,
            NOTIFICATION_ID,
            buildNotification(operationId),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
        )
        val job = serviceScope.launch {
            val result = DownloadExecutionHosts.default.execute(applicationContext, operationId)
            withContext(Dispatchers.Main) {
                runningJobs.remove(params.jobId)
                jobFinished(
                    params,
                    result == DownloadExecutionResult.Retry ||
                        result is DownloadExecutionResult.Failed
                )
            }
        }
        runningJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel(CancellationException("UIDT job stopped"))
        return shouldRescheduleUidtJob(
            stopReason = params.stopReason,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runningJobs.clear()
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
        internal const val OPERATION_ID_KEY = "operation_id"
        private const val CHANNEL_ID = "download_execution"
        private const val NOTIFICATION_ID = 0x4e50_0002
        private const val JOB_ID_MIN = 100_000
        private const val JOB_ID_RANGE = 900_000_000

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val component = ComponentName(context, UidtDownloadJobService::class.java)
            val extras = PersistableBundle().apply {
                putString(OPERATION_ID_KEY, normalizedId)
            }
            val jobInfo = JobInfo.Builder(jobIdFor(normalizedId), component)
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(1L, JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                .setExtras(extras)
                .build()
            return runCatching {
                scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }

        fun cancel(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            context.getSystemService(JobScheduler::class.java)
                ?.cancel(jobIdFor(normalizedId))
        }

        internal fun jobIdFor(operationId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(operationId.toByteArray(Charsets.UTF_8))
            var value = 0
            repeat(4) { index ->
                value = (value shl 8) or (digest[index].toInt() and 0xff)
            }
            return JOB_ID_MIN + (value and Int.MAX_VALUE) % JOB_ID_RANGE
        }
    }
}
