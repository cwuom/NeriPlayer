package moe.ouom.neriplayer.core.download.manager.batch

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest

internal suspend fun runCancellationConvergenceRound(
    loadRequests: suspend () -> List<DownloadExecutionRequest>,
    onReadFailure: (Throwable) -> Unit,
    cleanup: suspend (List<DownloadExecutionRequest>) -> Boolean,
    finish: suspend (List<DownloadExecutionRequest>) -> Unit
): Boolean {
    val requests = try {
        loadRequests()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // 读取失败时仍需要这些记录定位待清理产物，不能按空快照收尾
        onReadFailure(error)
        return false
    }
    if (!cleanup(requests)) return false
    finish(requests)
    return true
}
