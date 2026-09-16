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
import androidx.work.workDataOf
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        val generation = inputData.getLong(GENERATION_KEY, 0L)
        if (!scheduleCoordinator.claimWorker(generation)) return@withContext Result.success()
        var workWillRetry = true
        var continueSoon = false
        try {
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
            when (GlobalDownloadManager.recoverPostCoreDownloadsForWorker(appContext)) {
                PostCoreDownloadRecoveryResult.SETTLED -> {
                    workWillRetry = false
                    Result.success()
                }
                PostCoreDownloadRecoveryResult.CONTINUE_SOON -> {
                    continueSoon = true
                    workWillRetry = false
                    Result.success()
                }
                PostCoreDownloadRecoveryResult.RETRY,
                PostCoreDownloadRecoveryResult.BLOCKED -> Result.retry()

                PostCoreDownloadRecoveryResult.WAITING_NETWORK -> {
                    if (appContext.currentDownloadNetworkTypeOrNull() == null) {
                        Result.retry()
                    } else {
                        WifiBoundDownloadWakeWorker.scheduleAll(appContext)
                        workWillRetry = false
                        Result.success()
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            // 系统停止会重新调度持久 Worker，释放内存 owner 允许显式新请求接管
            scheduleCoordinator.complete(generation, workWillRetry = false)
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                "NERI-PostCoreRecovery",
                "下载收尾 Worker 执行失败，保留持久队列等待重试: ${error.message}",
                error
            )
            Result.retry()
        } finally {
            val completion = if (continueSoon) {
                scheduleCoordinator.completeWithSuccessor(generation)
            } else {
                scheduleCoordinator.complete(generation, workWillRetry)
            }
            if (completion == DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR) {
                schedule(appContext, initialDelayMs = SUCCESSOR_DELAY_MS)
            }
        }
    }

    companion object {
        private const val WORK_NAME = "download_post_core_recovery"
        private const val WORK_TAG = "download_execution_all"
        private const val POST_CORE_WORK_TAG = "download_post_core_recovery"
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val SUCCESSOR_DELAY_MS = 500L
        private const val STARTUP_RESTORE_WAIT_MS = 20_000L
        private const val GENERATION_KEY = "post_core_generation"
        internal val scheduleCoordinator = DownloadPumpScheduleCoordinator()
        private val enqueueCallbackExecutor = Executor { it.run() }

        /** 同一时间只保留一个系统任务，避免歌曲数直接放大 WorkManager 队列 */
        fun schedule(context: Context, initialDelayMs: Long = 0L): Boolean =
            enqueue(context, initialDelayMs, retryEnqueue = true)

        private fun enqueue(context: Context, initialDelayMs: Long, retryEnqueue: Boolean): Boolean {
            val appContext = context.applicationContext
            if (PersistentDownloadClearFenceStore.isActive(appContext)) return false
            val generation = scheduleCoordinator.request() ?: return true
            if (!scheduleCoordinator.markWorkEnqueueStarted(generation)) return true
            return runCatching {
                val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    buildRequest(initialDelayMs = initialDelayMs, generation = generation)
                )
                operation.result.addListener(
                    Runnable {
                        runCatching { operation.result.get() }.onFailure { error ->
                            handleEnqueueFailure(appContext, generation, retryEnqueue, error)
                        }
                    },
                    enqueueCallbackExecutor
                )
                true
            }.onFailure { error ->
                handleEnqueueFailure(appContext, generation, retryEnqueue, error)
            }.getOrDefault(false)
        }

        private fun handleEnqueueFailure(
            context: Context,
            generation: Long,
            retryEnqueue: Boolean,
            error: Throwable
        ) {
            if (!scheduleCoordinator.failEnqueue(generation)) return
            NPLogger.w("NERI-PostCoreRecovery", "持久收尾入队未确认，保留 Room 凭据", error)
            if (retryEnqueue) {
                GlobalDownloadManager.scope.launch {
                    delay(1_000L)
                    if (scheduleCoordinator.canRetry(generation)) {
                        enqueue(context, initialDelayMs = 0L, retryEnqueue = false)
                    }
                }
            }
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

        internal fun buildRequest(initialDelayMs: Long = 0L, generation: Long = 0L): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<PostCoreDownloadRecoveryWorker>()
                .setInputData(workDataOf(GENERATION_KEY to generation))
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
