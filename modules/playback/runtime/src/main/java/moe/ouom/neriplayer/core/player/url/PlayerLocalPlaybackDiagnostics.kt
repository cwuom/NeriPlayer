package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution

internal fun playbackFailureSongDescription(song: SongItem, requestToken: Long): String =
    "id=${song.id}, channel=${song.channelId}, albumId=${song.albumId}, " +
        "audioIdPresent=${!song.audioId.isNullOrBlank()}, " +
        "sourceStableKeyPresent=${!song.sourceStableKey.isNullOrBlank()}, " +
        "mediaUri=${playbackLogReference(song.mediaUri)}, " +
        "localPath=${playbackLogReference(song.localFilePath)}, requestToken=$requestToken"

internal fun logSongUrlResolutionRequest(
    song: SongItem,
    forceRefresh: Boolean,
    currentUrl: String?,
    requestToken: Long,
    stackHint: String
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "resolveSongUrl: id=${song.id}, channel=${song.channelId}, albumId=${song.albumId}, " +
            "song=${song.name}, source=${song.album}, forceRefresh=$forceRefresh, " +
            "requestToken=$requestToken, streamUrl=${playbackLogReference(song.streamUrl)}, " +
            "currentUrl=${playbackLogReference(currentUrl)}, stack=[$stackHint]"
    )
}

internal fun logLocalPlaybackResolution(
    song: SongItem,
    requestToken: Long,
    rawReference: String?,
    resolution: PlayerLocalPlaybackResolution
) {
    val selected = (resolution as? PlayerLocalPlaybackResolution.Playable)?.reference ?: rawReference
    val message = "LocalPlaybackTrace stage=resolve_result id=${song.id} channel=${song.channelId} " +
        "requestToken=$requestToken result=${localPlaybackResolutionLabel(resolution)} " +
        "selected=${playbackLogReference(selected)}"
    if (resolution is PlayerLocalPlaybackResolution.Playable) NPLogger.d("NERI-PlayerManager", message)
    else NPLogger.w("NERI-PlayerManager", message)
}

internal fun logLocalPlaybackRetry(
    song: SongItem,
    resolution: PlayerLocalPlaybackResolution,
    retryNumber: Int,
    retryCount: Int,
    delayMs: Long,
    mode: String
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "LocalPlaybackTrace stage=retry id=${song.id} mode=$mode " +
            "retry=$retryNumber/$retryCount delayMs=$delayMs result=${localPlaybackResolutionLabel(resolution)}"
    )
}

private fun localPlaybackResolutionLabel(resolution: PlayerLocalPlaybackResolution): String = when (resolution) {
    is PlayerLocalPlaybackResolution.Playable -> "playable"
    PlayerLocalPlaybackResolution.Missing -> "missing"
    PlayerLocalPlaybackResolution.NotIndexed -> "not_indexed"
    is PlayerLocalPlaybackResolution.TemporarilyUnavailable -> "temporarily_unavailable"
}
