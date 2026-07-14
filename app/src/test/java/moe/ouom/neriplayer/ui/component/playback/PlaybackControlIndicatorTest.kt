package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlIndicatorTest {

    @Test
    fun `requested playback waits until audio starts`() {
        assertTrue(
            resolvePlaybackWaiting(
                playbackRequested = true,
                isPlaying = false,
                usbPlaybackPreparing = false
            )
        )
    }

    @Test
    fun `active playback uses pause indicator`() {
        assertFalse(
            resolvePlaybackWaiting(
                playbackRequested = true,
                isPlaying = true,
                usbPlaybackPreparing = false
            )
        )
    }

    @Test
    fun `usb preparation keeps playback in waiting state`() {
        assertTrue(
            resolvePlaybackWaiting(
                playbackRequested = true,
                isPlaying = true,
                usbPlaybackPreparing = true
            )
        )
    }

    @Test
    fun `paused playback never shows waiting state`() {
        assertFalse(
            resolvePlaybackWaiting(
                playbackRequested = false,
                isPlaying = false,
                usbPlaybackPreparing = true
            )
        )
    }

    @Test
    fun `waiting playback reports waiting description`() {
        assertEquals(
            "等待中",
            resolvePlaybackControlContentDescription(
                isPlaying = true,
                isPlaybackWaiting = true,
                playContentDescription = "播放",
                pauseContentDescription = "暂停",
                waitingContentDescription = "等待中"
            )
        )
    }

    @Test
    fun `playing playback reports pause description`() {
        assertEquals(
            "暂停",
            resolvePlaybackControlContentDescription(
                isPlaying = true,
                isPlaybackWaiting = false,
                playContentDescription = "播放",
                pauseContentDescription = "暂停",
                waitingContentDescription = "等待中"
            )
        )
    }

}
