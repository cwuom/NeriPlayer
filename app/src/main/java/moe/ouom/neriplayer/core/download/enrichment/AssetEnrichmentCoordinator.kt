package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val startJob: (Job) -> Unit = { it.start() }
) {
    private val normalizedParallelism = parallelism.coerceAtLeast(1)
    private val maxActiveJobs = maxActiveJobs.coerceAtLeast(normalizedParallelism)
    private val semaphore = Semaphore(normalizedParallelism)
    private class Entry(val job: Job, val settled: CompletableDeferred<Unit> = CompletableDeferred())

    private val jobsByOperationId = ConcurrentHashMap<String, Entry>()
    private val jobRegistrationLock = Any()
    private var overflowOperationId: String? = null
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
        allowSingleOverflow: Boolean = false,
        onTimeout: suspend (Throwable) -> Unit = {},
        onCompletion: (Throwable?) -> Unit = {},
        traceToken: DownloadOperationTraceToken? = null,
        block: suspend () -> Unit
    ): Job? {
        return enqueueOrNull(
            operationId = operationId,
            attemptId = attemptId,
            allowSingleOverflow = allowSingleOverflow,
            onTimeout = onTimeout,
            onCompletion = onCompletion,
            traceToken = traceToken,
            block = block
        )
    }

    private fun enqueueOrNull(
        operationId: String,
        attemptId: Long?,
        allowSingleOverflow: Boolean = false,
        onTimeout: suspend (Throwable) -> Unit,
        onCompletion: (Throwable?) -> Unit,
        traceToken: DownloadOperationTraceToken?,
        block: suspend () -> Unit
    ): Job? {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank)
            ?: error("asset enrichment requires operationId")
        var completionHandler: ((Throwable?) -> Unit)? = null
        val registered = synchronized(jobRegistrationLock) {
            jobsByOperationId[normalizedId]?.let { existing ->
                return existing.job
            }
            val activeOverflowId = activeOverflowOperationIdLocked()
            val normalActiveCount = jobsByOperationId.size -
                if (activeOverflowId != null) 1 else 0
            val usesOverflow = normalActiveCount >= maxActiveJobs
            if (usesOverflow && (!allowSingleOverflow || activeOverflowId != null)) {
                return@synchronized null
            }
            val terminalError = AtomicReference<Throwable?>(null)
            val completionDelivered = AtomicBoolean(false)
            val entryReference = AtomicReference<Entry?>(null)
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
                try {
                    runCatching { onCompletion(error ?: terminalError.get()) }
                    runCatching {
                        DownloadOperationTrace.mark(
                            operationTraceToken,
                            DownloadOperationTracePhase.TERMINAL
                        )
                    }
                } finally {
                    val entry = checkNotNull(entryReference.get())
                    synchronized(jobRegistrationLock) {
                        val removed = jobsByOperationId.remove(normalizedId, entry)
                        if (removed && overflowOperationId == normalizedId) overflowOperationId = null
                        refreshActiveStateLocked()
                        entry.settled.complete(Unit)
                    }
                }
            }

            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    suspend fun runEnrichment() {
                        withTimeout(timeoutMs) {
                            DownloadOperationTrace.mark(
                                operationTraceToken,
                                DownloadOperationTracePhase.ENRICHMENT_STARTED
                            )
                            block()
                        }
                    }
                    if (usesOverflow) {
                        runEnrichment()
                    } else {
                        semaphore.withPermit { runEnrichment() }
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
                    }
                }
            }
            val entry = Entry(job)
            entryReference.set(entry)
            jobsByOperationId[normalizedId] = entry
            if (usesOverflow) overflowOperationId = normalizedId
            completionHandler = ::deliverCompletion
            refreshActiveStateLocked()
            entry
        } ?: return null
        // 已取消 scope 的完成处理器可能同步执行，不能在登记锁内调用外部回调
        val deliverCompletion = checkNotNull(completionHandler)
        registered.job.invokeOnCompletion { error ->
            // LAZY 取消可能同步触发处理器，外部回调不能阻塞取消调用者的超时边界
            Dispatchers.IO.dispatch(EmptyCoroutineContext) { deliverCompletion(error) }
        }
        startJob(registered.job)
        return registered.job
    }

    fun cancel(operationId: String): Boolean {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank) ?: return false
        val job = synchronized(jobRegistrationLock) {
            jobsByOperationId[normalizedId]
        } ?: return false
        val wasActive = !job.job.isCompleted
        if (wasActive) {
            job.job.cancel(CancellationException("asset enrichment cancelled"))
        }
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return wasActive
    }

    fun activeCount(): Int = synchronized(jobRegistrationLock) {
        jobsByOperationId.size
    }

    fun availableCapacity(): Int = synchronized(jobRegistrationLock) {
        val activeOverflowId = activeOverflowOperationIdLocked()
        val normalActiveCount = jobsByOperationId.size -
            if (activeOverflowId != null) 1 else 0
        (maxActiveJobs - normalActiveCount).coerceAtLeast(0)
    }

    fun isActive(operationId: String): Boolean {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank) ?: return false
        return synchronized(jobRegistrationLock) {
            jobsByOperationId.containsKey(normalizedId)
        }
    }

    /** 返回仍持有活动协程的收尾 operation ID */
    fun activeOperationIds(): Set<String> = synchronized(jobRegistrationLock) {
        jobsByOperationId
            .asSequence()
            .mapTo(linkedSetOf()) { (operationId, _) -> operationId }
    }

    /** 等待指定收尾任务释放活动位，不取消仍在进行的工作 */
    suspend fun awaitAnyCompletion(
        operationIds: Collection<String>,
        timeoutMs: Long
    ): Set<String> {
        val entries = synchronized(jobRegistrationLock) {
            operationIds.distinct().associateWith(jobsByOperationId::get)
        }
        if (entries.isEmpty()) return emptySet()
        fun completedIds() = entries.filterValues { it == null || it.settled.isCompleted }.keys
        completedIds().takeIf { it.isNotEmpty() }?.let { return it }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            select {
                entries.values.filterNotNull().forEach { entry ->
                    entry.settled.onAwait { }
                }
            }
        }
        return completedIds()
    }

    /** 等待指定窗口全部释放，包含完成回调仍在收尾的任务 */
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
            normalizedIds.mapNotNull(jobsByOperationId::get)
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.forEach { it.settled.await() }
            true
        } ?: false
        return settled
    }

    /** 取消所有收尾任务但保留完成回调 */
    fun cancelAll(reason: String = "asset enrichment cancelled"): Int {
        val jobs = synchronized(jobRegistrationLock) {
            jobsByOperationId.values.toList()
        }
        jobs.forEach { job ->
            job.job.cancel(CancellationException(reason))
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
            normalizedIds.mapNotNull(jobsByOperationId::get)
        }
        jobs.forEach { job ->
            job.job.cancel(CancellationException(reason))
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.forEach { it.settled.await() }
            true
        } ?: false
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return settled
    }

    /** 取消所有收尾任务并在文件清理前等待一段有界时间 */
    suspend fun cancelAllAndJoin(
        reason: String = "asset enrichment cancelled",
        timeoutMs: Long = DEFAULT_CANCEL_JOIN_TIMEOUT_MS
    ): Boolean {
        val jobs = synchronized(jobRegistrationLock) {
            jobsByOperationId.values.toList()
        }
        jobs.forEach { job ->
            job.job.cancel(CancellationException(reason))
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.forEach { it.settled.await() }
            true
        } ?: false
        synchronized(jobRegistrationLock) {
            refreshActiveStateLocked()
        }
        return settled
    }

    private fun refreshActiveStateLocked() {
        _hasActiveJobs.value = jobsByOperationId.isNotEmpty()
    }

    private fun activeOverflowOperationIdLocked(): String? {
        val activeId = overflowOperationId
            ?.takeIf { operationId -> jobsByOperationId.containsKey(operationId) }
        if (activeId == null) {
            overflowOperationId = null
        }
        return activeId
    }

    companion object {
        const val DEFAULT_PARALLELISM = 2
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_CANCEL_JOIN_TIMEOUT_MS = 5_000L
    }
}
