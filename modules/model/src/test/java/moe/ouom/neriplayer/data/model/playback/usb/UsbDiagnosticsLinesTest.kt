package moe.ouom.neriplayer.data.model.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDiagnosticsLinesTest {

    @Test
    fun `audio output lines truncate long format lists and show empty ones`() {
        val output = UsbAudioOutputDebugInfo(
            id = 7,
            type = 11,
            typeName = "USB_DEVICE",
            productName = "DAC",
            address = "card=1;device=0",
            isSink = true,
            isSource = false,
            sampleRates = (1..10).map { it * 1_000 },
            channelCounts = listOf(2),
            encodings = emptyList(),
            isUsbOutput = true
        )

        assertEquals(
            "id=7 type=USB_DEVICE(11) name=DAC address=card=1;device=0 usb=true " +
                "rates=[1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000]+10 channels=[2] encodings=[]",
            output.compactLine()
        )
    }

    @Test
    fun `host device lines show hex vendor and product ids with their interfaces`() {
        val device = UsbHostDeviceDebugInfo(
            deviceKey = "usb:10610:71:dac",
            deviceName = "/dev/bus/usb/001/002",
            productName = "DAC",
            manufacturerName = "Vendor",
            vendorId = 0x2972,
            productId = 0x47,
            deviceClass = 0,
            deviceClassName = "PER_INTERFACE",
            deviceSubclass = 0,
            deviceProtocol = 0,
            interfaceCount = 1,
            hasAudioInterface = true,
            hasAudioStreamingInterface = true,
            hasPermission = false,
            interfaces = listOf(UsbInterfaceDebugInfo(0, 1, "AUDIO", 2, 32, 1))
        )

        assertEquals("0x2972:0x47", device.vendorProductId)
        assertEquals(
            "DAC 0x2972:0x47 name=/dev/bus/usb/001/002 class=PER_INTERFACE(0) audio=true streaming=true " +
                "permission=false interfaces=[#0:AUDIO(1)/sub=2/proto=32/eps=1]",
            device.compactLine()
        )
    }
}
