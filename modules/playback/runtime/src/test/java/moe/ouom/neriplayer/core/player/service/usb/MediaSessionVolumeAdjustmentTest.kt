package moe.ouom.neriplayer.core.player.service.usb

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionVolumeAdjustmentTest {

    @Test
    fun `raise and lower stay inside the provider range`() {
        assertEquals(6, adjust(current = 5, direction = AudioManager.ADJUST_RAISE))
        assertEquals(10, adjust(current = 10, direction = AudioManager.ADJUST_RAISE))
        assertEquals(4, adjust(current = 5, direction = AudioManager.ADJUST_LOWER))
        assertEquals(0, adjust(current = 0, direction = AudioManager.ADJUST_LOWER))
    }

    @Test
    fun `mute commands restore a minimal audible level`() {
        assertEquals(0, adjust(current = 7, direction = AudioManager.ADJUST_MUTE))
        assertEquals(1, adjust(current = 0, direction = AudioManager.ADJUST_UNMUTE))
        assertEquals(4, adjust(current = 4, direction = AudioManager.ADJUST_UNMUTE))
        assertEquals(1, adjust(current = 0, direction = AudioManager.ADJUST_TOGGLE_MUTE))
        assertEquals(0, adjust(current = 3, direction = AudioManager.ADJUST_TOGGLE_MUTE))
    }

    @Test
    fun `other directions keep the clamped current index`() {
        assertEquals(1, adjust(current = 15, max = 0, direction = AudioManager.ADJUST_SAME))
        assertEquals(0, adjust(current = -3, direction = AudioManager.ADJUST_SAME))
    }

    private fun adjust(current: Int, max: Int = 10, direction: Int) =
        adjustedUsbExclusiveVolumeProviderIndex(
            currentIndex = current,
            providerMaxIndex = max,
            direction = direction
        )
}
