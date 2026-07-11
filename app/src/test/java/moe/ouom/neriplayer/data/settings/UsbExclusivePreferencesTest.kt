package moe.ouom.neriplayer.data.settings

import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExclusivePreferencesTest {

    @Test
    fun `defaults follow source and keep native path when conversion is needed`() {
        val preferences = UsbExclusivePreferences()

        assertEquals(UsbExclusiveSampleRateMode.FOLLOW_SOURCE, preferences.sampleRateMode)
        assertEquals(UsbExclusiveBitDepthMode.AUTO, preferences.bitDepthMode)
        assertEquals(UsbExclusiveBufferProfile.STABLE, preferences.bufferProfile)
        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
            preferences.unsupportedFormatPolicy
        )
        assertEquals(
            listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000),
            UsbExclusiveSampleRateMode.entries.mapNotNull { it.sampleRateHz }
        )
        assertEquals(
            listOf(16, 24, 32),
            UsbExclusiveBitDepthMode.entries.mapNotNull { it.bitDepth }
        )
        assertEquals(3000, UsbExclusiveBufferProfile.LOW_LATENCY.bufferDurationMs)
        assertEquals(5000, UsbExclusiveBufferProfile.BALANCED.bufferDurationMs)
        assertEquals(12000, UsbExclusiveBufferProfile.STABLE.bufferDurationMs)
        assertEquals(true, preferences.sampleRateCompatibilityEnabled)
        assertEquals(true, preferences.bitDepthCompatibilityEnabled)
        assertEquals(true, preferences.channelCompatibilityEnabled)
        assertEquals(250, DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS)
        assertEquals(1500, DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS)
        assertEquals(DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS, preferences.foregroundBufferMs)
        assertEquals(DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS, preferences.backgroundBufferMs)
        assertEquals(
            DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE,
            preferences.sampleRateMode.storageValue
        )
        assertEquals(
            DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE,
            preferences.bitDepthMode.storageValue
        )
        assertEquals(
            DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE,
            preferences.bufferProfile.storageValue
        )
        assertEquals(
            DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
            preferences.unsupportedFormatPolicy.storageValue
        )
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, preferences.selectedDeviceKey)
    }

    @Test
    fun `storage parsing accepts stable values and rejects invalid values`() {
        val parsed = UsbExclusivePreferences.fromStorageValues(
            sampleRateMode = " 96000 ",
            bitDepthMode = "BIT_24",
            bufferProfile = "stable",
            unsupportedFormatPolicy = "CLOSEST_SUPPORTED"
        )

        assertEquals(UsbExclusiveSampleRateMode.RATE_96000, parsed.sampleRateMode)
        assertEquals(UsbExclusiveBitDepthMode.BIT_24, parsed.bitDepthMode)
        assertEquals(UsbExclusiveBufferProfile.STABLE, parsed.bufferProfile)
        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
            parsed.unsupportedFormatPolicy
        )

        assertEquals(
            UsbExclusivePreferences(),
            UsbExclusivePreferences.fromStorageValues("invalid", "0", "", null)
        )
    }

    @Test
    fun `device key is normalized and defaults to automatic`() {
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey(null))
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey("  "))
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, normalizeUsbExclusiveDeviceKey("AUTO"))
        assertEquals("usb:1:2:dac", normalizeUsbExclusiveDeviceKey(" usb:1:2:dac "))
    }

    @Test
    fun `storage defaults use closest supported policy`() {
        val parsed = UsbExclusivePreferences.fromStorageValues(
            sampleRateMode = null,
            bitDepthMode = null,
            bufferProfile = null,
            unsupportedFormatPolicy = null
        )

        assertEquals(UsbExclusivePreferences(), parsed)
    }

    @Test
    fun `explicit system fallback policy is preserved`() {
        val parsed = UsbExclusivePreferences.fromStorageValues(
            sampleRateMode = "follow_source",
            bitDepthMode = "auto",
            bufferProfile = "balanced",
            unsupportedFormatPolicy = "system_fallback"
        )

        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK,
            parsed.unsupportedFormatPolicy
        )
    }

    @Test
    fun `each illegal stored value falls back to its safe default`() {
        val illegalValues = listOf(null, "", "   ", "unknown", "-1", "48000.0")

        illegalValues.forEach { value ->
            assertEquals(
                UsbExclusiveSampleRateMode.FOLLOW_SOURCE,
                UsbExclusiveSampleRateMode.fromStorageValue(value)
            )
            assertEquals(
                UsbExclusiveBitDepthMode.AUTO,
                UsbExclusiveBitDepthMode.fromStorageValue(value)
            )
            assertEquals(
                UsbExclusiveBufferProfile.STABLE,
                UsbExclusiveBufferProfile.fromStorageValue(value)
            )
            assertEquals(
                UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
                UsbExclusiveUnsupportedFormatPolicy.fromStorageValue(value)
            )
        }
    }

    @Test
    fun `default policy converts 96000 source to 48000 device capability`() {
        val resolved = UsbExclusivePreferences().resolveSampleRateHz(
            sourceSampleRateHz = 96_000,
            supportedSampleRatesHz = listOf(48_000)
        )

        assertEquals(48_000, resolved)
    }

    @Test
    fun `buffer profiles keep stable durations and storage round trips`() {
        val expectedDurationsMs = mapOf(
            UsbExclusiveBufferProfile.LOW_LATENCY to 3000,
            UsbExclusiveBufferProfile.BALANCED to 5000,
            UsbExclusiveBufferProfile.STABLE to 12000
        )

        expectedDurationsMs.forEach { (profile, durationMs) ->
            assertEquals(durationMs, profile.bufferDurationMs)
            assertEquals(profile, UsbExclusiveBufferProfile.fromStorageValue(profile.storageValue))
            assertEquals(profile, UsbExclusiveBufferProfile.fromStorageValue(profile.name))
        }
    }

    @Test
    fun `buffer values are normalized and follow foreground state`() {
        val preferences = UsbExclusivePreferences.fromStorageValues(
            sampleRateMode = null,
            bitDepthMode = null,
            bufferProfile = "stable",
            unsupportedFormatPolicy = null,
            foregroundBufferMs = 233,
            backgroundBufferMs = 175
        )

        assertEquals(200, preferences.foregroundBufferMs)
        assertEquals(MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS, preferences.backgroundBufferMs)
        assertEquals(200, preferences.bufferDurationMs(appInForeground = true))
        assertEquals(
            MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
            preferences.bufferDurationMs(appInForeground = false)
        )
        assertEquals(250, normalizeUsbExclusiveForegroundBufferMs(260))
        assertEquals(1500, normalizeUsbExclusiveBackgroundBufferMs(1530))
    }

    @Test
    fun `follow source keeps exact supported sample rate`() {
        val resolved = UsbExclusivePreferences().resolveSampleRateHz(
            sourceSampleRateHz = 48_000,
            supportedSampleRatesHz = listOf(44_100, 48_000, 96_000)
        )

        assertEquals(48_000, resolved)
    }

    @Test
    fun `system fallback rejects unsupported fixed format`() {
        val preferences = UsbExclusivePreferences(
            sampleRateMode = UsbExclusiveSampleRateMode.RATE_192000,
            bitDepthMode = UsbExclusiveBitDepthMode.BIT_32,
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK
        )

        assertNull(preferences.resolveSampleRateHz(48_000, listOf(44_100, 48_000, 96_000)))
        assertNull(preferences.resolveBitDepth(16, listOf(16, 24)))
    }

    @Test
    fun `closest policy chooses nearest supported format`() {
        val preferences = UsbExclusivePreferences(
            sampleRateMode = UsbExclusiveSampleRateMode.RATE_88200,
            bitDepthMode = UsbExclusiveBitDepthMode.AUTO,
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertEquals(
            96_000,
            preferences.resolveSampleRateHz(48_000, listOf(44_100, 48_000, 96_000))
        )
        assertEquals(24, preferences.resolveBitDepth(20, listOf(16, 24)))
    }

    @Test
    fun `follow source closest prefers the 44100 sample rate family`() {
        val preferences = UsbExclusivePreferences(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertEquals(
            88_200,
            preferences.resolveSampleRateHz(44_100, listOf(48_000, 88_200))
        )
    }

    @Test
    fun `follow source closest prefers the 48000 sample rate family`() {
        val preferences = UsbExclusivePreferences(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertEquals(
            96_000,
            preferences.resolveSampleRateHz(48_000, listOf(44_100, 96_000))
        )
    }

    @Test
    fun `fixed sample rate closest uses numeric distance across families`() {
        val preferences = UsbExclusivePreferences(
            sampleRateMode = UsbExclusiveSampleRateMode.RATE_44100,
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertEquals(
            48_000,
            preferences.resolveSampleRateHz(192_000, listOf(48_000, 88_200))
        )
    }

    @Test
    fun `numeric closest boundary deterministically prefers higher sample rate`() {
        val preferences = UsbExclusivePreferences(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertEquals(
            48_000,
            preferences.resolveSampleRateHz(46_050, listOf(44_100, 48_000))
        )
    }

    @Test
    fun `empty and invalid capabilities cannot resolve a format`() {
        val preferences = UsbExclusivePreferences(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertNull(preferences.resolveSampleRateHz(48_000, emptyList()))
        assertNull(preferences.resolveSampleRateHz(48_000, listOf(0, -44_100)))
        assertNull(preferences.resolveBitDepth(24, emptyList()))
        assertNull(preferences.resolveBitDepth(24, listOf(0, -16)))
        assertNull(preferences.resolveSampleRateHz(0, listOf(44_100, 48_000)))
        assertNull(preferences.resolveBitDepth(0, listOf(16, 24, 32)))
    }

    @Test
    fun `auto bit depth widens without loss before applying fallback policy`() {
        val preferences = UsbExclusivePreferences(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK
        )

        assertEquals(24, preferences.resolveBitDepth(16, listOf(24, 32)))
        assertEquals(32, preferences.resolveBitDepth(24, listOf(16, 32)))
        assertEquals(24, preferences.resolveBitDepth(24, listOf(16, 24, 32)))
        assertNull(preferences.resolveBitDepth(32, listOf(16, 24)))
    }

    @Test
    fun `fixed bit depth obeys fallback and closest tie rules`() {
        val fallbackPreferences = UsbExclusivePreferences(
            bitDepthMode = UsbExclusiveBitDepthMode.BIT_24,
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK
        )
        val closestPreferences = fallbackPreferences.copy(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )

        assertNull(fallbackPreferences.resolveBitDepth(16, listOf(16, 32)))
        assertEquals(32, closestPreferences.resolveBitDepth(16, listOf(16, 32)))
    }

    @Test
    fun `compatibility switches can require exact usb formats`() {
        val preferences = UsbExclusivePreferences(
            sampleRateMode = UsbExclusiveSampleRateMode.RATE_192000,
            bitDepthMode = UsbExclusiveBitDepthMode.BIT_32,
            sampleRateCompatibilityEnabled = false,
            bitDepthCompatibilityEnabled = false,
            channelCompatibilityEnabled = false
        )

        assertNull(preferences.resolveSampleRateHz(48_000, listOf(48_000)))
        assertNull(preferences.resolveBitDepth(16, listOf(16, 24)))
        assertNull(preferences.resolveChannelCount(6, listOf(2)))
        assertEquals(192_000, preferences.resolveSampleRateHz(48_000, listOf(192_000)))
        assertEquals(32, preferences.resolveBitDepth(16, listOf(32)))
        assertEquals(2, preferences.resolveChannelCount(2, listOf(2)))
    }

    @Test
    fun `snapshot sanitizes invalid stored selections`() {
        val snapshot = PlaybackPreferenceSnapshot(
            usbExclusiveDeviceKey = "  ",
            usbExclusiveSampleRateMode = "unsupported_rate",
            usbExclusiveBitDepthMode = "8",
            usbExclusiveBufferProfile = "huge",
            usbExclusiveUnsupportedFormatPolicy = "force_native"
        ).sanitized()

        assertEquals(DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE, snapshot.usbExclusiveSampleRateMode)
        assertEquals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, snapshot.usbExclusiveDeviceKey)
        assertEquals(DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE, snapshot.usbExclusiveBitDepthMode)
        assertEquals(DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE, snapshot.usbExclusiveBufferProfile)
        assertEquals(
            DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
            snapshot.usbExclusiveUnsupportedFormatPolicy
        )
        assertEquals(UsbExclusivePreferences(), snapshot.toUsbExclusivePreferences())
    }

    @Test
    fun `snapshot preserves explicit fallback policy`() {
        val snapshot = PlaybackPreferenceSnapshot(
            usbExclusiveSampleRateMode = "follow_source",
            usbExclusiveBitDepthMode = "auto",
            usbExclusiveBufferProfile = "balanced",
            usbExclusiveUnsupportedFormatPolicy = "system_fallback"
        ).sanitized()

        assertEquals(
            UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK,
            snapshot.toUsbExclusivePreferences().unsupportedFormatPolicy
        )
    }

    @Test
    fun `data store snapshot restores usb format selections`() {
        val snapshot = preferencesOf(
            SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY to "usb:31:2849:ab13x_usb_audio",
            SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE to "176400",
            SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE to "24",
            SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE to "stable",
            SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY to "closest_supported"
        ).toPlaybackPreferenceSnapshot()

        assertEquals(
            UsbExclusivePreferences(
                selectedDeviceKey = "usb:31:2849:ab13x_usb_audio",
                sampleRateMode = UsbExclusiveSampleRateMode.RATE_176400,
                bitDepthMode = UsbExclusiveBitDepthMode.BIT_24,
                bufferProfile = UsbExclusiveBufferProfile.STABLE,
                unsupportedFormatPolicy =
                    UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
            ),
            snapshot.toUsbExclusivePreferences()
        )
    }
}
