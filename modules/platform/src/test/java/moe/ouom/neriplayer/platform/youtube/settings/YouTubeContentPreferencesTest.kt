package moe.ouom.neriplayer.platform.youtube.settings

import java.util.Locale
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeContentPreferencesTest {
    private val prefs = YouTubeContentPreferences()
    private val mainland = Locale.forLanguageTag("en-CN")

    @Test
    fun savedPlaylistFiltersPreserveOrderOriginalSongsAndLegacyEntries() {
        val legacy = SongItem(1L, "legacy", "artist", "album", 0L, 0L, null, channelId = "youtubeMusic")
        val explicit = legacy.copy(id = 2L, youtubeContentLabel = YouTubeContentLabel.EXPLICIT)
        val domestic = explicit.copy(id = 3L, channelId = "netease")
        val video = legacy.copy(id = 4L, youtubeContentLabel = YouTubeContentLabel.MUSIC_VIDEO)
        val both = legacy.copy(id = 5L, youtubeContentLabel = YouTubeContentLabel.EXPLICIT_MUSIC_VIDEO)
        val saved = listOf(explicit, legacy, video, domestic, both)
        assertEquals(listOf(legacy, video, domestic), prefs.copy(hideExplicit = true).visiblePlaylistSongs(saved))
        assertEquals(listOf(explicit, legacy, domestic), prefs.copy(hideMusicVideos = true).visiblePlaylistSongs(saved))
        assertEquals(listOf(legacy, domestic), prefs.copy(hideExplicit = true, hideMusicVideos = true).visiblePlaylistSongs(saved))
        assertEquals(listOf(explicit, legacy, video, domestic, both), prefs.visiblePlaylistSongs(saved))
    }

    @Test
    fun mainlandDefaultsDependOnSystemCountryEvenForAnEnglishDevice() {
        assertEquals(YouTubeMusicRequestLocale("zh-CN", "JP"), prefs.resolveLocale(mainland))
        assertEquals(YouTubeMusicRequestLocale("zh-TW", "TW"), prefs.resolveLocale(Locale.TAIWAN))
        assertEquals(YouTubeMusicRequestLocale("en-US", "US"), prefs.resolveLocale(Locale.US))
        assertEquals(YouTubeMusicRequestLocale("sv", "SE"), prefs.resolveLocale(Locale.forLanguageTag("sv-SE")))
        assertEquals(YouTubeMusicRequestLocale("en", "US"), prefs.resolveLocale(Locale.ROOT))
        assertEquals(YouTubeMusicRequestLocale("fr", "US"), prefs.resolveLocale(Locale.FRENCH))
        assertEquals(YouTubeMusicRequestLocale("en", "US"), prefs.resolveLocale(Locale.forLanguageTag("xx-XX")))
    }

    @Test
    fun usersCanOverrideEitherFieldIndependently() {
        assertEquals(YouTubeMusicRequestLocale("ja", "JP"), prefs.copy(languageTag = "ja").resolveLocale(mainland))
        assertEquals(YouTubeMusicRequestLocale("zh-CN", "CA"), prefs.copy(countryCode = "CA").resolveLocale(mainland))
        assertEquals("zh-CN,zh;q=0.9", prefs.resolveLocale(mainland).acceptLanguage)
    }

    @Test
    fun catalogKeysIncludeResolvedLocaleAndFiltersButNotPlaylistSuggestionQueries() {
        val base = prefs.catalogKey(mainland)
        assertTrue(prefs.acceptsLegacyCache)
        listOf(prefs.copy(hideExplicit = true), prefs.copy(hideMusicVideos = true),
            prefs.copy(languageTag = "fr"), prefs.copy(countryCode = "GB")).forEach {
            assertFalse(it.acceptsLegacyCache)
            assertNotEquals(base, it.catalogKey(mainland))
        }
        assertEquals(base, prefs.copy(playlistSuggestionSource = PlaylistSuggestionSource.Tracks).catalogKey(mainland))
        assertNotEquals(base, prefs.catalogKey(Locale.FRANCE))
    }
}
