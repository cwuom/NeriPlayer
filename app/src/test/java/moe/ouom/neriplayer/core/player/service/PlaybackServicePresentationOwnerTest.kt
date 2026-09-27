package moe.ouom.neriplayer.core.player.service

import android.content.Intent
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.timer.SleepTimerState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServicePresentationOwnerTest {
    @Test
    fun `playback state projection prioritizes buffering and room fallback`() {
        val idle = playback(null)
        val fallback = playback(song()).copy(playerSongPresent = false, roomPlaying = true)
        assertEquals(PlaybackState.STATE_PAUSED, resolveServicePlaybackState(idle))
        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(fallback))
        assertEquals(PlaybackState.STATE_PLAYING, resolveServicePlaybackState(fallback.copy(transportActive = true)))
        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(fallback.copy(buffering = true)))
        assertEquals(8_000L, servicePlaybackPositionMs(fallback))
        assertEquals(2_000L, servicePlaybackPositionMs(fallback.copy(playerSongPresent = true)))
        assertEquals(2_000L, servicePlaybackPositionMs(idle))
        assertEquals(1.25f, servicePlaybackSpeed(PlaybackState.STATE_PLAYING, fallback))
        assertEquals(0.0f, servicePlaybackSpeed(PlaybackState.STATE_PAUSED, fallback))
        assertEquals(0, serviceFavoriteControlFingerprint(false, true))
        assertEquals(1, serviceFavoriteControlFingerprint(true, false))
        assertEquals(2, serviceFavoriteControlFingerprint(true, true))
    }

    @Test
    fun `external notification inputs reject unrelated media buttons and private share links`() {
        val current = song()
        assertNull(mediaButtonKeyEvent(null))
        val unrelated = mock(Intent::class.java)
        `when`(unrelated.action).thenReturn(Intent.ACTION_VIEW)
        assertNull(mediaButtonKeyEvent(unrelated))
        assertNull(resolveXiaomiIslandShare(null, "https://music.163.com/song?id=1"))
        assertNull(resolveXiaomiIslandShare(current, null))
        assertNull(resolveXiaomiIslandShare(current, "https://example.com/song"))
        assertEquals(
            current to "https://music.163.com/song?id=1",
            resolveXiaomiIslandShare(current, "https://music.163.com/song?id=1"),
        )
    }

    @Test
    fun `favorite refresh owns previous keys and reports only current song changes`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val owner = owner(source, backgroundScope)
        assertFalse(owner.refreshFavoriteSongKeys())
        source.favorites = setOf("another song")
        assertFalse(owner.refreshFavoriteSongKeys())
        source.favorites = setOf(current.stableKey())
        assertTrue(owner.refreshFavoriteSongKeys())
        assertFalse(owner.refreshFavoriteSongKeys())
        owner.resetFavoriteSongKeys()
        assertTrue(owner.refreshFavoriteSongKeys())
        source.favorites = emptySet()
        assertTrue(owner.refreshFavoriteSongKeys())
    }

    @Test
    fun `favorite controls require a ready playlist and noninteractive song`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val owner = owner(source, backgroundScope)

        assertFalse(owner.canToggleFavorite(null))
        assertTrue(owner.canToggleFavorite(current))
        source.localSong = true
        assertFalse(owner.canToggleFavorite(current))
        source.localSong = false
        source.playlistsReady = false
        assertFalse(owner.canToggleFavorite(current))
    }

    @Test
    fun `foreground notification publishes only a changed snapshot or forced refresh`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        val initialArtwork = artwork(null)
        val changedArtwork = artwork("content://covers/next.jpg")
        `when`(artwork.snapshotFor(current)).thenReturn(initialArtwork, initialArtwork, changedArtwork, changedArtwork)
        val owner = owner(source, backgroundScope, port, artwork)
        val lyricState = StatusBarLyricNotificationState(false, null)

        owner.updateNotification(false, false, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(true, true, lyricState, false)

        assertEquals(3, callCount(port, "publishNotification"))
        assertEquals(3, source.shareUrlRequests)
    }

    @Test
    fun `widget projection keeps its last state and respects force`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)

        owner.updateWidget(false, false)
        owner.updateWidget(false, false)
        owner.updateWidget(true, false)

        assertEquals(2, callCount(port, "publishWidget"))
    }

    @Test
    fun `widget progress switches between full and partial updates`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)

        owner.updateWidgetProgress(false)
        owner.updateWidgetProgress(false)
        source.currentPlayback = source.currentPlayback.copy(transportActive = true)
        owner.updateWidgetProgress(false)
        owner.updateWidgetProgress(false)

        assertEquals(2, callCount(port, "publishWidget"))
        assertEquals(1, callCount(port, "publishWidgetProgress"))
    }

    @Test
    fun `floating lyrics action persists the selected state in the owner scope`() = runTest {
        val source = FakeSource(playback(null))
        val owner = owner(source, backgroundScope)

        owner.applyFloatingLyricsExternalAction(currentEnabled = false, legacyHideAction = false)
        owner.applyFloatingLyricsExternalAction(currentEnabled = true, legacyHideAction = true)
        runCurrent()

        assertEquals(listOf(true, false), source.floatingLyricsWrites)
    }

    private fun owner(
        source: FakeSource,
        scope: CoroutineScope,
        port: PlaybackServicePresentationPort = mock(PlaybackServicePresentationPort::class.java),
        artwork: PlaybackArtworkOwner = mock(PlaybackArtworkOwner::class.java),
    ) = PlaybackServicePresentationOwner(source, port, artwork, scope)

    private fun playback(song: SongItem?) = PlaybackServicePlaybackSnapshot(
        song = song,
        playerSongPresent = song != null,
        playerPositionMs = 2_000L,
        roomPositionMs = 8_000L,
        buffering = false,
        transportActive = false,
        roomPlaying = false,
        playbackControlPlaying = false,
        audioRouteMuted = false,
        playbackSpeed = 1.25f,
    )

    private fun song() = SongItem(
        id = 1,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        durationMs = 10_000,
        coverUrl = null,
    )

    private fun artwork(source: String?) = PlaybackArtworkSnapshot(source, null, null, false, false, false)

    private fun callCount(port: PlaybackServicePresentationPort, method: String): Int =
        mockingDetails(port).invocations.count { it.method.name == method }

    private class FakeSource(var currentPlayback: PlaybackServicePlaybackSnapshot) : PlaybackServicePresentationSource {
        var favorites: Set<String> = emptySet()
        var playlistsReady = true
        var localSong = false
        val floatingLyricsWrites = mutableListOf<Boolean>()
        var shareUrlRequests = 0

        override fun playback() = currentPlayback
        override fun metadata() = PlaybackServiceMetadataInputs(ExternalBluetoothLyricPayload(), null, false)
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = favorites
        override fun localPlaylistsReady() = playlistsReady
        override fun isLocalSong(song: SongItem) = localSong
        override fun shareUrl(song: SongItem): String? {
            shareUrlRequests++
            return null
        }
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) {
            floatingLyricsWrites += enabled
        }
    }
}
