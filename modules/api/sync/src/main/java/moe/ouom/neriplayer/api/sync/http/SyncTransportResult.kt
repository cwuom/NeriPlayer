package moe.ouom.neriplayer.api.sync.http

import kotlinx.coroutines.CancellationException

internal fun <T> syncTransportResult(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
