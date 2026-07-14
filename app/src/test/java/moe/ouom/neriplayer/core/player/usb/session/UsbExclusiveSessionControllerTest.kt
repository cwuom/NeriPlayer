package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSessionControllerTest {

    @Test
    fun `24 bit usb container change does not reuse current native handle`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=24 subslot=3 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                preferredOutputFormat = "rate=96000 channels=2 bits=24 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            )
        )
    }

    @Test
    fun `lower bit depth fallback is not reused for later higher precision request`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=16 subslot=2 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                preferredOutputFormat = "rate=96000 channels=2 bits=32 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            )
        )
    }

    @Test
    fun `24 bit fallback is not reused for later 32 bit request`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=24 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                preferredOutputFormat = "rate=96000 channels=2 bits=32 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            )
        )
    }
}
