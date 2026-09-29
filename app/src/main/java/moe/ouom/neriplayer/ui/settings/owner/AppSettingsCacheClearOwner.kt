package moe.ouom.neriplayer.ui.settings.owner

import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions

internal class AppSettingsCacheClearOwner(
    private val clearPlayerCache: suspend (StorageCacheClearOptions) -> String,
    private val clearLyricsCache: suspend () -> Unit,
    private val clearExtraCaches: suspend (StorageCacheClearOptions) -> ExtraCacheClearResult,
    private val formatExtraResult: (ExtraCacheClearResult) -> String
) {
    suspend fun clear(options: StorageCacheClearOptions): String {
        val messages = mutableListOf<String>()
        if (options.needsPlayerCacheClear) {
            messages += clearPlayerCache(options)
        }
        if (options.needsExtraCacheClear) {
            if (options.lyricsCache) {
                clearLyricsCache()
            }
            messages += formatExtraResult(clearExtraCaches(options))
        }
        return messages.joinToString(" · ")
    }
}

internal fun formatExtraCacheClearResult(
    result: ExtraCacheClearResult,
    partialMessage: () -> String,
    roomCompleteMessage: (Long, Long) -> String,
    completeMessage: (Long) -> String
): String = when {
    !result.success -> partialMessage()
    result.roomBytesMadeReusable > 0L ->
        roomCompleteMessage(result.freedBytes, result.roomBytesMadeReusable)
    else -> completeMessage(result.freedBytes)
}
