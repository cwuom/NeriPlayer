package moe.ouom.neriplayer.ui.screen.tab

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab/SettingsDownloadDirectoryPreflight
 */

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProbeResult
import java.io.IOException

internal const val DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS = 3_000L

private val downloadDirectoryPreflightScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 在独立的 IO 子协程执行只读目录探测，避免缓慢的 DocumentsProvider
 * 把设置协程拖过界面可接受的等待时间
 */
internal suspend fun <T> runDownloadDirectoryPreflight(
    timeoutMs: Long = DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS,
    block: suspend () -> T
): Result<T>? {
    val probe = downloadDirectoryPreflightScope.async {
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    return try {
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            probe.await()
        }
    } finally {
        if (!probe.isCompleted) {
            probe.cancel()
        }
    }
}

private fun wrapDirectoryProviderFailure(
    error: Throwable
): ManagedDownloadRootProviderException {
    return error as? ManagedDownloadRootProviderException
        ?: ManagedDownloadRootProviderException(
            reference = "configured-root",
            cause = error
        )
}

internal fun directoryProbeTimeoutFailure(timeoutMs: Long): ManagedDownloadRootProviderException {
    return ManagedDownloadRootProviderException(
        reference = "configured-root",
        cause = IOException("download directory probe timed out after ${timeoutMs}ms")
    )
}

internal sealed interface DownloadDirectoryAvailability {
    data object Available : DownloadDirectoryAvailability
    data object Unavailable : DownloadDirectoryAvailability
    data class ProviderFailure(
        val error: ManagedDownloadRootProviderException
    ) : DownloadDirectoryAvailability
}

internal suspend fun resolveDownloadDirectoryPermissionLost(
    directoryUri: String?,
    isRootResolvable: suspend () -> Boolean,
    timeoutMs: Long = DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS
): Boolean {
    return when (
        resolveDownloadDirectoryAvailability(
            directoryUri = directoryUri,
            isRootResolvable = isRootResolvable,
            timeoutMs = timeoutMs
        )
    ) {
        DownloadDirectoryAvailability.Available -> false
        DownloadDirectoryAvailability.Unavailable -> true
        is DownloadDirectoryAvailability.ProviderFailure -> false
    }
}

internal suspend fun resolveDownloadDirectoryAvailability(
    directoryUri: String?,
    isRootResolvable: suspend () -> Boolean,
    timeoutMs: Long = DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS
): DownloadDirectoryAvailability {
    if (directoryUri.isNullOrBlank()) {
        return DownloadDirectoryAvailability.Available
    }
    val probeResult = runDownloadDirectoryPreflight(timeoutMs) {
        isRootResolvable()
    } ?: return DownloadDirectoryAvailability.ProviderFailure(
        directoryProbeTimeoutFailure(timeoutMs)
    )
    return probeResult.fold(
        onSuccess = { resolvable ->
            if (resolvable) {
                DownloadDirectoryAvailability.Available
            } else {
                DownloadDirectoryAvailability.Unavailable
            }
        },
        onFailure = { error ->
            DownloadDirectoryAvailability.ProviderFailure(
                wrapDirectoryProviderFailure(error)
            )
        }
    )
}

internal suspend fun probeConfiguredDownloadRoot(context: Context): Boolean {
    val result = ManagedDownloadStorage.probeStorageRoot(context)
    if (result is ManagedDownloadRootProbeResult.ProviderFailure) {
        throw result.error
    }
    return result == ManagedDownloadRootProbeResult.Accessible
}
