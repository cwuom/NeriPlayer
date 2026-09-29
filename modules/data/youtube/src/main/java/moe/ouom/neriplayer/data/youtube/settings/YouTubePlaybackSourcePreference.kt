package moe.ouom.neriplayer.data.youtube.settings

import java.util.Locale
import moe.ouom.neriplayer.api.youtube.model.playback.YouTubePlaybackSourcePreference

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
