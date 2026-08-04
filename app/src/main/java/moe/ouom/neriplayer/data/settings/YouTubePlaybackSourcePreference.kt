package moe.ouom.neriplayer.data.settings

import java.util.Locale

enum class YouTubePlaybackSourcePreference(
    val storageValue: String
) {
    Automatic("automatic"),
    VisionOs("visionos"),
    AndroidVr("android_vr"),
    WebRemix("web_remix"),
    TvHtml5("tv_html5"),
    WebCreator("web_creator")
}

const val DEFAULT_YOUTUBE_PLAYBACK_SOURCE = "automatic"

object YouTubePlaybackSourcePreferencePolicy {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): YouTubePlaybackSourcePreference {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            YouTubePlaybackSourcePreference.Automatic.storageValue ->
                YouTubePlaybackSourcePreference.Automatic
            YouTubePlaybackSourcePreference.VisionOs.storageValue,
            "vision_os" -> YouTubePlaybackSourcePreference.VisionOs
            YouTubePlaybackSourcePreference.AndroidVr.storageValue,
            "androidvr" -> YouTubePlaybackSourcePreference.AndroidVr
            YouTubePlaybackSourcePreference.TvHtml5.storageValue ->
                YouTubePlaybackSourcePreference.TvHtml5
            YouTubePlaybackSourcePreference.WebCreator.storageValue,
            "creator" -> YouTubePlaybackSourcePreference.WebCreator
            YouTubePlaybackSourcePreference.WebRemix.storageValue ->
                YouTubePlaybackSourcePreference.WebRemix
            else -> YouTubePlaybackSourcePreference.Automatic
        }
    }
}
