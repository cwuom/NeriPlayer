package moe.ouom.neriplayer.core.player.debug

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.invocation.InvocationOnMock

class UsbExclusiveDebugLoggerSnapshotTest {

    private data class LogLine(val level: Char, val message: String, val error: Throwable? = null)

    private lateinit var log: MockedStatic<Log>
    private val lines = mutableListOf<LogLine>()

    @Before
    fun recordLogcat() {
        log = mockStatic(Log::class.java)
        log.`when`<Int> { Log.d(anyString(), anyString(), nullable(Throwable::class.java)) }
            .thenAnswer { record('D', it) }
        log.`when`<Int> { Log.w(anyString(), anyString(), nullable(Throwable::class.java)) }
            .thenAnswer { record('W', it) }
    }

    @After
    fun releaseLogcat() {
        log.close()
    }

    @Test
    fun `audio outputs are described with fallbacks for blank and unreadable fields`() {
        val dac = audioDevice(
            id = 7,
            type = AudioDeviceInfo.TYPE_USB_DEVICE,
            productName = "USB DAC",
            address = "card=1;device=0",
            rates = intArrayOf(44_100, 48_000, 96_000),
            channels = intArrayOf(2)
        )
        val unnamed = audioDevice(
            id = 8,
            type = 999,
            productName = null,
            address = "",
            source = true,
            rates = IntArray(10) { 8_000 * (it + 1) },
            channels = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            encodings = intArrayOf(2)
        )
        val detached = audioDevice(
            id = 9,
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            nameFailure = IllegalStateException("detached"),
            addressFailure = SecurityException("denied")
        )
        val blankHeadset = audioDevice(
            id = 10,
            type = AudioDeviceInfo.TYPE_USB_HEADSET,
            productName = "   ",
            address = " ",
            source = true,
            rates = intArrayOf(48_000),
            channels = intArrayOf(2),
            encodings = intArrayOf(2, 4)
        )
        val audioManager = audioManager(dac, unnamed, detached, blankHeadset)

        UsbExclusiveDebugLogger.logSnapshot(context(usbService = null), audioManager, "apply_policy", enabled = true)

        assertEquals(
            listOf(
                LogLine(
                    'D',
                    "snapshot(apply_policy): enabled=true, preferred=none, audioOutputs=4, " +
                        "usbAudioOutputs=2, outputs=[" +
                        "id=7,type=USB_DEVICE(11),name=USB DAC,address=card=1;device=0," +
                        "sink=true,source=false,rates=[44100, 48000, 96000],channels=[2],encodings=[], " +
                        "id=8,type=UNKNOWN(999),name=blank,address=blank,sink=true,source=true," +
                        "rates=[8000, 16000, 24000, 32000, 40000, 48000, 56000, 64000]+10," +
                        "channels=[1, 2, 3, 4, 5, 6, 7, 8],encodings=[2], " +
                        "id=9,type=BUILTIN_SPEAKER(2),name=productName_error:IllegalStateException," +
                        "address=address_error:SecurityException,sink=true,source=false," +
                        "rates=[],channels=[],encodings=[], " +
                        "id=10,type=USB_HEADSET(22),name=blank,address=blank,sink=true,source=true," +
                        "rates=[48000],channels=[2],encodings=[2, 4]]"
                ),
                LogLine('W', "usbHost(apply_policy): UsbManager unavailable")
            ),
            lines
        )
    }

