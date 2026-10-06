package moe.ouom.neriplayer.data.model.settings.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExclusivePreferencesTest {

    @Test
    fun `fixed modes override the source and follow modes need a positive source`() {
        assertEquals(44_100, UsbExclusiveSampleRateMode.FOLLOW_SOURCE.requestedSampleRateHz(44_100))
        assertNull(UsbExclusiveSampleRateMode.FOLLOW_SOURCE.requestedSampleRateHz(0))
        assertEquals(96_000, UsbExclusiveSampleRateMode.RATE_96000.requestedSampleRateHz(44_100))
        assertEquals(24, UsbExclusiveBitDepthMode.AUTO.requestedBitDepth(24))
        assertNull(UsbExclusiveBitDepthMode.AUTO.requestedBitDepth(-1))
        assertEquals(16, UsbExclusiveBitDepthMode.BIT_16.requestedBitDepth(24))
    }

    @Test
    fun `stored values match storage values or enum names ignoring case`() {
        assertEquals(UsbExclusiveSampleRateMode.RATE_48000, UsbExclusiveSampleRateMode.fromStorageValue(" 48000 "))
        assertEquals(UsbExclusiveSampleRateMode.RATE_88200, UsbExclusiveSampleRateMode.fromStorageValue("rate_88200"))
        assertEquals(UsbExclusiveSampleRateMode.FOLLOW_SOURCE, UsbExclusiveSampleRateMode.fromStorageValue("11025"))
        assertEquals(UsbExclusiveSampleRateMode.FOLLOW_SOURCE, UsbExclusiveSampleRateMode.fromStorageValue(null))
        assertEquals(UsbExclusiveBitDepthMode.BIT_24, UsbExclusiveBitDepthMode.fromStorageValue("BIT_24"))
        assertEquals(UsbExclusiveBufferProfile.BALANCED, UsbExclusiveBufferProfile.fromStorageValue("Balanced"))
        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK,
            UsbExclusiveUnsupportedFormatPolicy.fromStorageValue("SYSTEM_FALLBACK")
        )
        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
            UsbExclusiveUnsupportedFormatPolicy.fromStorageValue(" ")
        )
    }

    @Test
    fun `auto bit depth picks the smallest supported depth that keeps source precision`() {
        val auto = UsbExclusivePreferences()

        assertEquals(24, auto.resolveBitDepth(16, listOf(32, 0, 24, 24)))
        assertEquals(24, auto.resolveBitDepth(32, listOf(16, 24)))
        assertNull(auto.resolveBitDepth(0, listOf(16, 24)))
    }

    @Test
    fun `fixed bit depths honour compatibility and fallback policy`() {
        val fixed16 = UsbExclusivePreferences(bitDepthMode = UsbExclusiveBitDepthMode.BIT_16)
        val strict = fixed16.copy(bitDepthCompatibilityEnabled = false)
        val systemFallback = fixed16.copy(unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK)

        assertEquals(16, fixed16.resolveBitDepth(24, listOf(16, 24)))
        assertEquals(24, fixed16.resolveBitDepth(24, listOf(24, 32)))
        assertNull(strict.resolveBitDepth(24, listOf(24)))
        assertEquals(16, strict.resolveBitDepth(24, listOf(16)))
        assertNull(systemFallback.resolveBitDepth(24, listOf(24, 32)))
    }

    @Test
    fun `follow source sample rates stay in the source rate family when possible`() {
        val follow = UsbExclusivePreferences()

        assertEquals(44_100, follow.resolveSampleRateHz(88_200, listOf(48_000, 96_000, 44_100)))
        assertEquals(192_000, follow.resolveSampleRateHz(96_000, listOf(44_100, 88_200, 192_000)))
        assertEquals(96_000, follow.resolveSampleRateHz(88_200, listOf(48_000, 96_000)))
        assertEquals(44_100, follow.resolveSampleRateHz(44_100, listOf(44_100, 48_000)))
        assertNull(follow.resolveSampleRateHz(0, listOf(44_100)))
    }

    @Test
    fun `rates outside both families use the nearest rate and prefer the higher one on ties`() {
        val follow = UsbExclusivePreferences()

        assertEquals(48_000, follow.resolveSampleRateHz(50_000, listOf(44_100, 48_000)))
        assertEquals(48_000, follow.resolveSampleRateHz(46_050, listOf(44_100, 48_000)))
        assertNull(
            follow.copy(sampleRateMode = UsbExclusiveSampleRateMode.RATE_48000, sampleRateCompatibilityEnabled = false)
                .resolveSampleRateHz(44_100, listOf(44_100))
        )
    }

    @Test
    fun `buffer durations are clamped and stepped per app state`() {
        val preferences = UsbExclusivePreferences(foregroundBufferMs = 5_000, backgroundBufferMs = 10)

        assertEquals(MAX_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS, preferences.bufferDurationMs(appInForeground = true))
        assertEquals(MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS, preferences.bufferDurationMs(appInForeground = false))
        assertEquals(300, UsbExclusivePreferences(foregroundBufferMs = 333).bufferDurationMs(appInForeground = true))
        assertEquals(DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS, UsbExclusivePreferences().reservedBufferDurationMs())
    }

    @Test
    fun `device keys treat blank and auto as automatic selection`() {
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey(null))
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey("  "))
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey(" AUTO "))
        assertEquals("usb:1:2:dac", normalizeUsbExclusiveDeviceKey(" usb:1:2:dac "))
    }
}
