package moe.ouom.neriplayer.data.local.storage.policy

import kotlinx.coroutines.CancellationException

internal suspend fun <T> storageScanOrDefault(fallback: T, scan: suspend () -> T): T = try {
    scan()
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    fallback
}
