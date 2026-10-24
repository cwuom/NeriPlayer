package moe.ouom.neriplayer.ui.settings.owner

import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions

internal class AppSettingsCacheClearOwner(
    private val clearPlayerCache: suspend (StorageCacheClearOptions) -> ExtraCacheClearResult,
    private val clearLyricsCache: suspend () -> Unit,
    private val clearExtraCaches: suspend (StorageCacheClearOptions) -> ExtraCacheClearResult,
    private val formatExtraResult: (ExtraCacheClearResult) -> String
) {
    suspend fun clear(options: StorageCacheClearOptions): String {
        if (!options.hasSelection) return ""
        var result = ExtraCacheClearResult(true, 0, 0, 0)
        if (options.needsPlayerCacheClear) {
            result = clearPlayerCache(options)
            // 保留名单读取失败时不能继续清除其它类型，避免只执行一半用户要求
            if (!result.success && options.retainRecentSongs > 0) return formatExtraResult(result)
        }
        if (options.needsExtraCacheClear) {
            if (options.lyricsCache) {
                clearLyricsCache()
            }
            val extra = clearExtraCaches(options)
            result = ExtraCacheClearResult(
                success = result.success && extra.success,
                freedBytes = result.freedBytes + extra.freedBytes,
                roomBytesMadeReusable = result.roomBytesMadeReusable + extra.roomBytesMadeReusable,
                deletedFiles = result.deletedFiles + extra.deletedFiles
            )
        }
        return formatExtraResult(result)
    }
}

internal fun formatExtraCacheClearResult(
    result: ExtraCacheClearResult,
    partialMessage: () -> String,
    roomCompleteMessage: (Long, Long) -> String,
    completeMessage: (Long) -> String
): String {
    val summary = if (result.roomBytesMadeReusable > 0L) {
        roomCompleteMessage(result.freedBytes, result.roomBytesMadeReusable)
    } else {
        completeMessage(result.freedBytes)
    }
    return if (result.success) summary else "${partialMessage()} · $summary"
}
