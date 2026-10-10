package moe.ouom.neriplayer.ui.screen.tab.library

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LibraryLocalSearchMatchingTest {

    @Test
    fun `blank query matches everything without inspecting values`() {
        assertTrue(queryMatches("   ", null))
        assertTrue(queryMatches("", "anything"))
    }

    @Test
    fun `query is trimmed and matched case-insensitively against plain values`() {
        assertTrue(queryMatches("  night  ", "Midnight City"))
        assertTrue(queryMatches("42", 4242L))
        assertFalse(queryMatches("dawn", "Midnight City", 7))
    }

    @Test
    fun `null values never match a non-blank query`() {
        assertFalse(queryMatches("null", null, null))
    }

    @Test
    fun `iterable values match when any non-null item contains the query`() {
        assertTrue(queryMatches("music", listOf(null, "YouTube Music")))
        assertFalse(queryMatches("music", listOf(null, "Bilibili")))
        assertFalse(queryMatches("music", emptyList<String>()))
    }

    @Test
    fun `local artist matches by its own name before checking songs`() {
        val artist = LocalArtistSummary(name = "Aimer", songs = emptyList())

        assertTrue(artist.matchesLocalArtistSearch("aim"))
        assertFalse(artist.matchesLocalArtistSearch("yoasobi"))
    }

    @Test
    fun `local artist matches through song title artist album or file name`() {
        val artist = LocalArtistSummary(
            name = "Various",
            songs = listOf(
                song(name = "Kataomoi", artist = "Aimer", album = "Daydream"),
                song(name = "Track", artist = "Unknown", album = "", localFileName = "brave-shine.flac")
            )
        )

        assertTrue(artist.matchesLocalArtistSearch("kataomoi"))
        assertTrue(artist.matchesLocalArtistSearch("daydream"))
        assertTrue(artist.matchesLocalArtistSearch("BRAVE"))
        assertFalse(artist.matchesLocalArtistSearch("lemon"))
    }

    @Test
    fun `local artist search prefers custom song metadata over the original tags`() {
        val artist = LocalArtistSummary(
            name = "Various",
            songs = listOf(
                song(name = "Original", artist = "Original Artist").copy(
                    customName = "Renamed Title",
                    customArtist = "Renamed Artist"
                )
            )
        )

        assertTrue(artist.matchesLocalArtistSearch("renamed title"))
        assertTrue(artist.matchesLocalArtistSearch("renamed artist"))
        assertFalse(artist.matchesLocalArtistSearch("original"))
    }

    @Test
    fun `blank playlist query keeps every playlist visible`() {
        val playlist = LocalPlaylist(id = 3L, name = "Road trip")

        assertTrue(playlist.matchesLocalPlaylistSearch(" ", mock(Context::class.java)))
    }

    @Test
    fun `user playlist matches by id name size or song metadata`() {
        val playlist = LocalPlaylist(
            id = 77L,
            name = "Road trip",
            songs = mutableListOf(song(name = "Highway Star", artist = "Deep Purple"))
        )
        val context = mock(Context::class.java)

        assertTrue(playlist.matchesLocalPlaylistSearch("road", context))
        assertTrue(playlist.matchesLocalPlaylistSearch("77", context))
        assertTrue(playlist.matchesLocalPlaylistSearch("purple", context))
        assertFalse(playlist.matchesLocalPlaylistSearch("jazz", context))
    }

    @Test
    fun `system favorites playlist matches its localized display name`() {
        val favorites = LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "My Favorite Music"
        )
        val context = localizedContext()

        assertTrue(favorites.matchesLocalPlaylistSearch("喜欢", context))
        assertTrue(favorites.matchesLocalPlaylistSearch("favorite", context))
        assertFalse(favorites.matchesLocalPlaylistSearch("本地", context))
    }

    @Test
    fun `favorite playlist matches remote identifiers and subtitle`() {
        val favorite = favorite(
            source = "youtubeMusic",
            browseId = "VLPL-browse-1",
            playlistId = "PL-remote-9",
            subtitle = "Weekly mix"
        )

        assertTrue(favorite.matchesFavoriteSearch("browse-1"))
        assertTrue(favorite.matchesFavoriteSearch("remote-9"))
        assertTrue(favorite.matchesFavoriteSearch("weekly"))
        assertTrue(favorite.matchesFavoriteSearch("12"))
    }

    @Test
    fun `favorite playlist matches the localized aliases of its source`() {
        assertTrue(favorite(source = "netease").matchesFavoriteSearch("网易云"))
        assertTrue(favorite(source = "bili").matchesFavoriteSearch("b站"))
        assertTrue(favorite(source = "youtubeMusic").matchesFavoriteSearch("youtube music"))
        assertFalse(favorite(source = "netease").matchesFavoriteSearch("bilibili"))
    }

    @Test
    fun `favorite playlist falls back to song metadata`() {
        val favorite = favorite(
            source = "netease",
            songs = listOf(song(name = "Unravel", artist = "TK", album = "Fantastic Magic"))
        )

        assertTrue(favorite.matchesFavoriteSearch("unravel"))
        assertTrue(favorite.matchesFavoriteSearch("magic"))
        assertFalse(favorite.matchesFavoriteSearch("lemon"))
    }

    @Test
    fun `favorite filter keeps every favorite for a blank query and filters otherwise`() {
        val netease = favorite(source = "netease", name = "Rainy days")
        val bili = favorite(source = "bili", name = "Gaming BGM")

        assertEquals(listOf(netease, bili), filterFavoritePlaylists(listOf(netease, bili), " "))
        assertEquals(listOf(bili), filterFavoritePlaylists(listOf(netease, bili), "bgm"))
    }

    private fun favorite(
        source: String,
        name: String = "Favorite list",
        browseId: String? = null,
        playlistId: String? = null,
        subtitle: String? = null,
        songs: List<SongItem> = emptyList()
    ) = FavoritePlaylist(
        id = 9001L,
        name = name,
        coverUrl = null,
        trackCount = 12,
        source = source,
        browseId = browseId,
        playlistId = playlistId,
        subtitle = subtitle,
        songs = songs,
        addedTime = 1L
    )

    private fun song(
        name: String,
        artist: String,
        album: String = "Album",
        localFileName: String? = null
    ) = SongItem(
        id = name.hashCode().toLong(),
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        localFileName = localFileName
    )

    private fun localizedContext(): Context {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)

        `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(context.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        `when`(context.createConfigurationContext(any(Configuration::class.java))).thenReturn(context)
        `when`(context.getString(CoreCommonR.string.favorite_my_music)).thenReturn("我喜欢的音乐")
        return context
    }
}
