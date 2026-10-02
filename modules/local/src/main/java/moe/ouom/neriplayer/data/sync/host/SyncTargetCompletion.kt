package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.sync.SyncResult

internal suspend fun verifyTargetSyncCompletion(
    result: Result<SyncResult>,
    targetId: String,
    loadProtocolVersion: suspend (String) -> Int
): Result<SyncResult> {
    currentCoroutineContext().ensureActive()
    val syncFailure = result.exceptionOrNull()
    if (syncFailure is CancellationException) throw syncFailure
    if (result.getOrNull()?.success != true) return result
    return try {
        val version = loadProtocolVersion(targetId)
        currentCoroutineContext().ensureActive()
        if (version == SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION) result
        else Result.failure(IOException("Sync database upgrade did not finish"))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
