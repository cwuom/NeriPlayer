package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.notification.ServiceWidgetLabels
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

@RunWith(AndroidJUnit4::class)
class PlaybackServiceMatchedFavoritesTest {
    private val remote = SongItem(
        id = 42L, name = "Song", artist = "Artist", album = "NeteaseAlbum", albumId = 7L,
        durationMs = 180_000L, coverUrl = null, channelId = "netease", audioId = "42"
    )
    private val local = remote.copy(
        id = 99L, album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, albumId = 0L,
        channelId = "local", audioId = "99", mediaUri = "/music/song.mp3",
        localFilePath = "/music/song.mp3", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42"
    )
    private var previousPlaylists: List<LocalPlaylist> = emptyList()
    private var previousReady = false
    private val androidSource = AndroidPlaybackServicePresentationSource(
        ApplicationProvider.getApplicationContext<Application>()
    )

    @Before
    fun savePlaylistState() {
        previousPlaylists = PlayerManager._playlistsFlow.value
        previousReady = PlayerManager._localPlaylistsReadyFlow.value
        PlayerManager._playlistsFlow.value = emptyList()
        PlayerManager._localPlaylistsReadyFlow.value = true
    }

    @After
    fun restorePlaylistState() {
        PlayerManager._playlistsFlow.value = previousPlaylists
        PlayerManager._localPlaylistsReadyFlow.value = previousReady
    }

    @Test
    fun `remote favorites update matched local notification and widget on addition and removal`() = runTest {
        val fixture = fixture(local, backgroundScope)
        assertFalse(fixture.owner.refreshFavoriteSongKeys())
        favorites(remote)
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
        assertFalse(fixture.owner.refreshFavoriteSongKeys())
        fixture.publish()
        assertTrue(fixture.notification().favorite)
        assertFalse(fixture.notification().interactiveFavorite)
        assertTrue(fixture.widget().isFavorite)
        assertTrue(fixture.widget().canToggleFavorite)
        assertSame(local, fixture.notification().song)

        favorites()
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
        fixture.publish()
        assertFalse(fixture.notification().favorite)
        assertFalse(fixture.notification().interactiveFavorite)
        assertFalse(fixture.widget().isFavorite)
        assertTrue(fixture.widget().canToggleFavorite)
        assertEquals(listOf(true, false), fixture.widgetStates().map { it.isFavorite })
    }

