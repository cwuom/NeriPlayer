package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTraceToken

/**
 * 在有界队列中执行歌词、封面和标签等非核心资产工作
 *
 * core audio commit 不依赖这个队列的完成状态，资产失败只会留下可重试状态
 */
internal class AssetEnrichmentCoordinator(
    private val scope: CoroutineScope,
    parallelism: Int = DEFAULT_PARALLELISM,
    maxActiveJobs: Int = Int.MAX_VALUE,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    private val normalizedParallelism = parallelism.coerceAtLeast(1)
    private val maxActiveJobs = maxActiveJobs.coerceAtLeast(normalizedParallelism)
    private val semaphore = Semaphore(normalizedParallelism)
    private val jobsByOperationId = ConcurrentHashMap<String, Job>()
    private val jobRegistrationLock = Any()
    private val _hasActiveJobs = MutableStateFlow(false)

    val hasActiveJobs: StateFlow<Boolean> = _hasActiveJobs.asStateFlow()

    fun enqueue(
        operationId: String,
        attemptId: Long? = null,
        onTimeout: suspend (Throwable) -> Unit = {},
        onCompletion: (Throwable?) -> Unit = {},
        traceToken: DownloadOperationTraceToken? = null,
        block: suspend () -> Unit
    ): Job {
        return enqueueOrNull(
            operationId = operationId,
            attemptId = attemptId,
            onTimeout = onTimeout,
            onCompletion = onCompletion,
            traceToken = traceToken,
            block = block
        ) ?: error("asset enrichment active-job limit reached")
    }

    /** 队列已满时不创建等待协程，由持久恢复 Worker 稍后重新接管 */
    fun tryEnqueue(
        operationId: String,
        attemptId: Long? = null,
        onTimeout: suspend (Throwable) -> Unit = {},
        onCompletion: (Throwable?) -> Unit = {},
        traceToken: DownloadOperationTraceToken? = null,
        block: suspend () -> Unit
    ): Job? {
        return enqueueOrNull(
            operationId = operationId,
            attemptId = attemptId,
            onTimeout = onTimeout,
            onCompletion = onCompletion,
            traceToken = traceToken,
            block = block
        )
    }

    private fun enqueueOrNull(
        operationId: String,
        attemptId: Long?,
        onTimeout: suspend (Throwable) -> Unit,
        onCompletion: (Throwable?) -> Unit,
        traceToken: DownloadOperationTraceToken?,
        block: suspend () -> Unit
    ): Job? {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank)
            ?: error("asset enrichment requires operationId")
        return synchronized(jobRegistrationLock) {
            jobsByOperationId[normalizedId]?.let { existing ->
                if (existing.isActive) return@synchronized existing
            }
            if (jobsByOperationId.values.count(Job::isActive) >= maxActiveJobs) {
                return@synchronized null
            }
            val terminalError = AtomicReference<Throwable?>(null)
            val completionDelivered = AtomicBoolean(false)
            val jobReference = AtomicReference<Job?>(null)
            val operationTraceToken = traceToken
                ?.takeIf { token ->
                    token.operationId == normalizedId && token.attemptId == attemptId
                }
                ?: DownloadOperationTrace.begin(
                    operationId = normalizedId,
                    attemptId = attemptId
                )
            DownloadOperationTrace.mark(
                operationTraceToken,
                DownloadOperationTracePhase.ENRICHMENT_ENQUEUED
            )
            fun deliverCompletion(error: Throwable?) {
                if (!completionDelivered.compareAndSet(false, true)) return
                synchronized(jobRegistrationLock) {
                    jobReference.get()?.let { registeredJob ->
                        jobsByOperationId.remove(normalizedId, registeredJob)
                    }
                    refreshActiveStateLocked()
                }
                runCatching { onCompletion(error ?: terminalError.get()) }
                DownloadOperationTrace.mark(
                    operationTraceToken,
                    DownloadOperationTracePhase.TERMINAL
                )
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    semaphore.withPermit {
                        withTimeout(timeoutMs) {
                            DownloadOperationTrace.mark(
                                operationTraceToken,
                                DownloadOperationTracePhase.ENRICHMENT_STARTED
                            )
                            block()
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    runCatching { onTimeout(error) }
                        .onFailure { callbackError ->
                            terminalError.compareAndSet(null, callbackError)
                        }
                } catch (error: CancellationException) {
                    terminalError.compareAndSet(null, error)
                    throw error
                } catch (error: Throwable) {
                    terminalError.compareAndSet(null, error)
                    throw error
                } finally {
                    try {
                        DownloadOperationTrace.mark(
                            operationTraceToken,
                            DownloadOperationTracePhase.ENRICHMENT_FINISHED
                        )
                    } catch (error: Throwable) {
                        terminalError.compareAndSet(null, error)
                        throw error
                    } finally {
                        // join 返回前必须释放 operation 所有权，完成处理器只负责兜底
                        deliverCompletion(terminalError.get())
                    }
                }
            }
            jobReference.set(job)
            jobsByOperationId[normalizedId] = job
            job.invokeOnCompletion { error ->
                deliverCompletion(error)
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

    fun availableCapacity(): Int = synchronized(jobRegistrationLock) {
        (maxActiveJobs - jobsByOperationId.values.count(Job::isActive)).coerceAtLeast(0)
    }

    /** 返回仍持有活动协程的收尾 operation ID */
    fun activeOperationIds(): Set<String> = synchronized(jobRegistrationLock) {
        jobsByOperationId
            .asSequence()
            .filter { (_, job) -> job.isActive }
            .mapTo(linkedSetOf()) { (operationId, _) -> operationId }
    }

    /** 等待指定收尾任务释放活动位，不取消仍在进行的工作 */
    suspend fun awaitCompletion(
        operationIds: Collection<String>,
        timeoutMs: Long
    ): Boolean {
        val normalizedIds = operationIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedIds.isEmpty()) return true
        val jobs = synchronized(jobRegistrationLock) {
            normalizedIds.mapNotNull(jobsByOperationId::get).filter(Job::isActive)
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.joinAll()
            true
        } ?: false
        return settled && normalizedIds.none { operationId ->
            jobsByOperationId[operationId]?.isActive == true
        }
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

    /** 只等待指定清空快照中的收尾任务，不干扰清空后新建的 operation */
    suspend fun cancelAndJoin(
        operationIds: Collection<String>,
        reason: String = "asset enrichment cancelled",
        timeoutMs: Long = DEFAULT_CANCEL_JOIN_TIMEOUT_MS
    ): Boolean {
        val normalizedIds = operationIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedIds.isEmpty()) return true
        val jobs = synchronized(jobRegistrationLock) {
            normalizedIds.mapNotNull(jobsByOperationId::get).filter(Job::isActive)
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
        return settled && normalizedIds.none { operationId ->
            jobsByOperationId[operationId]?.isActive == true
        }
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
