package moe.ouom.neriplayer.core.download.resource

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

/**
 * 传输无进展监视器
 *
 * 监视器只负责把卡住的传输转换成可重试错误，不会直接释放 permit。先让传输协程退出，
 * 再由 transfer-cycle owner 回收 permit，避免旧写入继续污染新 attempt
 */
internal class DownloadTransferWatchdog(
    private val registry: DownloadTransferPermitRegistry,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val staleAfterNs: Long = DEFAULT_STALE_AFTER_NS,
    private val nowNs: () -> Long = System::nanoTime
) {
    init {
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(staleAfterNs > 0L) { "staleAfterNs must be positive" }
    }

    suspend fun <T> run(
        permit: DownloadTransferPermitRegistry.Permit,
        block: suspend () -> T
    ): T = supervisorScope {
        val transfer = async(start = CoroutineStart.UNDISPATCHED) { block() }
        val stalled = CompletableDeferred<DownloadTransferStalledException>()
        val monitor = async(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                delay(pollIntervalMs)
                if (
                    registry.isProgressStale(
                        ownerKey = permit.ownerKey,
                        generation = permit.generation,
                        staleAfterNs = staleAfterNs,
                        atNs = nowNs()
                    )
                ) {
                    stalled.complete(
                        DownloadTransferStalledException(
                            ownerKey = permit.ownerKey,
                            generation = permit.generation,
                            staleAfterMs = staleAfterNs / NANOS_PER_MILLISECOND
                        )
                    )
                    return@async
                }
            }
        }
        try {
            awaitTransferOrStall(transfer, stalled)
        } finally {
            withContext(NonCancellable) {
                monitor.cancelAndJoin()
                transfer.cancelAndJoin()
            }
        }
    }

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

internal class DownloadTransferStalledException(
    ownerKey: String,
    generation: Long,
    staleAfterMs: Long
) : IOException(
    "download transfer made no progress for ${staleAfterMs}ms: " +
        "owner=$ownerKey, generation=$generation"
)
