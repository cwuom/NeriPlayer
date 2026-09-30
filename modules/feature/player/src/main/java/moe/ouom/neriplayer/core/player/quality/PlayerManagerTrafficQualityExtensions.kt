package moe.ouom.neriplayer.core.player.quality

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataYouTubeAudioQuality
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType

internal fun PlayerManager.effectiveNeteaseQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.NETEASE,
        defaultQuality = preferredQuality
    )
}

internal fun PlayerManager.effectiveYouTubeQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.YOUTUBE_MUSIC,
        defaultQuality = youtubePreferredQuality
    )
}

internal fun PlayerManager.effectiveBiliQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.BILIBILI,
        defaultQuality = biliPreferredQuality
    )
}

private fun PlayerManager.resolveTrafficAwareQuality(
    source: PlaybackAudioSource,
    defaultQuality: String
): String {
    if (!isApplicationInitialized()) {
        return defaultQuality
    }
    val networkType = application.currentTrafficNetworkType()
    if (networkType == TrafficNetworkType.WIFI) {
        return defaultQuality
    }
    if (mobileDataFollowDefaultAudioQuality) {
        return defaultQuality
    }

    return when (source) {
        PlaybackAudioSource.NETEASE ->
            normalizeMobileDataNeteaseAudioQuality(mobileDataNeteaseAudioQuality)
        PlaybackAudioSource.YOUTUBE_MUSIC ->
            normalizeMobileDataYouTubeAudioQuality(mobileDataYouTubeAudioQuality)
        PlaybackAudioSource.BILIBILI ->
            normalizeMobileDataBiliAudioQuality(mobileDataBiliAudioQuality)
        else -> defaultQuality
    }
}
