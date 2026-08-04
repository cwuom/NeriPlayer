package moe.ouom.neriplayer.core.api.youtube

import moe.ouom.neriplayer.data.settings.YouTubePlaybackSourcePreference

internal enum class YouTubePlayerClientSource {
    VISION_OS,
    ANDROID_VR,
    WEB_REMIX,
    TV_HTML5,
    WEB_CREATOR,
    TV_HTML5_LEGACY
}

internal fun resolveYouTubePlayerClientOrder(
    preference: YouTubePlaybackSourcePreference,
    preferAuthenticatedWebRemix: Boolean = false
): List<YouTubePlayerClientSource> {
    val anonymousAutomaticOrder = listOf(
        YouTubePlayerClientSource.VISION_OS,
        YouTubePlayerClientSource.ANDROID_VR,
        YouTubePlayerClientSource.WEB_REMIX,
        YouTubePlayerClientSource.TV_HTML5,
        YouTubePlayerClientSource.WEB_CREATOR,
        YouTubePlayerClientSource.TV_HTML5_LEGACY
    )
    // 登录态承载区域和资料库权限，自动模式先保留原有的网页音乐请求链
    val automaticOrder = if (preferAuthenticatedWebRemix) {
        listOf(YouTubePlayerClientSource.WEB_REMIX) +
            anonymousAutomaticOrder.filterNot { it == YouTubePlayerClientSource.WEB_REMIX }
    } else {
        anonymousAutomaticOrder
    }
    val preferred = when (preference) {
        YouTubePlaybackSourcePreference.Automatic -> null
        YouTubePlaybackSourcePreference.VisionOs -> YouTubePlayerClientSource.VISION_OS
        YouTubePlaybackSourcePreference.AndroidVr -> YouTubePlayerClientSource.ANDROID_VR
        YouTubePlaybackSourcePreference.WebRemix -> YouTubePlayerClientSource.WEB_REMIX
        YouTubePlaybackSourcePreference.TvHtml5 -> YouTubePlayerClientSource.TV_HTML5
        YouTubePlaybackSourcePreference.WebCreator -> YouTubePlayerClientSource.WEB_CREATOR
    }
    return if (preferred == null) {
        automaticOrder
    } else {
        listOf(preferred) + automaticOrder.filterNot { it == preferred }
    }
}
