package moe.ouom.neriplayer.data.storage.policy

import kotlinx.coroutines.CancellationException

internal suspend fun <T> storageScanOrDefault(fallback: T, scan: suspend () -> T): T = try {
    scan()
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    fallback
}
