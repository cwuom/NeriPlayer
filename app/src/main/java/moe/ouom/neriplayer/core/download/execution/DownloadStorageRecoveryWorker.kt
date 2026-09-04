package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 低频检查空间等待队列，空间不足时不让共享下载泵反复空转
 */
class DownloadStorageRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        if (
            PersistentDownloadClearFenceStore.isActive(appContext) ||
            ManagedDownloadDirectoryMutationFence.isActiveFast(appContext) ||
            ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(appContext)
        ) {
            return@withContext Result.retry()
        }
        try {
            val promotedCount =
                GlobalDownloadManager.promoteWaitingStorageMutationsForDownloadPump(appContext)
            if (promotedCount > 0) {
                return@withContext if (ForegroundDownloadWorker.schedulePump(appContext)) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            val hasWaitingOperations =
                DownloadRecoveryRoomStore(appContext).listWaitingStorageMutations().isNotEmpty()
            if (hasWaitingOperations) Result.retry() else Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                "NERI-DownloadStorageRecovery",
                "空间等待恢复检查失败，保留持久任务等待下次重试: ${error.message}",
                error
            )
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "download_storage_recovery"
        private const val WORK_TAG = "download_execution_all"
        private const val STORAGE_RECOVERY_TAG = "download_storage_recovery"
        private const val FIRST_CHECK_DELAY_MS = 5_000L
        private const val RETRY_BACKOFF_MS = 30_000L

        /** 同一时间只保留一个空间探测任务，避免大量歌曲各自创建 Worker */
        fun schedule(
            context: Context,
            initialDelayMs: Long = FIRST_CHECK_DELAY_MS
        ): Boolean {
            return runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    buildRequest(initialDelayMs)
                )
                true
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-DownloadStorageRecovery",
                    "空间等待 Worker 调度失败，启动恢复仍会再次接管: ${error.message}",
                    error
                )
            }.getOrDefault(false)
        }

        internal fun buildRequest(initialDelayMs: Long = FIRST_CHECK_DELAY_MS): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<DownloadStorageRecoveryWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_MS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_TAG)
                .addTag(STORAGE_RECOVERY_TAG)
            if (initialDelayMs > 0L) {
                builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            return builder.build()
        }
    }
}
