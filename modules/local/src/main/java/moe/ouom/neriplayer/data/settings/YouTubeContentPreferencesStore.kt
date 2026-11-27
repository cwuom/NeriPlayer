package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import moe.ouom.neriplayer.platform.youtube.settings.PlaylistSuggestionSource
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings

fun Preferences.toYouTubeContentPreferences(): YouTubeContentPreferences = YouTubeContentPreferences(
    languageTag = this[SettingsKeys.YOUTUBE_CONTENT_LANGUAGE]?.takeIf { it in YouTubeContentSettings.languageTags }.orEmpty(),
    countryCode = this[SettingsKeys.YOUTUBE_CONTENT_COUNTRY]?.takeIf { it in YouTubeContentSettings.countryCodes }.orEmpty(),
    hideExplicit = this[SettingsKeys.YOUTUBE_HIDE_EXPLICIT] ?: false,
    hideMusicVideos = this[SettingsKeys.YOUTUBE_HIDE_MUSIC_VIDEOS] ?: false,
    playlistSuggestionSource = PlaylistSuggestionSource.entries.firstOrNull {
        it.name == this[SettingsKeys.YOUTUBE_PLAYLIST_SUGGESTION_SOURCE]
    } ?: PlaylistSuggestionSource.Both
)

suspend fun Context.updateYouTubeContentPreferences(change: (YouTubeContentPreferences) -> YouTubeContentPreferences) {
    dataStore.edit { prefs ->
        val next = change(prefs.toYouTubeContentPreferences())
        if (next.languageTag.isEmpty()) prefs.remove(SettingsKeys.YOUTUBE_CONTENT_LANGUAGE)
        else prefs[SettingsKeys.YOUTUBE_CONTENT_LANGUAGE] = next.languageTag
        if (next.countryCode.isEmpty()) prefs.remove(SettingsKeys.YOUTUBE_CONTENT_COUNTRY)
        else prefs[SettingsKeys.YOUTUBE_CONTENT_COUNTRY] = next.countryCode
        prefs[SettingsKeys.YOUTUBE_HIDE_EXPLICIT] = next.hideExplicit
        prefs[SettingsKeys.YOUTUBE_HIDE_MUSIC_VIDEOS] = next.hideMusicVideos
        prefs[SettingsKeys.YOUTUBE_PLAYLIST_SUGGESTION_SOURCE] = next.playlistSuggestionSource.name
    }
}
