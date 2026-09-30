package moe.ouom.neriplayer.core.player.service.usb

import android.media.VolumeProvider
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class UsbExclusiveMediaSessionVolumeRouterTest {
    @Test
    fun `native USB route installs one provider and restores local routing when path changes`() {
        val port = FakePort()
        val router = UsbExclusiveMediaSessionVolumeRouter(port)

        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)
        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)
        assertTrue(router.isRemote())
        assertEquals(1, port.remoteCalls)
        assertEquals(0.5f, port.fraction, 0.0001f)

        router.update(UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM, bitPerfect = false)
        assertFalse(router.isRemote())
        assertEquals(1, port.localCalls)
        assertEquals(1, port.clearCalls)
    }

    @Test
    fun `bit perfect playback never enters remote volume routing`() {
        val port = FakePort()
        val router = UsbExclusiveMediaSessionVolumeRouter(port)

        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = true)

        assertFalse(router.isRemote())
        assertEquals(0, port.remoteCalls)
        assertEquals(1, port.clearCalls)
    }

    @Test
    fun `remote routing failure clears fraction and permits a later retry`() {
        val port = FakePort().apply { failRemote = true }
        val router = UsbExclusiveMediaSessionVolumeRouter(port)

        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)
        assertFalse(router.isRemote())
        assertEquals(1, port.localCalls)
        assertEquals(1, port.clearCalls)

        port.failRemote = false
        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)
        assertTrue(router.isRemote())
        assertEquals(2, port.remoteCalls)
    }

    @Test
    fun `fraction update failure restores local routing after remote route was installed`() {
        val port = FakePort().apply { failFractionUpdate = true }
        val router = UsbExclusiveMediaSessionVolumeRouter(port)

        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)

        assertFalse(router.isRemote())
        assertEquals(1, port.remoteCalls)
        assertEquals(1, port.localCalls)
        assertEquals(1, port.clearCalls)
    }

    @Test
    fun `destroy clears fraction even when the MediaSession has already gone`() {
        val port = FakePort().apply { hasSession = false }
        val router = UsbExclusiveMediaSessionVolumeRouter(port)
        router.update(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, bitPerfect = false)

        router.disable("destroy")

        assertFalse(router.isRemote())
        assertEquals(0, port.localCalls)
        assertEquals(1, port.clearCalls)
    }

    private class FakePort : UsbExclusiveVolumeRoutingPort {
        var remoteCalls = 0
        var localCalls = 0
        var clearCalls = 0
        var fraction = -1f
        var failRemote = false
        var failFractionUpdate = false
        var hasSession = true
        private val provider = mock(VolumeProvider::class.java).also {
            `when`(it.currentVolume).thenReturn(5)
            `when`(it.maxVolume).thenReturn(10)
        }

        override fun createProvider(): VolumeProvider = provider
        override fun setRemote(provider: VolumeProvider) {
            remoteCalls += 1
            if (failRemote) error("routing unavailable")
        }
        override fun setLocal() { localCalls += 1 }
        override fun hasSession(): Boolean = hasSession
        override fun updateSessionFraction(fraction: Float) {
            if (failFractionUpdate) error("volume bridge unavailable")
            this.fraction = fraction
        }
        override fun clearSessionFraction() { clearCalls += 1 }
    }
}
