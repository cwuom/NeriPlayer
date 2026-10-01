package moe.ouom.neriplayer.common.coroutines

import kotlinx.coroutines.CancellationException

suspend fun <T> runCatchingNonCancellation(
    block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
}