    @Test
    fun `syncable matched favorites can be added externally while unmatched files still require confirmation`() = runTest {
        favorites()
        val matched = fixture(local, backgroundScope)
        matched.owner.refreshFavoriteSongKeys()
        matched.publish()
        assertFalse(matched.notification().favorite)
        assertFalse(matched.notification().interactiveFavorite)
        assertTrue(matched.widget().canToggleFavorite)
        assertTrue(matched.owner.canToggleFavorite(local))

        listOf(
            local.copy(matchedSongId = null),
            local.copy(matchedSongId = "invalid"),
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)
        ).forEach { song ->
            val unmatched = fixture(song, backgroundScope)
            unmatched.owner.refreshFavoriteSongKeys()
            unmatched.publish()
            assertTrue(unmatched.notification().interactiveFavorite)
            assertFalse(unmatched.widget().canToggleFavorite)
            assertFalse(unmatched.owner.canToggleFavorite(song))
        }
    }

    @Test
    fun `local favorites expose their matched remote key without replacing the file identity`() = runTest {
        favorites(local)
        val localKey = local.stableKey()
        assertEquals(setOf(localKey, remote.stableKey()), androidSource.favoriteSongKeys())
        assertSame(local, PlayerManager.playlistsFlow.value.single().songs.single())
        assertEquals(localKey, local.stableKey())
        val fixture = fixture(remote, backgroundScope)
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
        fixture.publish()
        assertTrue(fixture.notification().favorite)
        assertTrue(fixture.widget().isFavorite)

        PlayerManager._playlistsFlow.value = listOf(
            LocalPlaylist(id = 10L, name = "Mix", songs = mutableListOf(local))
        )
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
        fixture.publish()
        assertFalse(fixture.notification().favorite)
        assertFalse(fixture.widget().isFavorite)
    }

    @Test
    fun `provider conflicts and invalid matches never borrow a netease favorite`() = runTest {
        val conflicting = listOf(
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            local.copy(matchedLyricSource = null),
            local.copy(matchedSongId = "0"),
            local.copy(matchedSongId = "invalid"),
            local.copy(sourceStableKey = "77|netease|"),
            local.copy(sourceStableKey = "77|bilibili|"),
            remote.copy(channelId = "bilibili", audioId = "BV42",
                matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
        )
        for (candidate in conflicting) {
            favorites(remote)
            val localFixture = fixture(candidate, backgroundScope)
            assertFalse(candidate.toString(), localFixture.owner.refreshFavoriteSongKeys())
            localFixture.publish()
            assertFalse(candidate.toString(), localFixture.notification().favorite)
            assertFalse(candidate.toString(), localFixture.widget().isFavorite)

            favorites(candidate)
            assertFalse(candidate.toString(), remote.stableKey() in androidSource.favoriteSongKeys())
            val remoteFixture = fixture(remote, backgroundScope)
            assertFalse(candidate.toString(), remoteFixture.owner.refreshFavoriteSongKeys())
            remoteFixture.publish()
            assertFalse(candidate.toString(), remoteFixture.notification().favorite)
        }
    }

    @Test
    fun `playlist readiness and unrelated changes retain current favorite refresh semantics`() = runTest {
        favorites(remote)
        PlayerManager._localPlaylistsReadyFlow.value = false
        val fixture = fixture(local, backgroundScope)
        assertTrue(androidSource.favoriteSongKeys().isEmpty())
        assertFalse(fixture.owner.refreshFavoriteSongKeys())

        PlayerManager._localPlaylistsReadyFlow.value = true
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
        favorites(remote, remote.copy(id = 43L, audioId = "43"))
        assertFalse(fixture.owner.refreshFavoriteSongKeys())
        favorites(local)
        assertFalse(fixture.owner.refreshFavoriteSongKeys())
        fixture.owner.resetFavoriteSongKeys()
        assertTrue(fixture.owner.refreshFavoriteSongKeys())
    }

    private fun favorites(vararg songs: SongItem) {
        PlayerManager._playlistsFlow.value = listOf(
            LocalPlaylist(id = FavoritesPlaylist.SYSTEM_ID, name = "Favorites", songs = songs.toMutableList())
        )
    }

    private fun fixture(current: SongItem, scope: CoroutineScope): Fixture {
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(
            PlaybackArtworkSnapshot(null, null, null, false, false, false)
        )
        val source = object : PlaybackServicePresentationSource {
            override fun playback() = PlaybackServicePlaybackSnapshot(
                song = current, playerSongPresent = true, playerPositionMs = 0L, roomPositionMs = 0L,
                buffering = false, transportActive = false, enginePlaying = false, roomPlaying = false,
                playbackControlPlaying = false, audioRouteMuted = false, playbackSpeed = 1f
            )
            override fun metadata() = PlaybackServiceMetadataInputs(ExternalBluetoothLyricPayload(), null, false)
            override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
            override fun favoriteSongKeys() = androidSource.favoriteSongKeys()
            override fun localPlaylistsReady() = androidSource.localPlaylistsReady()
            override fun isLocalSong(song: SongItem) = LocalSongSupport.isLocalSong(song, null)
            override fun shareUrl(song: SongItem): String? = null
            override suspend fun setFloatingLyricsEnabled(enabled: Boolean) = Unit
        }
        return Fixture(PlaybackServicePresentationOwner(source, port, artwork, scope), port)
    }

    private class Fixture(
        val owner: PlaybackServicePresentationOwner,
        private val port: PlaybackServicePresentationPort
    ) {
        fun publish() = owner.updateNotification(
            force = false, foregroundStarted = true,
            lyricState = StatusBarLyricNotificationState(false, null), floatingLyricsEnabled = false
        )

        fun notification(): PlaybackServiceNotificationRenderInputs = mockingDetails(port).invocations
            .last { it.method.name == "publishNotification" }.arguments[0] as PlaybackServiceNotificationRenderInputs

        fun widgetStates(): List<PlaybackWidgetState> = mockingDetails(port).invocations
            .filter { it.method.name == "publishWidget" }.map { it.arguments[0] as PlaybackWidgetState }

        fun widget(): PlaybackWidgetState = widgetStates().last()
    }
}
