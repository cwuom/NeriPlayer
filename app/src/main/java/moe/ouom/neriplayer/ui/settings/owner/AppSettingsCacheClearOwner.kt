package moe.ouom.neriplayer.ui.settings.owner

import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearOutcome
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions

internal class AppSettingsCacheClearOwner(
    private val clearPlayerCache: suspend (StorageCacheClearOptions) -> PlayerCacheClearOutcome,
    private val clearLyricsCache: suspend () -> Unit,
    private val clearExtraCaches: suspend (StorageCacheClearOptions) -> ExtraCacheClearResult,
    private val formatExtraResult: (ExtraCacheClearResult) -> String
) {
    suspend fun clear(options: StorageCacheClearOptions): String {
        if (!options.hasSelection) return ""
        var result = ExtraCacheClearResult(true, 0, 0, 0)
        if (options.needsPlayerCacheClear) {
            val outcome = clearPlayerCache(options)
            result = outcome.result
            // 仅在保留名单不可用时停止，普通部分失败继续清理其它类型
            if (outcome.retentionUnavailable) return formatExtraResult(result)
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
