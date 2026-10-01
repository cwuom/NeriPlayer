package moe.ouom.neriplayer.core.player.usb.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbExclusiveNativeBridgePackagingTest {
    @Test
    fun libraryDependencyLoadsAndResolvesJniWithoutAUsbDevice() {
        assertTrue(UsbExclusiveNativeBridge.ensureLoaded())
        assertEquals("none", UsbExclusiveNativeBridge.runtimeReport(0L))
    }
}
