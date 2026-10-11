package moe.ouom.neriplayer.core.download.resource.watchdog

import moe.ouom.neriplayer.core.download.resource.permit.DownloadTransferPermitRegistry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * 传输无进展监视器
 *
 * 监视器只负责把卡住的传输转换成可重试错误，不会直接释放 permit。先让传输协程退出，
 * 再由 transfer-cycle owner 回收 permit，避免旧写入继续污染新 attempt
 */
class DownloadTransferWatchdog(
    private val registry: DownloadTransferPermitRegistry,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val staleAfterNs: Long = DEFAULT_STALE_AFTER_NS,
    private val nowNs: () -> Long = System::nanoTime
) {
    init {
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(staleAfterNs > 0L) { "staleAfterNs must be positive" }
    }

    /**
     * 监视器先启动，传输再派发到调用方的调度器；传输里的阻塞读不会挂起，
     * 如果先在当前线程跑传输，监视器要等传输结束才会出现。判定卡住时先调用 [onStalled]
     * 中断底层网络调用，阻塞读才能退出，否则只能等网络库自己超时
     */
    suspend fun <T> run(
        permit: DownloadTransferPermitRegistry.Permit,
        onStalled: () -> Unit = {},
        block: suspend () -> T
    ): T = supervisorScope {
        val stalled = CompletableDeferred<DownloadTransferStalledException>()
        val monitor = async(start = CoroutineStart.UNDISPATCHED) {
            awaitStall(permit)
            // 先发布卡住结果再中断网络调用，被中断的阻塞读不会抢先以"已取消"结束
            stalled.complete(stalledError(permit))
            onStalled()
        }
        val transfer = async { block() }
        try {
            awaitTransferOrStall(transfer, stalled)
        } finally {
            withContext(NonCancellable) {
                monitor.cancelAndJoin()
                transfer.cancelAndJoin()
            }
        }
    }

    private suspend fun awaitStall(permit: DownloadTransferPermitRegistry.Permit) {
        while (true) {
            delay(pollIntervalMs.milliseconds)
            val stale = registry.isProgressStale(
                ownerKey = permit.ownerKey,
                generation = permit.generation,
                staleAfterNs = staleAfterNs,
                atNs = nowNs()
            )
            if (stale) return
        }
    }

    private fun stalledError(permit: DownloadTransferPermitRegistry.Permit) = DownloadTransferStalledException(
        ownerKey = permit.ownerKey,
        generation = permit.generation,
        staleAfterMs = staleAfterNs / NANOS_PER_MILLISECOND
    )

    private suspend fun <T> awaitTransferOrStall(
        transfer: Deferred<T>,
        stalled: CompletableDeferred<DownloadTransferStalledException>
    ): T {
        return select {
            transfer.onAwait { value -> value }
            stalled.onAwait { error -> throw error }
        }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        /** 与 OkHttp 读取超时错开，先由下载层写出可审计的重试原因 */
        const val DEFAULT_STALE_AFTER_NS = 30_000_000_000L
    }
}

class DownloadTransferStalledException(
    ownerKey: String,
    generation: Long,
    staleAfterMs: Long
) : IOException(
    "download transfer made no progress for ${staleAfterMs}ms: " +
        "owner=$ownerKey, generation=$generation"
)
