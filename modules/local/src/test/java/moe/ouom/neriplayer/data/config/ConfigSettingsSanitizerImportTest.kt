package moe.ouom.neriplayer.data.config

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ConfigSettingsSanitizerImportTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
        doReturn(INVALID_VALUES_WARNING).`when`(context)
            .getString(CoreCommonR.string.config_import_warning_invalid_setting_values)
        doReturn(BACKGROUND_WARNING).`when`(context)
            .getString(CoreCommonR.string.config_import_warning_background_image)
    }
    private val sanitizer = ConfigSettingsSanitizer(context)

    @Test
    fun `int settings outside their supported values are clamped`() {
        val warnings = mutableListOf<String>()

        val sanitized = sanitizer.sanitize(
            TypedPreferenceSnapshot(
                ints = mapOf(
                    SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB.name to 2_000,
                    SettingsKeys.DOWNLOAD_PARALLELISM.name to 0,
                    SettingsKeys.PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES.name to 45,
                    SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS.name to 123,
                    "unknown_int_setting" to 1
                )
            ),
            warnings
        )

        assertEquals(
            mapOf(
                SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB.name to 1_500,
                SettingsKeys.DOWNLOAD_PARALLELISM.name to 1,
                SettingsKeys.PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES.name to 60,
                SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS.name to 123
            ),
            sanitized.ints
        )
        assertEquals(listOf(INVALID_VALUES_WARNING), warnings)
    }

    @Test
    fun `supported int settings are imported without warnings`() {
        val ints = mapOf(
            SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB.name to 300,
            SettingsKeys.DOWNLOAD_PARALLELISM.name to 4,
            SettingsKeys.PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES.name to 30
        )
        val warnings = mutableListOf<String>()

        assertEquals(ints, sanitizer.sanitize(TypedPreferenceSnapshot(ints = ints), warnings).ints)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `string choices are normalized and unusable values are dropped`() {
        val warnings = mutableListOf<String>()

        val sanitized = sanitizer.sanitize(
            TypedPreferenceSnapshot(
                strings = mapOf(
                    SettingsKeys.AUDIO_QUALITY.name to " LOSSLESS ",
                    SettingsKeys.BILI_AUDIO_QUALITY.name to "ultra",
                    SettingsKeys.DEFAULT_START_DESTINATION.name to "library",
                    SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name to " Rock ",
                    SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS.name to "not,levels",
                    SettingsKeys.THEME_SEED_COLOR.name to "zzz",
                    SettingsKeys.THEME_COLOR_PALETTE.name to "#ff0000, 00ff00,FF0000, nope",
                    "unknown_string_setting" to "value"
                )
            ),
            warnings
        )

        assertEquals(
            mapOf(
                SettingsKeys.AUDIO_QUALITY.name to "lossless",
                SettingsKeys.BILI_AUDIO_QUALITY.name to "high",
                SettingsKeys.DEFAULT_START_DESTINATION.name to "library",
                SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name to "rock",
                SettingsKeys.THEME_SEED_COLOR.name to "0061A4",
                SettingsKeys.THEME_COLOR_PALETTE.name to "FF0000,00FF00"
            ),
            sanitized.strings
        )
        assertEquals(listOf(INVALID_VALUES_WARNING), warnings)
    }

    @Test
    fun `unknown equalizer presets fall back to flat and empty palettes are removed`() {
        val warnings = mutableListOf<String>()

        val sanitized = sanitizer.sanitize(
            TypedPreferenceSnapshot(
                strings = mapOf(
                    SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name to "stadium",
                    SettingsKeys.THEME_COLOR_PALETTE.name to "nope, #12"
                )
            ),
            warnings
        )

        assertEquals(mapOf(SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name to "flat"), sanitized.strings)
        assertEquals(listOf(INVALID_VALUES_WARNING), warnings)
    }

    @Test
    fun `canonical string choices are imported unchanged`() {
        val strings = mapOf(
            SettingsKeys.AUDIO_QUALITY.name to "hires",
            SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name to "custom",
            SettingsKeys.THEME_COLOR_PALETTE.name to "FF0000,00FF00"
        )
        val warnings = mutableListOf<String>()

        assertEquals(strings, sanitizer.sanitize(TypedPreferenceSnapshot(strings = strings), warnings).strings)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `blank background images are dropped as invalid values`() {
        val warnings = mutableListOf<String>()

        val sanitized = sanitizer.sanitize(
            TypedPreferenceSnapshot(strings = mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to "  ")),
            warnings
        )

        assertEquals(emptyMap<String, String>(), sanitized.strings)
        assertEquals(listOf(INVALID_VALUES_WARNING), warnings)
    }

    @Test
    fun `unparseable background images are dropped with a background warning`() {
        val warnings = mutableListOf<String>()

        val sanitized = sanitizer.sanitize(
            TypedPreferenceSnapshot(strings = mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to BACKGROUND_URI)),
            warnings
        )

        assertEquals(emptyMap<String, String>(), sanitized.strings)
        assertEquals(listOf(BACKGROUND_WARNING), warnings)
    }

    @Test
    fun `readable background images are kept`() {
        val warnings = mutableListOf<String>()

        val sanitized = withBackgroundUri { uri ->
            doReturn(ByteArrayInputStream(byteArrayOf(1))).`when`(resolver).openInputStream(uri)
            sanitizer.sanitize(
                TypedPreferenceSnapshot(strings = mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to BACKGROUND_URI)),
                warnings
            )
        }

        assertEquals(mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to BACKGROUND_URI), sanitized.strings)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `background images the provider cannot open are dropped`() {
        val missingStream = mutableListOf<String>()
        val deniedStream = mutableListOf<String>()

        val missing = withBackgroundUri { uri ->
            doReturn(null).`when`(resolver).openInputStream(uri)
            sanitizer.sanitize(
                TypedPreferenceSnapshot(strings = mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to BACKGROUND_URI)),
                missingStream
            )
        }
        val denied = withBackgroundUri { uri ->
            doThrow(SecurityException("permission revoked")).`when`(resolver).openInputStream(uri)
            sanitizer.sanitize(
                TypedPreferenceSnapshot(strings = mapOf(SettingsKeys.BACKGROUND_IMAGE_URI.name to BACKGROUND_URI)),
                deniedStream
            )
        }

        assertEquals(emptyMap<String, String>(), missing.strings)
        assertEquals(emptyMap<String, String>(), denied.strings)
        assertEquals(listOf(BACKGROUND_WARNING), missingStream)
        assertEquals(listOf(BACKGROUND_WARNING), deniedStream)
    }

    private fun <T> withBackgroundUri(block: (Uri) -> T): T {
        val uri = mock(Uri::class.java)
        return mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenReturn(uri)
            block(uri)
        }
    }

    private companion object {
        const val INVALID_VALUES_WARNING = "invalid values adjusted"
        const val BACKGROUND_WARNING = "background image unavailable"
        const val BACKGROUND_URI = "content://media/external/images/media/5"
    }
}
