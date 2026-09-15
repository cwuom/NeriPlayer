package moe.ouom.neriplayer.core.download.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 隔离同一批下载中的单项失败，避免一个 operation 取消时连带取消其他歌曲
 */
internal suspend fun executePumpCandidateIsolated(
    operationId: String,
    execute: suspend () -> DownloadExecutionResult
): DownloadExecutionResult {
    return try {
        execute()
    } catch (cancellation: CancellationException) {
        // 父级泵被取消时必须继续抛出，只有单项主动取消才转换成结果
        currentCoroutineContext().ensureActive()
        NPLogger.d(
            "NERI-DownloadHost",
            "批量泵中的 operation 已取消，继续处理同批任务: " +
                "operationId=$operationId, reason=${cancellation.message ?: "cancelled"}"
        )
        DownloadExecutionResult.Cancelled
    } catch (error: Throwable) {
        NPLogger.e(
            "NERI-DownloadHost",
            "批量泵中的 operation 异常，继续处理同批任务: operationId=$operationId",
            error
        )
        DownloadExecutionResult.Failed(error)
    }
}
