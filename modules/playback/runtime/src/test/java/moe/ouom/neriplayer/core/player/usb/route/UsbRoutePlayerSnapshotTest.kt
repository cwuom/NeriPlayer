package moe.ouom.neriplayer.core.player.usb.route

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class UsbRoutePlayerSnapshotTest {
    private val live = mirroredUsbRoutePlayerSnapshot("song", 1_000L, true) { Triple(true, true, Player.STATE_READY) }
    private val mirrored = mirroredUsbRoutePlayerSnapshot("song", 2_000L, false) { Triple(true, true, Player.STATE_READY) }

    @Test
    fun `uninitialized player never reads player state`() {
        var touched = false
        val snapshot = selectUsbRoutePlayerSnapshot(
            initialized = false,
            onPlayerThread = { touched = true; true },
            live = { touched = true; live },
            mirrored = { touched = true; mirrored }
        )

        assertSame(UsbRoutePlayerSnapshot.Uninitialized, snapshot)
        assertFalse(touched)
    }

    @Test
    fun `player thread reads the live player`() {
        assertSame(live, selectUsbRoutePlayerSnapshot(true, { true }, { live }, { mirrored }))
    }

    @Test
    fun `audio sink thread uses mirrored state instead of the player`() {
        val snapshot = selectUsbRoutePlayerSnapshot(
            initialized = true,
            onPlayerThread = { false },
            live = { error("ExoPlayer must not be read off its application thread") },
            mirrored = { mirrored }
        )

        assertSame(mirrored, snapshot)
    }

    @Test
    fun `mirrored state describes the single current item`() {
        val withSong = mirroredUsbRoutePlayerSnapshot("song", 42L, true) { Triple(true, false, Player.STATE_BUFFERING) }
        assertEquals(1, withSong.mediaItemCount)
        assertEquals(0, withSong.mediaItemIndex)
        assertEquals(42L, withSong.positionMs)
        assertEquals(true, withSong.playWhenReady)
        assertEquals(Player.STATE_BUFFERING, withSong.playbackState)

        val empty = mirroredUsbRoutePlayerSnapshot(null, 0L, false) { error("transport not requested") }
        assertFalse(empty.hasMediaItem)
        assertEquals(0, empty.mediaItemCount)
        assertFalse(empty.playWhenReady)
        assertEquals(Player.STATE_IDLE, empty.playbackState)
    }
}
