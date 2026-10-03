package moe.ouom.neriplayer.ui.screen.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderHeader
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

class CreatorFollowButtonTest {
    @Test
    fun `bili follow uses mid and preserves profile metadata`() {
        val favorite = createBiliUploaderFavorite(
            uploader = BiliUploaderSummary(42L, "Summary", "summary-cover"),
            header = BiliUploaderHeader(42L, "Uploader", "avatar", "Profile", "banner")
        )

        assertEquals(42L, favorite.id)
        assertEquals(FAVORITE_SOURCE_BILI_ARTIST, favorite.source)
        assertEquals("Uploader", favorite.name)
        assertEquals("avatar", favorite.coverUrl)
        assertEquals("Profile", favorite.subtitle)
        assertTrue(favorite.songs.isEmpty())
    }

    @Test
    fun `bili follow ignores a previous uploader header`() {
        val favorite = createBiliUploaderFavorite(
            uploader = BiliUploaderSummary(42L, "Current", "current-cover"),
            header = BiliUploaderHeader(41L, "Previous", "previous-cover", "Previous", "")
        )

        assertEquals("Current", favorite.name)
        assertEquals("current-cover", favorite.coverUrl)
        assertNull(favorite.subtitle)
    }

    @Test
    fun `youtube follow keeps the real browse id and enriched metadata`() {
        val favorite = createYouTubeMusicCreatorFavorite(
            creator = creator(),
            header = YouTubeMusicCreatorHeader("UCcreator", "Artist", "Channel", "avatar")
        )

        assertEquals(stableYouTubeMusicId("UCcreator"), favorite.id)
        assertEquals("UCcreator", favorite.browseId)
        assertEquals(FAVORITE_SOURCE_YOUTUBE_ARTIST, favorite.source)
        assertEquals("Artist", favorite.name)
        assertEquals("Channel", favorite.subtitle)
        assertEquals("avatar", favorite.coverUrl)
        assertTrue(favorite.songs.isEmpty())
    }

    @Test
    fun `youtube follow retains summary metadata when a loaded value is blank`() {
        val favorite = createYouTubeMusicCreatorFavorite(
            creator = creator(),
            header = YouTubeMusicCreatorHeader("UCcreator", "", "", "")
        )

        assertEquals("Creator", favorite.name)
        assertEquals("Artist channel", favorite.subtitle)
        assertEquals("summary-cover", favorite.coverUrl)
    }

    @Test
    fun `youtube follow ignores a previous creator header`() {
        val favorite = createYouTubeMusicCreatorFavorite(
            creator = creator(),
            header = YouTubeMusicCreatorHeader("UCprevious", "Previous", "Previous", "previous-cover")
        )

        assertEquals("UCcreator", favorite.browseId)
        assertEquals("Creator", favorite.name)
        assertEquals("Artist channel", favorite.subtitle)
        assertEquals("summary-cover", favorite.coverUrl)
    }

    @Test
    fun `youtube error still resolves a header from the local summary`() {
        val detail = resolveYouTubeMusicCreatorDetail(
            uiState = YouTubeMusicCreatorDetailUiState(loading = false, error = "Offline"),
            creator = creator()
        )!!

        assertEquals("UCcreator", detail.header.browseId)
        assertEquals("Creator", detail.header.title)
        assertEquals("Artist channel", detail.header.subtitle)
        assertEquals("summary-cover", detail.header.coverUrl)
        assertTrue(detail.sections.isEmpty())
    }

    @Test
    fun `toggle reads the loaded state and removes an existing follow`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        var followed = false
        var initializationCalls = 0
        `when`(repository.awaitInitialized()).thenAnswer {
            if (initializationCalls++ == 0) followed = true
            true
        }
        `when`(repository.isFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)).thenAnswer { followed }
        doAnswer {
            followed = false
            Unit
        }.`when`(repository).removeFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)

        toggleCreatorFollow(repository, favorite())

        verify(repository).removeFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)
        verify(repository, times(2)).awaitInitialized()
    }

    @Test
    fun `toggle adds creator metadata without a song snapshot`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        val favorite = createYouTubeMusicCreatorFavorite(creator(), null)
        `when`(repository.awaitInitialized()).thenReturn(true)
        `when`(repository.isFavorite(favorite.id, favorite.source)).thenReturn(false, true)

        toggleCreatorFollow(repository, favorite)

        verify(repository).addFavorite(
            id = favorite.id,
            name = "Creator",
            coverUrl = "summary-cover",
            trackCount = 0,
            source = FAVORITE_SOURCE_YOUTUBE_ARTIST,
            browseId = "UCcreator",
            playlistId = null,
            subtitle = "Artist channel",
            songs = emptyList()
        )
    }

    @Test
    fun `initialization failure prevents a follow mutation`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        `when`(repository.awaitInitialized()).thenReturn(false)

        val error = runCatching { toggleCreatorFollow(repository, favorite()) }.exceptionOrNull()

        assertTrue(error is IOException)
        verify(repository, never()).isFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)
        verify(repository, never()).removeFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)
    }

    @Test
    fun `failed persistence is reported even after the state changes in memory`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        `when`(repository.awaitInitialized()).thenReturn(true, false)
        `when`(repository.isFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)).thenReturn(true, false)

        val error = runCatching { toggleCreatorFollow(repository, favorite()) }.exceptionOrNull()

        assertTrue(error is IOException)
        verify(repository).removeFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)
    }

    @Test
    fun `unchanged follow state cannot be reported as a successful mutation`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        `when`(repository.awaitInitialized()).thenReturn(true)
        `when`(repository.isFavorite(42L, FAVORITE_SOURCE_BILI_ARTIST)).thenReturn(true)

        val error = runCatching { toggleCreatorFollow(repository, favorite()) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun `cancellation remains cancellation`() = runTest {
        val repository = mock(FavoritePlaylistRepository::class.java)
        val cancellation = CancellationException("Cancelled")
        `when`(repository.awaitInitialized()).thenThrow(cancellation)

        val error = runCatching { toggleCreatorFollow(repository, favorite()) }.exceptionOrNull()

        assertSame(cancellation, error)
    }

    private fun creator() = YouTubeMusicCreatorSummary(
        browseId = "UCcreator",
        title = "Creator",
        subtitle = "Artist channel",
        coverUrl = "summary-cover"
    )

    private fun favorite() = FavoritePlaylist(
        id = 42L,
        name = "Uploader",
        coverUrl = "avatar",
        trackCount = 0,
        source = FAVORITE_SOURCE_BILI_ARTIST,
        songs = emptyList()
    )
}
