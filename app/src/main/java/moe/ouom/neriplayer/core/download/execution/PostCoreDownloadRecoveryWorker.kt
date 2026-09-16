package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.PostCoreDownloadRecoveryResult
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull

/** 用一个持久任务分批收敛所有已提交音频的歌词、封面和标签 */
class PostCoreDownloadRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        if (ForegroundDownloadWorker.isPumpBlocked(appContext)) {
            return@withContext Result.retry()
        }
        GlobalDownloadManager.initialize(appContext)
        val startupReady = withTimeoutOrNull(STARTUP_RESTORE_WAIT_MS) {
            GlobalDownloadManager.startupProgressRestoreReady.await()
            true
        } == true
        if (!startupReady || ForegroundDownloadWorker.isPumpBlocked(appContext)) {
            return@withContext Result.retry()
        }

        try {
            when (GlobalDownloadManager.recoverPostCoreDownloadsForWorker(appContext)) {
                PostCoreDownloadRecoveryResult.SETTLED -> Result.success()
                PostCoreDownloadRecoveryResult.CONTINUE_SOON -> {
                    if (scheduleSuccessor(appContext)) Result.success() else Result.retry()
                }
                PostCoreDownloadRecoveryResult.RETRY,
                PostCoreDownloadRecoveryResult.BLOCKED -> Result.retry()

                PostCoreDownloadRecoveryResult.WAITING_NETWORK -> {
                    if (appContext.currentDownloadNetworkTypeOrNull() == null) {
                        Result.retry()
                    } else {
                        WifiBoundDownloadWakeWorker.scheduleAll(appContext)
                        Result.success()
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                "NERI-PostCoreRecovery",
                "下载收尾 Worker 执行失败，保留持久队列等待重试: ${error.message}",
                error
            )
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "download_post_core_recovery"
        private const val WORK_TAG = "download_execution_all"
        private const val POST_CORE_WORK_TAG = "download_post_core_recovery"
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val SUCCESSOR_DELAY_MS = 500L
        private const val STARTUP_RESTORE_WAIT_MS = 20_000L

        /** 同一时间只保留一个系统任务，避免歌曲数直接放大 WorkManager 队列 */
        fun schedule(context: Context): Boolean {
            val appContext = context.applicationContext
            if (PersistentDownloadClearFenceStore.isActive(appContext)) return false
            return runCatching {
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    buildRequest()
                )
                true
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PostCoreRecovery",
                    "下载收尾 Worker 调度失败，启动恢复会再次接管: ${error.message}",
                    error
                )
            }.getOrDefault(false)
        }

        /** 升级后撤销旧版本按歌曲创建的宿主任务，Room operation 继续由共享任务接管 */
        fun cancelLegacyPerOperationWork(
            context: Context,
            operationIds: Collection<String>
        ) {
            val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
            if (normalizedIds.isEmpty()) return
            val appContext = context.applicationContext
            ForegroundDownloadWorker.cancelAll(appContext, normalizedIds)
            WifiBoundDownloadWakeWorker.cancelAll(appContext, normalizedIds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                UidtDownloadJobService.cancelAll(appContext, normalizedIds)
            }
        }

        /** 本轮已有完成进展时追加短延迟后继，避免正常大队列进入指数退避 */
        private fun scheduleSuccessor(context: Context): Boolean {
            return runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    buildRequest(initialDelayMs = SUCCESSOR_DELAY_MS)
                )
                true
            }.getOrDefault(false)
        }

        internal fun buildRequest(initialDelayMs: Long = 0L): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<PostCoreDownloadRecoveryWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_MS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG)
                .addTag(POST_CORE_WORK_TAG)
            if (initialDelayMs > 0L) {
                builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            return builder.build()
        }
    }
}
