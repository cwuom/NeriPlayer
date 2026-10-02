package moe.ouom.neriplayer.data.sync.work

import moe.ouom.neriplayer.api.sync.github.GitHubRateLimitException
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerHost

/** 限流续传交给 WorkManager，避免占用同步协程等待服务器恢复 */
internal class GitHubRateLimitedWorkerHost(
    private val delegate: SyncWorkerHost,
    private val scheduleContinuation: (delayMillis: Long, manual: Boolean) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : SyncWorkerHost by delegate {
    override suspend fun handleFailure(error: Throwable?, manual: Boolean, unexpected: Boolean): SyncWorkerOutcome {
        if (error !is GitHubRateLimitException) return delegate.handleFailure(error, manual, unexpected)
        if (!error.automaticRetryAllowed) {
            delegate.handleFailure(error, manual, unexpected)
            return SyncWorkerOutcome.FAILURE
        }
        val remaining = error.retryAtMillis.coerceAtLeast(0L) - nowMillis().coerceAtLeast(0L)
        scheduleContinuation(remaining.coerceAtLeast(1_000L), manual)
        return SyncWorkerOutcome.SUCCESS
    }
}
