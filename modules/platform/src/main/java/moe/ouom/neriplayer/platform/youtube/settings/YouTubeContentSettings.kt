package moe.ouom.neriplayer.platform.youtube.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import moe.ouom.neriplayer.common.locale.DeviceLocale
import moe.ouom.neriplayer.platform.youtube.media.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.SongItem
import java.util.Locale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeCatalogPolicy
import moe.ouom.neriplayer.data.model.youtube.music.youtubeContentLanguageTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class PlaylistSuggestionSource {
    Title, Tracks, Both
}

data class YouTubeContentPreferences(
    override val languageTag: String = "",
    override val countryCode: String = "",
    override val hideExplicit: Boolean = false,
    override val hideMusicVideos: Boolean = false,
    val playlistSuggestionSource: PlaylistSuggestionSource = PlaylistSuggestionSource.Both
) : YouTubeCatalogPolicy {
    fun catalogKey(system: Locale): String {
        val locale = resolveLocale(system)
        return "${locale.hl}/${locale.gl}/$hideExplicit/$hideMusicVideos"
    }

    internal val acceptsLegacyCache: Boolean
        get() = languageTag.isEmpty() && countryCode.isEmpty() && !hideExplicit && !hideMusicVideos
}

object YouTubeContentSettings {
    val languageTags = youtubeContentLanguageTags
    val countryCodes = listOf(
        "AE", "AR", "AU", "BR", "CA", "CL", "CN", "CO", "DE", "EG", "ES", "FR", "GB",
        "HK", "ID", "IN", "IT", "JP", "KR", "MX", "MY", "NG", "NL", "PE", "PH", "PL",
        "RU", "SA", "SG", "TH", "TR", "TW", "US", "VN", "ZA"
    )

    @Volatile
    private var appContext: Context? = null
    private var store: SharedPreferences? = null
    private val state = MutableStateFlow(YouTubeContentPreferences())
    val preferences = state.asStateFlow()
    val catalogKeyFlow = combine(preferences, DeviceLocale.changes) { prefs, locale ->
        prefs.catalogKey(locale)
    }.distinctUntilChanged()

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        DeviceLocale.refresh(context)
        val storage = context.applicationContext.getSharedPreferences("youtube_content", Context.MODE_PRIVATE)
        store = storage
        state.value = YouTubeContentPreferences(
            languageTag = storage.getString("language", "").orEmpty().takeIf { it in languageTags }.orEmpty(),
            countryCode = storage.getString("country", "").orEmpty().takeIf { it in countryCodes }.orEmpty(),
            hideExplicit = storage.getBoolean("hide_explicit", false),
            hideMusicVideos = storage.getBoolean("hide_music_videos", false),
            playlistSuggestionSource = PlaylistSuggestionSource.entries.firstOrNull {
                it.name == storage.getString("playlist_suggestions", "")
            } ?: PlaylistSuggestionSource.Both
        )
    }

    @Synchronized
    fun publish(value: YouTubeContentPreferences) {
        if (value == state.value) return
        val storage = checkNotNull(store)
        storage.edit {
            putString("language", value.languageTag)
            putString("country", value.countryCode)
            putBoolean("hide_explicit", value.hideExplicit)
            putBoolean("hide_music_videos", value.hideMusicVideos)
            putString("playlist_suggestions", value.playlistSuggestionSource.name)
        }
        state.value = value
    }

    fun systemLocale(): Locale = appContext?.let(DeviceLocale::read) ?: Locale.getDefault()

    fun catalogKey(): String = preferences.value.catalogKey(systemLocale())

}

fun YouTubeContentPreferences.visiblePlaylistSongs(songs: List<SongItem>): List<SongItem> {
    if (!hideExplicit && !hideMusicVideos) return songs
    return songs.filterNot { song ->
        hides(song.youtubeContentLabel) && (song.channelId == "youtubeMusic" || isYouTubeMusicSong(song))
    }
}