    @Test
    fun `failed output query still logs the preferred device`() {
        val failure = IllegalStateException("audio server died")
        val audioManager = mock(AudioManager::class.java).also {
            `when`(it.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenThrow(failure)
        }
        val preferred = audioDevice(
            id = 3,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            productName = "Buds",
            address = "AA:BB"
        )

        UsbExclusiveDebugLogger.logSnapshot(
            context(usbService = "not a usb manager"),
            audioManager,
            "route_change",
            enabled = false,
            preferredDevice = preferred
        )

        assertEquals(
            listOf(
                LogLine('W', "snapshot(route_change): failed to query audio outputs", failure),
                LogLine(
                    'D',
                    "snapshot(route_change): enabled=false, preferred=id=3,type=BLUETOOTH_A2DP(8)," +
                        "name=Buds,address=AA:BB,sink=true,source=false,rates=[],channels=[]," +
                        "encodings=[], audioOutputs=0, usbAudioOutputs=0, outputs=[]"
                ),
                LogLine('W', "usbHost(route_change): UsbManager unavailable")
            ),
            lines
        )
    }

    @Test
    fun `usb hosts are listed with class names and permission state`() {
        val usbManager = mock(UsbManager::class.java)
        val audio = usbDevice("/dev/bus/usb/001/002", UsbConstants.USB_CLASS_AUDIO, vendorId = 8369, productId = 8, interfaces = 3)
        val modem = usbDevice("/dev/bus/usb/001/003", UsbConstants.USB_CLASS_COMM, subclass = 2, protocol = 1, interfaces = 2)
        val keyboard = usbDevice("/dev/bus/usb/001/004", UsbConstants.USB_CLASS_HID)
        val devices = listOf(
            audio,
            modem,
            keyboard,
            usbDevice("/dev/bus/usb/001/005", UsbConstants.USB_CLASS_MASS_STORAGE),
            usbDevice("/dev/bus/usb/001/006", UsbConstants.USB_CLASS_HUB),
            usbDevice("/dev/bus/usb/001/007", UsbConstants.USB_CLASS_PER_INTERFACE),
            usbDevice("/dev/bus/usb/001/008", UsbConstants.USB_CLASS_VENDOR_SPEC),
            usbDevice("/dev/bus/usb/001/009", UsbConstants.USB_CLASS_WIRELESS_CONTROLLER)
        )
        val attached = LinkedHashMap<String, UsbDevice>().apply {
            devices.forEach { put(it.deviceName, it) }
        }
        `when`(usbManager.deviceList).thenReturn(attached)
        `when`(usbManager.hasPermission(audio)).thenReturn(true)
        `when`(usbManager.hasPermission(keyboard)).thenThrow(SecurityException("no access"))

        UsbExclusiveDebugLogger.logSnapshot(context(usbManager), audioManager(), "scan", enabled = false)

        assertEquals(
            listOf(
                LogLine(
                    'D',
                    "snapshot(scan): enabled=false, preferred=none, audioOutputs=0, usbAudioOutputs=0, outputs=[]"
                ),
                LogLine(
                    'D',
                    "usbHost(scan): devices=8, list=[" + listOf(
                        "name=/dev/bus/usb/001/002,vendor=8369,product=8,class=AUDIO(1),subclass=0," +
                            "protocol=0,interfaces=3,hasPermission=true",
                        "name=/dev/bus/usb/001/003,vendor=1,product=2,class=COMM(2),subclass=2," +
                            "protocol=1,interfaces=2,hasPermission=false",
                        "name=/dev/bus/usb/001/004,vendor=1,product=2,class=HID(3),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=error:SecurityException",
                        "name=/dev/bus/usb/001/005,vendor=1,product=2,class=MASS_STORAGE(8),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=false",
                        "name=/dev/bus/usb/001/006,vendor=1,product=2,class=HUB(9),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=false",
                        "name=/dev/bus/usb/001/007,vendor=1,product=2,class=PER_INTERFACE(0),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=false",
                        "name=/dev/bus/usb/001/008,vendor=1,product=2,class=VENDOR_SPEC(255),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=false",
                        "name=/dev/bus/usb/001/009,vendor=1,product=2,class=UNKNOWN(224),subclass=0," +
                            "protocol=0,interfaces=1,hasPermission=false"
                    ).joinToString(", ") + "]"
                )
            ),
            lines
        )
    }

    @Test
    fun `usb device query failure logs an empty host list`() {
        val failure = IllegalStateException("usb service died")
        val usbManager = mock(UsbManager::class.java).also {
            `when`(it.deviceList).thenThrow(failure)
        }

        UsbExclusiveDebugLogger.logSnapshot(context(usbManager), audioManager(), "resume", enabled = true)

        assertEquals(
            listOf(
                LogLine(
                    'D',
                    "snapshot(resume): enabled=true, preferred=none, audioOutputs=0, usbAudioOutputs=0, outputs=[]"
                ),
                LogLine('W', "usbHost(resume): failed to query USB devices", failure),
                LogLine('D', "usbHost(resume): devices=0, list=[]")
            ),
            lines
        )
    }

    @Test
    fun `device callbacks count missing and present device arrays`() {
        val dac = audioDevice(id = 7, type = AudioDeviceInfo.TYPE_USB_DEVICE, productName = "DAC", address = "card=1")

        UsbExclusiveDebugLogger.logAudioDeviceCallback("onAudioDevicesAdded", null)
        UsbExclusiveDebugLogger.logAudioDeviceCallback("onAudioDevicesRemoved", arrayOf(dac))

        assertEquals(
            listOf(
                LogLine('D', "onAudioDevicesAdded: count=0, devices=[]"),
                LogLine(
                    'D',
                    "onAudioDevicesRemoved: count=1, devices=[id=7,type=USB_DEVICE(11),name=DAC," +
                        "address=card=1,sink=true,source=false,rates=[],channels=[],encodings=[]]"
                )
            ),
            lines
        )
    }

    private fun record(level: Char, invocation: InvocationOnMock): Int {
        lines += LogLine(level, invocation.getArgument(1), invocation.getArgument(2))
        return 0
    }

    private fun context(usbService: Any?): Context = mock(Context::class.java).also {
        `when`(it.getSystemService(Context.USB_SERVICE)).thenReturn(usbService)
    }

    private fun audioManager(vararg outputs: AudioDeviceInfo): AudioManager =
        mock(AudioManager::class.java).also {
            `when`(it.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(*outputs))
        }

    private fun audioDevice(
        id: Int,
        type: Int,
        productName: CharSequence? = null,
        address: String = "",
        source: Boolean = false,
        rates: IntArray = IntArray(0),
        channels: IntArray = IntArray(0),
        encodings: IntArray = IntArray(0),
        nameFailure: RuntimeException? = null,
        addressFailure: RuntimeException? = null
    ): AudioDeviceInfo = mock(AudioDeviceInfo::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.type).thenReturn(type)
        if (nameFailure != null) {
            `when`(it.productName).thenThrow(nameFailure)
        } else {
            `when`(it.productName).thenReturn(productName)
        }
        if (addressFailure != null) {
            `when`(it.address).thenThrow(addressFailure)
        } else {
            `when`(it.address).thenReturn(address)
        }
        `when`(it.isSink).thenReturn(true)
        `when`(it.isSource).thenReturn(source)
        `when`(it.sampleRates).thenReturn(rates)
        `when`(it.channelCounts).thenReturn(channels)
        `when`(it.encodings).thenReturn(encodings)
    }

    private fun usbDevice(
        path: String,
        deviceClass: Int,
        vendorId: Int = 1,
        productId: Int = 2,
        subclass: Int = 0,
        protocol: Int = 0,
        interfaces: Int = 1
    ): UsbDevice = mock(UsbDevice::class.java).also {
        `when`(it.deviceName).thenReturn(path)
        `when`(it.vendorId).thenReturn(vendorId)
        `when`(it.productId).thenReturn(productId)
        `when`(it.deviceClass).thenReturn(deviceClass)
        `when`(it.deviceSubclass).thenReturn(subclass)
        `when`(it.deviceProtocol).thenReturn(protocol)
        `when`(it.interfaceCount).thenReturn(interfaces)
    }
}
