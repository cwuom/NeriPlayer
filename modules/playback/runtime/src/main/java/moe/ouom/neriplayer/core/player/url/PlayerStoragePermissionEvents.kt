package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.StoragePermissionFallback
import moe.ouom.neriplayer.data.model.playback.StoragePermissionScope
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution

internal fun localStoragePermissionLostEvent(
    reference: String?,
    fallback: StoragePermissionFallback
): PlayerEvent.StoragePermissionLost = PlayerEvent.StoragePermissionLost(
    scope = StoragePermissionScope.LOCAL_MEDIA,
    fallback = fallback,
    reference = reference?.trim()?.takeIf(String::isNotBlank)
)

/** 播放侧拿不到索引引用，由 UI 以当前下载目录作为重新授权目标 */
internal fun downloadDirectoryPermissionLostEvent(): PlayerEvent.StoragePermissionLost =
    PlayerEvent.StoragePermissionLost(
        scope = StoragePermissionScope.DOWNLOAD_DIRECTORY,
        fallback = StoragePermissionFallback.STREAMING
    )

/** 授权失效和文件缺失一样读不到本地副本，下载过的网易云歌曲可以改回在线来源 */
internal fun PlayerLocalPlaybackResolution.isPermissionLost(): Boolean =
    this is PlayerLocalPlaybackResolution.PermissionLost

internal fun PlayerManager.reportLocalPermissionLostFallback(
    resolution: PlayerLocalPlaybackResolution,
    reference: String?,
    sideEffects: RefreshResolverSideEffects
) {
    if (resolution !is PlayerLocalPlaybackResolution.PermissionLost) return
    sideEffects.emitError {
        postPlayerEvent(localStoragePermissionLostEvent(reference, StoragePermissionFallback.STREAMING))
    }
}

/** 授权失效时用专门的重新授权弹窗代替通用的“无法获取播放地址” */
internal fun PlayerManager.reportLocalPlaybackFailure(
    song: SongItem,
    resolution: PlayerLocalPlaybackResolution,
    reference: String?,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult {
    val event = if (resolution is PlayerLocalPlaybackResolution.PermissionLost) {
        NPLogger.w(
            "NERI-PlayerManager",
            "Local reference lost its SAF grant; skip until the folder is authorized again: " +
                "id=${song.id}, song=${song.name}, reference=${playbackLogReference(reference)}"
        )
        localStoragePermissionLostEvent(reference, StoragePermissionFallback.SKIPPED)
    } else {
        PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url))
    }
    sideEffects.emitError { postPlayerEvent(event) }
    return SongUrlResult.Failure
}

/**
 * 授权失效不会自行恢复，继续阻断只会让已下载歌曲一直无法播放，因此返回 null 让调用方改走在线地址
 */
internal fun PlayerManager.reportIndexedPermissionLost(
    song: SongItem,
    evidence: String,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult? {
    NPLogger.w(
        "NERI-PlayerManager",
        "checkLocalCache: indexed download lost its SAF grant; allow remote fallback: " +
            "song=${song.name}, evidence=$evidence"
    )
    sideEffects.emitError { postPlayerEvent(downloadDirectoryPermissionLostEvent()) }
    return null
}
