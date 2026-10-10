package moe.ouom.neriplayer.data.sync.schedule

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome

class SyncWorkerExecution(private val host: SyncWorkerHost) {
    suspend fun execute(
        forceSync: Boolean,
        triggerByUserAction: Boolean,
        triggerByAppStartup: Boolean = false
    ): SyncWorkerOutcome {
        val manual = forceSync || triggerByUserAction
        return try {
            perform(manual, triggerByAppStartup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            host.handleFailure(error, manual, unexpected = true)
        }
    }

    private suspend fun perform(manual: Boolean, triggerByAppStartup: Boolean): SyncWorkerOutcome {
        if (!eligible(manual)) return SyncWorkerOutcome.SUCCESS
        if (!manual && !triggerByAppStartup && host.playbackActive()) {
            host.deferForPlayback()
            return SyncWorkerOutcome.SUCCESS
        }
        if (!host.validatedNetwork()) return SyncWorkerOutcome.RETRY
        val result = host.synchronize()
        if (result.isSuccess) return SyncWorkerOutcome.SUCCESS
        return host.handleFailure(result.exceptionOrNull(), manual, unexpected = false)
    }

    private suspend fun eligible(manual: Boolean): Boolean {
        if (!manual && !host.autoSyncEnabled()) return false
        if (!host.configured()) return false
        return host.protocolUpgradeApproved()
    }
}
