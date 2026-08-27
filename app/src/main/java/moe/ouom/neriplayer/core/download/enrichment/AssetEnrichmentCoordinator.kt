package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
            val job = scope.launch(start = CoroutineStart.LAZY) {
                semaphore.withPermit {
                    try {
                        withTimeout(timeoutMs) { block() }
                    } catch (error: TimeoutCancellationException) {
                        runCatching { onTimeout(error) }
                    } catch (error: CancellationException) {
                        throw error
                    }
                }
            }
            jobsByOperationId[normalizedId] = job
            job.invokeOnCompletion { error ->
                jobsByOperationId.remove(normalizedId, job)
                runCatching { onCompletion(error) }
            }
            job.start()
            job
        }
    }

    fun cancel(operationId: String): Boolean {
        val job = jobsByOperationId.remove(operationId) ?: return false
        val wasActive = job.isActive
        job.cancel()
        return wasActive
    }

    fun activeCount(): Int = jobsByOperationId.values.count(Job::isActive)

    companion object {
        const val DEFAULT_PARALLELISM = 2
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
