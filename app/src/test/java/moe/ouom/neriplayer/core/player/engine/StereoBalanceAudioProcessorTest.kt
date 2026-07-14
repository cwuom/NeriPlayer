package moe.ouom.neriplayer.core.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoBalanceAudioProcessorTest {
    @Test
    fun `center balance keeps both channels at full gain`() {
        val gains = stereoBalanceGains(0f)

        assertEquals(1f, gains.left, 0.0001f)
        assertEquals(1f, gains.right, 0.0001f)
    }

    @Test
    fun `left balance attenuates right channel only`() {
        val gains = stereoBalanceGains(-0.4f)

        assertEquals(1f, gains.left, 0.0001f)
        assertEquals(0.6f, gains.right, 0.0001f)
    }

    @Test
    fun `right balance attenuates left channel only`() {
        val gains = stereoBalanceGains(0.75f)

        assertEquals(0.25f, gains.left, 0.0001f)
        assertEquals(1f, gains.right, 0.0001f)
    }
}
