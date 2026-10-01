package moe.ouom.neriplayer.core.download.storage.tree.query

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Binder 查询可能不响应线程中断，超时后保留槽位直到实际调用结束，不能无限增开线程 */
internal class BoundedStorageQueryRunner<T>(
    parallelism: Int = 4,
    private val timeoutMs: Long = 10_000L
) : AutoCloseable {
    private class Pending<T> {
        val result = CompletableFuture<T>()
        val abandoned = AtomicBoolean(false)
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending<T>>()
    private val executor = ThreadPoolExecutor(
        0, parallelism, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        { command -> Thread(command, "managed-storage-query").apply { isDaemon = true } }
    )

    fun query(key: String, read: () -> T): T? {
        val query = synchronized(lock) {
            pending[key] ?: Pending<T>().also { created ->
                pending[key] = created
                try {
                    executor.execute {
                        val completed = runCatching(read)
                        synchronized(lock) {
                            // 唤醒调用方前先移除旧查询，立即重试不能再拿到上一轮的异常或快照
                            pending.remove(key, created)
                            if (!created.abandoned.get()) {
                                completed.fold(created.result::complete, created.result::completeExceptionally)
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    pending.remove(key, created)
                    created.abandoned.set(true)
                }
            }
        }
        if (query.abandoned.get()) return null
        return try {
            query.result.get(timeoutMs, TimeUnit.MILLISECONDS).takeUnless { query.abandoned.get() }
        } catch (_: TimeoutException) {
            query.abandoned.set(true)
            null
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("storage query wait interrupted", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun close() {
        synchronized(lock) {
            pending.values.forEach { it.abandoned.set(true) }
        }
        executor.shutdownNow()
    }
}
