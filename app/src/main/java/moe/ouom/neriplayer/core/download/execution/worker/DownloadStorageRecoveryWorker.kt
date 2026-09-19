package moe.ouom.neriplayer.core.download.execution.worker

import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
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
        val generation = inputData.getLong(GENERATION_KEY, 0L)
        if (!scheduleCoordinator.claimWorker(generation)) return@withContext Result.success()
        var workWillRetry = true
        try {
            if (
                PersistentDownloadClearFenceStore.isActive(appContext) ||
                ManagedDownloadDirectoryMutationFence.isActiveFast(appContext) ||
                ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(appContext)
            ) {
                return@withContext Result.retry()
            }
            val promotedCount =
                GlobalDownloadManager.promoteWaitingStorageMutationsForDownloadPump(appContext)
            if (promotedCount > 0) {
                if (!ForegroundDownloadWorker.schedulePump(appContext)) {
                    return@withContext Result.retry()
                }
            }
            val hasWaitingOperations =
                DownloadRecoveryRoomStore(appContext).listWaitingStorageMutations().isNotEmpty()
            if (hasWaitingOperations) {
                Result.retry()
            } else {
                workWillRetry = false
                Result.success()
            }
        } catch (cancellation: CancellationException) {
            workWillRetry = false
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                "NERI-DownloadStorageRecovery",
                "空间等待恢复检查失败，保留持久任务等待下次重试: ${error.message}",
                error
            )
            Result.retry()
        } finally {
            if (scheduleCoordinator.complete(generation, workWillRetry) ==
                DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR
            ) {
                schedule(appContext, initialDelayMs = scheduleCoordinator.takeSuccessorDelayMs(generation))
            }
        }
    }

    companion object {
        private const val WORK_NAME = "download_storage_recovery"
        private const val WORK_TAG = "download_execution_all"
        private const val STORAGE_RECOVERY_TAG = "download_storage_recovery"
        private const val FIRST_CHECK_DELAY_MS = 5_000L
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val GENERATION_KEY = "storage_recovery_generation"
        internal val scheduleCoordinator = DownloadPumpScheduleCoordinator(
            lock = downloadWorkSubmissionLock
        )
        private val enqueueCallbackExecutor = Executor { it.run() }

        /** 同一时间只保留一个空间探测任务，避免大量歌曲各自创建 Worker */
        fun schedule(
            context: Context,
            initialDelayMs: Long = FIRST_CHECK_DELAY_MS
        ): Boolean = enqueue(context, initialDelayMs, retryEnqueue = true)

        private fun enqueue(context: Context, initialDelayMs: Long, retryEnqueue: Boolean): Boolean {
            val appContext = context.applicationContext
            val generation = scheduleCoordinator.request(initialDelayMs = initialDelayMs) ?: return true
            return runCatching {
                val workManager = WorkManager.getInstance(appContext)
                val request = buildRequest(initialDelayMs, generation)
                var submittedOperation: Operation? = null
                scheduleCoordinator.submitWorkEnqueue(generation) {
                    submittedOperation = workManager.enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request
                    )
                }
                val operation = submittedOperation ?: return@runCatching true
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
            context: Context, generation: Long, retryEnqueue: Boolean, error: Throwable
        ) {
            if (!scheduleCoordinator.failEnqueue(generation)) return
            NPLogger.w("NERI-DownloadStorageRecovery", "存储等待入队未确认，保留 Room 凭据", error)
            if (retryEnqueue) {
                GlobalDownloadManager.scope.launch {
                    delay(1_000L)
                    if (scheduleCoordinator.canRetry(generation)) {
                        enqueue(context, initialDelayMs = 0L, retryEnqueue = false)
                    }
                }
            }
        }

        internal fun buildRequest(
            initialDelayMs: Long = FIRST_CHECK_DELAY_MS,
            generation: Long = 0L
        ): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<DownloadStorageRecoveryWorker>()
                .setInputData(workDataOf(GENERATION_KEY to generation))
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
