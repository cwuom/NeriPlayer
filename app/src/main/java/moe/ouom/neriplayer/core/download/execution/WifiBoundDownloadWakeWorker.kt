package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType

/** wakes a Wi-Fi-bound operation only after a Wi-Fi-class transport is available again */
class WifiBoundDownloadWakeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(OPERATION_ID_KEY)
            ?.let(::normalizeDownloadOperationId)
        if (operationId == null) {
            if (!shouldHandoffWifiBoundDownloadWake(applicationContext.currentTrafficNetworkType())) {
                return@withContext Result.retry()
            }
            return@withContext if (
                GlobalDownloadManager.recoverPendingDownloadsFromWifiWake(applicationContext)
            ) {
                Result.success()
            } else {
                Result.retry()
            }
        }
        val request = DownloadExecutionRoomStore.read(
            context = applicationContext,
            operationId = operationId
        ) ?: return@withContext Result.success()
        val state = DownloadExecutionRoomStore.state(
            context = applicationContext,
            operationId = operationId
        )
        if (!shouldScheduleWifiBoundDownloadWakeup(request.requiresWifiNetwork, state)) {
            return@withContext Result.success()
        }
        if (!shouldHandoffWifiBoundDownloadWake(applicationContext.currentTrafficNetworkType())) {
            return@withContext Result.retry()
        }
        when (
            val schedule = DownloadExecutionHosts.default.schedule(
                context = applicationContext,
                request = request
            )
        ) {
            is DownloadExecutionSchedule.Scheduled -> Result.success()
            is DownloadExecutionSchedule.Deferred -> Result.retry()
            is DownloadExecutionSchedule.Rejected -> {
                if (schedule.retryable) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        internal const val OPERATION_ID_KEY = "operation_id"
        private const val WORK_NAME_PREFIX = "download_wifi_wake_"
        private const val GLOBAL_WORK_NAME = "download_wifi_wake_all"
        private const val WORK_TAG_PREFIX = "download_wifi_wake_operation_"
        private const val ALL_WIFI_WAKE_WORK_TAG = "download_wifi_wake_all"

        fun scheduleAll(context: Context): Boolean {
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        GLOBAL_WORK_NAME,
                        wifiBoundDownloadWakeExistingWorkPolicy,
                        buildGlobalRequest()
                    )
                true
            }.getOrDefault(false)
        }

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            return enqueue(
                context = context,
                operationId = normalizedId,
                policy = wifiBoundDownloadWakeExistingWorkPolicy
            )
        }

        /** preserves a follow-up wake when a generic execution host hands work back to policy */
        fun rearmAfterNetworkPolicyWait(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            return enqueue(
                context = context,
                operationId = normalizedId,
                policy = wifiBoundDownloadWakeHandoffRearmPolicy
            )
        }

        private fun enqueue(
            context: Context,
            operationId: String,
            policy: ExistingWorkPolicy
        ): Boolean {
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        uniqueWorkName(operationId),
                        policy,
                        buildRequest(operationId)
                    )
                true
            }.getOrDefault(false)
        }

        fun cancel(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(uniqueWorkName(normalizedId))
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
                }
            }
        }

        fun cancelAllOwned(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelAllWorkByTag(ALL_WIFI_WAKE_WORK_TAG)
            }
        }

        internal fun buildRequest(operationId: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<WifiBoundDownloadWakeWorker>()
                .setInputData(workDataOf(OPERATION_ID_KEY to operationId))
                .setConstraints(wifiClassNetworkConstraints())
                .addTag(WORK_TAG_PREFIX + operationId)
                .addTag(ALL_WIFI_WAKE_WORK_TAG)
                .build()
        }

        internal fun buildGlobalRequest(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<WifiBoundDownloadWakeWorker>()
                .setConstraints(wifiClassNetworkConstraints())
                .addTag(ALL_WIFI_WAKE_WORK_TAG)
                .build()
        }

        internal fun uniqueWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + operationId
        }

        private fun wifiClassNetworkConstraints(): Constraints {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            return Constraints.Builder()
                .setRequiredNetworkRequest(networkRequest, NetworkType.CONNECTED)
                .build()
        }
    }
}

internal fun shouldScheduleWifiBoundDownloadWakeup(
    requiresWifiNetwork: Boolean,
    operationState: String?
): Boolean {
    return requiresWifiNetwork &&
        operationState in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
}

internal val wifiBoundDownloadWakeExistingWorkPolicy = ExistingWorkPolicy.KEEP
internal val wifiBoundDownloadWakeHandoffRearmPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

internal fun shouldHandoffWifiBoundDownloadWake(
    currentNetworkType: TrafficNetworkType
): Boolean = currentNetworkType == TrafficNetworkType.WIFI
