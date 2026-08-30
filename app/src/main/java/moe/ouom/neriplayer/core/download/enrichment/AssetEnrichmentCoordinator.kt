package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在有界队列中执行歌词、封面和标签等非核心资产工作
 *
 * core audio commit 不依赖这个队列的完成状态，资产失败只会留下可重试状态
 */
internal class AssetEnrichmentCoordinator(
    private val scope: CoroutineScope,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    private val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    private val jobsByOperationId = ConcurrentHashMap<String, Job>()
    private val jobRegistrationLock = Any()
    private val _hasActiveJobs = MutableStateFlow(false)

    val hasActiveJobs: StateFlow<Boolean> = _hasActiveJobs.asStateFlow()

    fun enqueue(
        operationId: String,
        onTimeout: suspend (Throwable) -> Unit = {},
        onCompletion: (Throwable?) -> Unit = {},
        block: suspend () -> Unit
    ): Job {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank)
            ?: error("asset enrichment requires operationId")
        return synchronized(jobRegistrationLock) {
            jobsByOperationId[normalizedId]?.let { existing ->
                if (existing.isActive) return@synchronized existing
            }
            val timeoutCallbackFailure = AtomicReference<Throwable?>(null)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                semaphore.withPermit {
                    try {
                        withTimeout(timeoutMs) { block() }
                    } catch (error: TimeoutCancellationException) {
                        runCatching { onTimeout(error) }
                            .onFailure(timeoutCallbackFailure::set)
                    } catch (error: CancellationException) {
                        throw error
                    }
                }
            }
            jobsByOperationId[normalizedId] = job
            job.invokeOnCompletion { error ->
                synchronized(jobRegistrationLock) {
                    jobsByOperationId.remove(normalizedId, job)
                    refreshActiveStateLocked()
                }
                val completionError = error ?: timeoutCallbackFailure.get()
                runCatching { onCompletion(completionError) }
            }
            job.start()
            refreshActiveStateLocked()
            job
        }
    }

    fun cancel(operationId: String): Boolean {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank) ?: return false
        val job = synchronized(jobRegistrationLock) {
            jobsByOperationId[normalizedId]
        } ?: return false
        val wasActive = job.isActive
        if (wasActive) {
            job.cancel(CancellationException("asset enrichment cancelled"))
        }
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return wasActive
    }

    fun activeCount(): Int = synchronized(jobRegistrationLock) {
        jobsByOperationId.values.count(Job::isActive)
    }

    /** 返回仍持有活动协程的收尾 operation ID */
    fun activeOperationIds(): Set<String> = synchronized(jobRegistrationLock) {
        jobsByOperationId
            .asSequence()
            .filter { (_, job) -> job.isActive }
            .mapTo(linkedSetOf()) { (operationId, _) -> operationId }
    }

    /** 取消所有收尾任务但保留完成回调 */
    fun cancelAll(reason: String = "asset enrichment cancelled"): Int {
        val jobs = synchronized(jobRegistrationLock) {
            jobsByOperationId.values.filter(Job::isActive).toList()
        }
        jobs.forEach { job ->
            job.cancel(CancellationException(reason))
        }
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return jobs.size
    }

    /** 取消所有收尾任务并在文件清理前等待一段有界时间 */
    suspend fun cancelAllAndJoin(
        reason: String = "asset enrichment cancelled",
        timeoutMs: Long = DEFAULT_CANCEL_JOIN_TIMEOUT_MS
    ): Boolean {
        val jobs = synchronized(jobRegistrationLock) {
            jobsByOperationId.values.filter(Job::isActive).toList()
        }
        jobs.forEach { job ->
            job.cancel(CancellationException(reason))
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.joinAll()
            true
        } ?: false
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return settled && activeOperationIds().isEmpty()
    }

    private fun refreshActiveStateLocked() {
        _hasActiveJobs.value = jobsByOperationId.values.any(Job::isActive)
    }

    companion object {
        const val DEFAULT_PARALLELISM = 2
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_CANCEL_JOIN_TIMEOUT_MS = 5_000L
    }
}
