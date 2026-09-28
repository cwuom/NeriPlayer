package moe.ouom.neriplayer.core.player.service.notification

import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.artwork.resolveRemoteMetadataArtworkUri
import moe.ouom.neriplayer.core.player.service.presentation.serviceMetadataSnapshot
import moe.ouom.neriplayer.core.player.service.presentation.serviceMetadataText
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.timer.SleepTimerMode
import moe.ouom.neriplayer.core.player.timer.SleepTimerState
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationWidgetPresentationTest {
    @Test
    fun `timer text preserves artist and each stop mode`() {
        val song = song()
        val localized: (Int, String?) -> String = { id, remaining -> "$id:$remaining" }
        assertEquals("Artist", serviceNotificationText(song, SleepTimerState(), "", localized))
        assertEquals(
            "Artist | ${R.string.notification_timer_remaining}:1:00",
            serviceNotificationText(song, SleepTimerState(true, SleepTimerMode.COUNTDOWN), "1:00", localized),
        )
        assertEquals(
            "Artist | ${R.string.notification_timer_finish_current_remaining}:1:00",
            serviceNotificationText(song, SleepTimerState(true, SleepTimerMode.COUNTDOWN_FINISH_CURRENT), "1:00", localized),
        )
        assertEquals(
            "Artist | ${R.string.notification_stop_after_current}:null",
            serviceNotificationText(song, SleepTimerState(true, SleepTimerMode.FINISH_CURRENT), "", localized),
        )
        assertEquals(
            "${R.string.notification_stop_after_playlist}:null",
            serviceNotificationText(null, SleepTimerState(true, SleepTimerMode.FINISH_PLAYLIST), "", localized),
        )
    }

    @Test
    fun `notification snapshot uses current artwork readiness and source`() {
        val artwork = artwork("content://covers/current.jpg", ready = true)
        val snapshot = serviceNotificationSnapshot(
            song = song(),
            text = "Artist",
            transportActive = true,
            playbackControlPlaying = true,
            audioRouteMuted = false,
            isFavorite = true,
            interactiveFavorite = false,
            artwork = artwork,
            lyricState = StatusBarLyricNotificationState(false, null),
            floatingLyricsEnabled = false,
        )
        assertEquals("Song", snapshot.title)
        assertEquals("content://covers/current.jpg", snapshot.coverSource)
        assertTrue(snapshot.largeIconReady)
        assertTrue(snapshot.isFavorite)
        assertEquals("NeriPlayer", serviceNotificationTitle(null))
        assertNull(serviceNotificationSnapshot(
            song = null,
            text = "",
            transportActive = false,
            playbackControlPlaying = false,
            audioRouteMuted = false,
            isFavorite = false,
            interactiveFavorite = false,
            artwork = artwork(null),
            lyricState = StatusBarLyricNotificationState(false, null),
            floatingLyricsEnabled = false,
        ).songKey)
    }

    @Test
    fun `widget projection chooses room position and artwork pending for fallback song`() {
        val input = widgetInputs(song = song(), artwork = artwork(null, pending = true))
        val state = servicePlaybackWidgetState(input)
        assertEquals(8_000L, state.positionMs)
        assertTrue(state.isPlaying)
        assertTrue(state.artworkPending)
        assertFalse(state.artworkReady)
        assertEquals("Song", state.title)
        assertEquals("Artist", state.subtitle)
        assertEquals("Playing", state.status)
        assertTrue(shouldUpdateServicePlaybackWidget(false, null, state))
        assertFalse(shouldUpdateServicePlaybackWidget(false, state, state))
        assertTrue(shouldUpdateServicePlaybackWidget(true, state, state))
    }

    @Test
    fun `widget projection uses idle labels without a song`() {
        val state = servicePlaybackWidgetState(widgetInputs(song = null, artwork = artwork(null)))
        assertEquals("NeriPlayer", state.title)
        assertEquals("Idle", state.subtitle)
        assertEquals("Ready", state.status)
        assertFalse(state.hasSong)
        assertFalse(state.artworkPending)
    }

    @Test
    fun `widget status prioritizes buffering and pauses an inactive song`() {
        val song = song()
        val paused = servicePlaybackWidgetState(widgetInputs(song, artwork(null)).copyFor(
            playerSongPresent = true,
            roomPlaying = false,
        ))
        assertEquals("Paused", paused.status)
        assertEquals(2_000L, paused.positionMs)

        val buffering = servicePlaybackWidgetState(widgetInputs(song, artwork(null)).copyFor(
            buffering = true,
        ))
        assertEquals("Buffering", buffering.status)
    }

    @Test
    fun `control action priority gives mute precedence over playing`() {
        val muted = playbackControlActionChoice(true, true)
        assertEquals(R.drawable.round_volume_up_24, muted.iconRes)
        assertEquals(2, muted.intentIndex)
        assertEquals(R.drawable.round_pause_24, playbackControlActionChoice(false, true).iconRes)
        assertEquals(R.drawable.round_play_arrow_24, playbackControlActionChoice(false, false).iconRes)
        assertEquals(R.drawable.ic_baseline_favorite_24, favoriteActionIcon(true))
        assertEquals(R.string.favorite_add, favoriteActionTitle(false))
        assertEquals(R.drawable.ic_lyrics_off_24, floatingLyricsActionIcon(true))
        assertEquals(R.string.notification_show_floating_lyrics, floatingLyricsActionTitle(false))
    }

    @Test
    fun `metadata projection keeps remote URI separate from retained bitmap state`() {
        val song = song()
        val text = serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false)
        val snapshot = serviceMetadataSnapshot(song, text, artwork("content://covers/local.jpg", ready = true))
        assertEquals("Song", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("content://covers/local.jpg", snapshot.coverSource)
        assertTrue(snapshot.largeIconReady)
        assertNull(resolveRemoteMetadataArtworkUri(snapshot.coverSource))
        assertEquals(0L, serviceMetadataSnapshot(null, text, artwork(null)).durationMs)
        val lyricText = serviceMetadataText(
            song,
            ExternalBluetoothLyricPayload(lyric = "lyric line"),
            audioDeviceType = null,
            forceSendLyrics = true,
        )
        assertEquals("lyric line", lyricText.title)
    }

    private fun artwork(
        source: String?,
        ready: Boolean = false,
        pending: Boolean = false,
    ) = PlaybackArtworkSnapshot(source, null, null, ready, ready, pending)

    private fun widgetInputs(song: SongItem?, artwork: PlaybackArtworkSnapshot) = ServiceWidgetInputs(
        song = song,
        playerSongPresent = false,
        playerPositionMs = 2_000L,
        roomPositionMs = 8_000L,
        buffering = false,
        transportActive = false,
        roomPlaying = song != null,
        favorite = false,
        canToggleFavorite = false,
        floatingLyricsEnabled = false,
        artwork = artwork,
        labels = ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready"),
    )

    private fun ServiceWidgetInputs.copyFor(
        playerSongPresent: Boolean = this.playerSongPresent,
        roomPlaying: Boolean = this.roomPlaying,
        buffering: Boolean = this.buffering,
    ) = ServiceWidgetInputs(
        song = song,
        playerSongPresent = playerSongPresent,
        playerPositionMs = playerPositionMs,
        roomPositionMs = roomPositionMs,
        buffering = buffering,
        transportActive = transportActive,
        roomPlaying = roomPlaying,
        favorite = favorite,
        canToggleFavorite = canToggleFavorite,
        floatingLyricsEnabled = floatingLyricsEnabled,
        artwork = artwork,
        labels = labels,
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
}
