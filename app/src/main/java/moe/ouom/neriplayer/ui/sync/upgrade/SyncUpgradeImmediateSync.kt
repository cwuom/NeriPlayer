package moe.ouom.neriplayer.ui.sync.upgrade

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException

internal class SyncUpgradeImmediateSync(
    private val performSync: suspend (String) -> Result<SyncResult>,
    private val confirmLegacy: suspend (Boolean, SyncProtocolUpgradeChallenge) -> Unit,
    private val isTargetActive: suspend (String) -> Boolean,
    private val hasCurrentProtocol: suspend (String) -> Boolean = { true }
) {
    suspend fun execute(targetId: String, approveDetectedLegacy: Boolean): Result<SyncResult> = try {
        val first = synchronize(targetId)
        val challenge = (first.exceptionOrNull() as? SyncProtocolUpgradeRequiredException)?.challenge
        if (!approveDetectedLegacy || challenge == null || challenge.targetId != targetId) first
        else {
            requireActiveTarget(targetId)
            confirmLegacy(true, challenge)
            synchronize(targetId)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun synchronize(targetId: String): Result<SyncResult> {
        requireActiveTarget(targetId)
        val result = performSync(targetId)
        currentCoroutineContext().ensureActive()
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (result.getOrNull()?.success == true) {
            val current = hasCurrentProtocol(targetId)
            currentCoroutineContext().ensureActive()
            if (!current) throw IOException("Sync database upgrade did not finish")
        }
        return result
    }

    private suspend fun requireActiveTarget(targetId: String) {
        currentCoroutineContext().ensureActive()
        check(isTargetActive(targetId)) { "Sync target changed" }
        currentCoroutineContext().ensureActive()
    }
}
