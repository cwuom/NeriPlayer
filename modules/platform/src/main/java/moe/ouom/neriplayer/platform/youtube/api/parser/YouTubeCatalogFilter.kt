package moe.ouom.neriplayer.platform.youtube.api.parser

import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.filterCatalogItems(preferences: YouTubeContentPreferences): Int {
    if (!preferences.hideExplicit && !preferences.hideMusicVideos) return 0
    var removed = 0
    val pending = ArrayDeque<Any>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeLast()) {
            is JSONObject -> node.keys().forEach { key ->
                val child = node.opt(key)
                if (child is JSONObject || child is JSONArray) pending.add(child)
            }
            is JSONArray -> for (i in node.length() - 1 downTo 0) {
                val child = node.opt(i)
                val renderer = (child as? JSONObject)?.let {
                    it.optJSONObject("musicResponsiveListItemRenderer") ?: it.optJSONObject("musicTwoRowItemRenderer")
                }
                if (renderer != null) {
                    if (preferences.hides(renderer.contentLabel())) {
                        node.remove(i)
                        removed++
                    }
                } else if (child is JSONObject || child is JSONArray) {
                    pending.add(child)
                }
            }
        }
    }
    return removed
}

internal fun JSONObject.contentLabel(knownVideo: Boolean = false): YouTubeContentLabel? =
    YouTubeContentLabel.from(hasExplicitBadge(), knownVideo || YouTubeMusicParser.isMusicVideo(this))

private fun JSONObject.hasExplicitBadge(): Boolean =
    sequenceOf(optJSONArray("badges"), optJSONArray("subtitleBadges")).filterNotNull().any { badges ->
        (0 until badges.length()).any { index ->
            val badge = badges.optJSONObject(index)?.optJSONObject("musicInlineBadgeRenderer")
            badge?.optJSONObject("icon")?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
        }
    }
