package moe.ouom.neriplayer.data.config

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.player.download.normalizeDownloadParallelism
import moe.ouom.neriplayer.core.player.model.DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresets
import moe.ouom.neriplayer.core.player.model.decodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.encodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.data.settings.PlaybackServiceIdleShutdownPreference
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsBackupKeys
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsAlignment
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsAlpha
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsColorHex
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsFontSizeSp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsMaxWidthDp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsOutlineWidthDp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsPosition
import moe.ouom.neriplayer.data.settings.normalizeLyricDefaultOffsetMs
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import java.util.Locale

internal class ConfigSettingsSanitizer(private val context: Context) {

    fun sanitize(
        snapshot: TypedPreferenceSnapshot,
        warnings: MutableList<String>
    ): TypedPreferenceSnapshot {
        var adjustedInvalidValue = false
        fun markAdjustedInvalidValue() {
            adjustedInvalidValue = true
        }

        val booleans = filterKnownSettings(
            source = snapshot.booleans,
            allowedNames = SETTINGS_BOOLEAN_KEY_NAMES,
            onAdjusted = ::markAdjustedInvalidValue
        )
        val floats = sanitizeFloatSettings(snapshot.floats, ::markAdjustedInvalidValue)
        val ints = sanitizeIntSettings(snapshot.ints, ::markAdjustedInvalidValue)
        val longs = sanitizeLongSettings(snapshot.longs, ::markAdjustedInvalidValue)
        val strings = sanitizeStringSettings(
            source = snapshot.strings,
            warnings = warnings,
            onAdjusted = ::markAdjustedInvalidValue
        )

        if (adjustedInvalidValue) {
            warnings += context.getString(R.string.config_import_warning_invalid_setting_values)
        }

        return TypedPreferenceSnapshot(
            booleans = booleans,
            floats = floats,
            ints = ints,
            longs = longs,
            strings = strings
        )
    }

    private fun sanitizeFloatSettings(
        source: Map<String, Float>,
        onAdjusted: () -> Unit
    ): LinkedHashMap<String, Float> {
        val result = linkedMapOf<String, Float>()
        source.forEach { (name, value) ->
            if (name !in SETTINGS_FLOAT_KEY_NAMES) {
                onAdjusted()
                return@forEach
            }
            if (value.isNaN() || value.isInfinite()) {
                onAdjusted()
                return@forEach
            }
            val normalized = when (name) {
                SettingsKeys.LYRIC_FONT_SCALE.name -> normalizeLyricFontScale(value)
                SettingsKeys.FLOATING_LYRICS_FONT_SIZE_SP.name -> normalizeFloatingLyricsFontSizeSp(value)
                SettingsKeys.FLOATING_LYRICS_OUTLINE_WIDTH_DP.name ->
                    normalizeFloatingLyricsOutlineWidthDp(value)
                SettingsKeys.FLOATING_LYRICS_LYRIC_ALPHA.name ->
                    normalizeFloatingLyricsAlpha(value, fallback = 1f)
                SettingsKeys.FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP.name ->
                    normalizeFloatingLyricsOutlineWidthDp(value)
                SettingsKeys.FLOATING_LYRICS_TRANSLATION_ALPHA.name ->
                    normalizeFloatingLyricsAlpha(value)
                SettingsKeys.FLOATING_LYRICS_MAX_WIDTH_DP.name -> normalizeFloatingLyricsMaxWidthDp(value)
                SettingsKeys.FLOATING_LYRICS_POSITION_X.name,
                SettingsKeys.FLOATING_LYRICS_POSITION_Y.name -> normalizeFloatingLyricsPosition(value)
                SettingsKeys.UI_DENSITY_SCALE.name -> value.coerceIn(UI_DENSITY_SCALE_RANGE)
                SettingsKeys.BACKGROUND_IMAGE_BLUR.name -> value.coerceIn(BACKGROUND_IMAGE_BLUR_RANGE)
                SettingsKeys.BACKGROUND_IMAGE_ALPHA.name -> value.coerceIn(BACKGROUND_IMAGE_ALPHA_RANGE)
                SettingsKeys.NOWPLAYING_COVER_BLUR_AMOUNT.name ->
                    value.coerceIn(NOW_PLAYING_COVER_BLUR_AMOUNT_RANGE)
                SettingsKeys.NOWPLAYING_COVER_BLUR_DARKEN.name ->
                    value.coerceIn(NOW_PLAYING_COVER_BLUR_DARKEN_RANGE)
                SettingsKeys.LYRIC_BLUR_AMOUNT.name -> value.coerceIn(LYRIC_BLUR_AMOUNT_RANGE)
                SettingsKeys.PLAYBACK_SPEED.name -> normalizePlaybackSpeed(value)
                SettingsKeys.PLAYBACK_PITCH.name -> normalizePlaybackPitch(value)
                else -> value
            }
            if (normalized != value) {
                onAdjusted()
            }
            result[name] = normalized
        }
        return result
    }

    private fun sanitizeIntSettings(
        source: Map<String, Int>,
        onAdjusted: () -> Unit
    ): LinkedHashMap<String, Int> {
        val result = linkedMapOf<String, Int>()
        source.forEach { (name, value) ->
            if (name !in SETTINGS_INT_KEY_NAMES) {
                onAdjusted()
                return@forEach
            }
            val normalized = when (name) {
                SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB.name ->
                    normalizePlaybackLoudnessGainMb(value)
                SettingsKeys.DOWNLOAD_PARALLELISM.name ->
                    normalizeDownloadParallelism(value)
                SettingsKeys.PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES.name ->
                    PlaybackServiceIdleShutdownPreference.normalize(value)
                else -> value
            }
            if (normalized != value) {
                onAdjusted()
            }
            result[name] = normalized
        }
        return result
    }

    private fun sanitizeLongSettings(
        source: Map<String, Long>,
        onAdjusted: () -> Unit
    ): LinkedHashMap<String, Long> {
        val result = linkedMapOf<String, Long>()
        source.forEach { (name, value) ->
            if (name !in SETTINGS_LONG_KEY_NAMES) {
                onAdjusted()
                return@forEach
            }
            val normalized = when (name) {
                SettingsKeys.CLOUD_MUSIC_LYRIC_DEFAULT_OFFSET_MS.name,
                SettingsKeys.QQ_MUSIC_LYRIC_DEFAULT_OFFSET_MS.name ->
                    normalizeLyricDefaultOffsetMs(value)
                SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS.name,
                SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS.name,
                SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS.name,
                SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS.name ->
                    value.coerceIn(PLAYBACK_FADE_DURATION_RANGE_MS)
                SettingsKeys.MAX_CACHE_SIZE_BYTES.name -> value.coerceIn(CACHE_SIZE_RANGE_BYTES)
                else -> value
            }
            if (normalized != value) {
                onAdjusted()
            }
            result[name] = normalized
        }
        return result
    }

    private fun sanitizeStringSettings(
        source: Map<String, String>,
        warnings: MutableList<String>,
        onAdjusted: () -> Unit
    ): LinkedHashMap<String, String> {
        val strings = filterKnownSettings(
            source = source,
            allowedNames = SETTINGS_STRING_KEY_NAMES,
            onAdjusted = onAdjusted
        )
        sanitizeQualityStrings(strings, onAdjusted)
        sanitizePlaybackStrings(strings, onAdjusted)
        sanitizeThemeStrings(strings, onAdjusted)
        sanitizeFloatingLyricsStrings(strings, onAdjusted)
        sanitizeBackgroundImageUri(strings, warnings, onAdjusted)
        sanitizeDownloadDirectory(strings, warnings, onAdjusted)
        return strings
    }

    private fun sanitizeQualityStrings(
        strings: MutableMap<String, String>,
        onAdjusted: () -> Unit
    ) {
        sanitizeStringValue(strings, SettingsKeys.AUDIO_QUALITY.name, onAdjusted) {
            normalizeChoice(it, NETEASE_AUDIO_QUALITY_VALUES, DEFAULT_NETEASE_AUDIO_QUALITY)
        }
        sanitizeStringValue(strings, SettingsKeys.YOUTUBE_AUDIO_QUALITY.name, onAdjusted) {
            normalizeChoice(it, YOUTUBE_AUDIO_QUALITY_VALUES, DEFAULT_YOUTUBE_AUDIO_QUALITY)
        }
        sanitizeStringValue(strings, SettingsKeys.BILI_AUDIO_QUALITY.name, onAdjusted) {
            normalizeChoice(it, BILI_AUDIO_QUALITY_VALUES, DEFAULT_BILI_AUDIO_QUALITY)
        }
        sanitizeStringValue(strings, SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE.name, onAdjusted) {
            normalizeDownloadFileNameTemplate(it)
        }
        sanitizeStringValue(strings, SettingsKeys.DEFAULT_START_DESTINATION.name, onAdjusted) {
            normalizeChoice(it, DEFAULT_START_DESTINATION_ROUTES, DEFAULT_START_DESTINATION_ROUTE)
        }
    }

    private fun sanitizePlaybackStrings(
        strings: MutableMap<String, String>,
        onAdjusted: () -> Unit
    ) {
        sanitizeStringValue(strings, SettingsKeys.PLAYBACK_EQUALIZER_PRESET.name, onAdjusted) {
            normalizePlaybackEqualizerPreset(it)
        }
        sanitizeStringValue(
            strings,
            SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS.name,
            onAdjusted,
            ::normalizePlaybackEqualizerBandLevels
        )
    }

    private fun sanitizeThemeStrings(
        strings: MutableMap<String, String>,
        onAdjusted: () -> Unit
    ) {
        sanitizeStringValue(strings, SettingsKeys.THEME_SEED_COLOR.name, onAdjusted) {
            normalizeConfigHex(it) ?: ThemeDefaults.DEFAULT_SEED_COLOR_HEX
        }
        sanitizeStringValue(
            strings,
            SettingsKeys.THEME_COLOR_PALETTE.name,
            onAdjusted,
            ::normalizeThemeColorPalette
        )
        sanitizeStringValue(strings, SettingsKeys.THEME_PALETTE_STYLE.name, onAdjusted) {
            ThemeDefaults.normalizePaletteStyle(it)
        }
        sanitizeStringValue(strings, SettingsKeys.THEME_COLOR_SPEC.name, onAdjusted) {
            ThemeDefaults.normalizeColorSpec(it)
        }
    }

    private fun sanitizeFloatingLyricsStrings(
        strings: MutableMap<String, String>,
        onAdjusted: () -> Unit
    ) {
        sanitizeStringValue(strings, SettingsKeys.FLOATING_LYRICS_TEXT_COLOR.name, onAdjusted) {
            normalizeFloatingLyricsColorHex(it)
        }
        sanitizeStringValue(strings, SettingsKeys.FLOATING_LYRICS_OUTLINE_COLOR.name, onAdjusted) {
            normalizeFloatingLyricsColorHex(it)
        }
        sanitizeStringValue(strings, SettingsKeys.FLOATING_LYRICS_ALIGNMENT.name, onAdjusted) {
            normalizeFloatingLyricsAlignment(it)
        }
    }

    private fun sanitizeBackgroundImageUri(
        strings: MutableMap<String, String>,
        warnings: MutableList<String>,
        onAdjusted: () -> Unit
    ) {
        val backgroundImageUri = strings[SettingsKeys.BACKGROUND_IMAGE_URI.name]
        if (backgroundImageUri != null && backgroundImageUri.isBlank()) {
            strings.remove(SettingsKeys.BACKGROUND_IMAGE_URI.name)
            onAdjusted()
        }
        if (!backgroundImageUri.isNullOrBlank() && !canAccessImportedContentUri(backgroundImageUri)) {
            strings.remove(SettingsKeys.BACKGROUND_IMAGE_URI.name)
            warnings += context.getString(R.string.config_import_warning_background_image)
        }
    }

    private fun sanitizeDownloadDirectory(
        strings: MutableMap<String, String>,
        warnings: MutableList<String>,
        onAdjusted: () -> Unit
    ) {
        val downloadDirectoryKey = SettingsKeys.DOWNLOAD_DIRECTORY_URI.name
        val downloadDirectoryLabelKey = SettingsKeys.DOWNLOAD_DIRECTORY_LABEL.name
        val downloadDirectoryUri = strings[downloadDirectoryKey]
        val normalizedDownloadDirectoryUri = ManagedDownloadStorage.canonicalizeDirectoryUri(
            downloadDirectoryUri
        )
        when {
            downloadDirectoryUri == null -> Unit
            normalizedDownloadDirectoryUri.isNullOrBlank() -> {
                strings.remove(downloadDirectoryKey)
                strings.remove(downloadDirectoryLabelKey)
                onAdjusted()
            }
            !hasPersistedTreeAccess(normalizedDownloadDirectoryUri) -> {
                strings.remove(downloadDirectoryKey)
                strings.remove(downloadDirectoryLabelKey)
                warnings += context.getString(R.string.config_import_warning_download_directory)
            }
            normalizedDownloadDirectoryUri != downloadDirectoryUri -> {
                strings[downloadDirectoryKey] = normalizedDownloadDirectoryUri
                onAdjusted()
            }
        }

        val downloadDirectoryLabel = strings[downloadDirectoryLabelKey]
        if (downloadDirectoryLabel != null && downloadDirectoryLabel.isBlank()) {
            strings.remove(downloadDirectoryLabelKey)
            onAdjusted()
        }

        if (strings[downloadDirectoryKey].isNullOrBlank() && strings.containsKey(downloadDirectoryLabelKey)) {
            strings.remove(downloadDirectoryKey)
            strings.remove(downloadDirectoryLabelKey)
            onAdjusted()
        }
    }

    private fun canAccessImportedContentUri(uriString: String): Boolean {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun hasPersistedTreeAccess(uriString: String): Boolean {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && (permission.isReadPermission || permission.isWritePermission)
        }
        if (!hasPersistedPermission) {
            return false
        }
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)?.exists() == true
        }.getOrDefault(false)
    }
}

internal val SETTINGS_BOOLEAN_KEYS = listOf(
    AutoSettingsBackupKeys.booleanKeys
).flatten()

internal val SETTINGS_FLOAT_KEYS = listOf(
    AutoSettingsBackupKeys.floatKeys
).flatten()

internal val SETTINGS_INT_KEYS = listOf(
    AutoSettingsBackupKeys.intKeys
).flatten()

internal val SETTINGS_LONG_KEYS = listOf(
    AutoSettingsBackupKeys.longKeys
).flatten()

internal val SETTINGS_STRING_KEYS = listOf(
    AutoSettingsBackupKeys.stringKeys
).flatten()

private val SETTINGS_BOOLEAN_KEY_NAMES = SETTINGS_BOOLEAN_KEYS.map { it.name }.toSet()
private val SETTINGS_FLOAT_KEY_NAMES = SETTINGS_FLOAT_KEYS.map { it.name }.toSet()
private val SETTINGS_INT_KEY_NAMES = SETTINGS_INT_KEYS.map { it.name }.toSet()
private val SETTINGS_LONG_KEY_NAMES = SETTINGS_LONG_KEYS.map { it.name }.toSet()
private val SETTINGS_STRING_KEY_NAMES = SETTINGS_STRING_KEYS.map { it.name }.toSet()

private val UI_DENSITY_SCALE_RANGE = 0.6f..1.2f
private val BACKGROUND_IMAGE_BLUR_RANGE = 0f..25f
private val BACKGROUND_IMAGE_ALPHA_RANGE = 0.1f..1.0f
private val NOW_PLAYING_COVER_BLUR_AMOUNT_RANGE = 0f..500f
private val NOW_PLAYING_COVER_BLUR_DARKEN_RANGE = 0f..0.8f
private val LYRIC_BLUR_AMOUNT_RANGE = 0f..8f
private val PLAYBACK_FADE_DURATION_RANGE_MS = 0L..3000L
private val CACHE_SIZE_RANGE_BYTES = 0L..(10L * 1024L * 1024L * 1024L)

private const val DEFAULT_NETEASE_AUDIO_QUALITY = "exhigh"
private const val DEFAULT_YOUTUBE_AUDIO_QUALITY = "very_high"
private const val DEFAULT_BILI_AUDIO_QUALITY = "high"
private const val DEFAULT_START_DESTINATION_ROUTE = "home"
private const val MAX_THEME_PALETTE_COLORS = 64
private const val MAX_EQUALIZER_BAND_LEVELS = 32

private val NETEASE_AUDIO_QUALITY_VALUES = setOf(
    "standard",
    "higher",
    DEFAULT_NETEASE_AUDIO_QUALITY,
    "lossless",
    "hires",
    "jyeffect",
    "sky",
    "jymaster"
)
private val YOUTUBE_AUDIO_QUALITY_VALUES = setOf(
    "low",
    "medium",
    "high",
    DEFAULT_YOUTUBE_AUDIO_QUALITY
)
private val BILI_AUDIO_QUALITY_VALUES = setOf(
    "dolby",
    "hires",
    "lossless",
    DEFAULT_BILI_AUDIO_QUALITY,
    "medium",
    "low"
)
private val DEFAULT_START_DESTINATION_ROUTES = setOf(
    "home",
    "explore",
    "library",
    "settings"
)
private val PLAYBACK_EQUALIZER_PRESET_IDS =
    PlaybackEqualizerPresets.map { it.id }.toSet() + PlaybackEqualizerPresetId.CUSTOM
private val HEX_COLOR_REGEX = Regex("^[0-9A-F]{6}$")

private fun <T> filterKnownSettings(
    source: Map<String, T>,
    allowedNames: Set<String>,
    onAdjusted: () -> Unit
): LinkedHashMap<String, T> {
    val result = linkedMapOf<String, T>()
    source.forEach { (name, value) ->
        if (name in allowedNames) {
            result[name] = value
        } else {
            onAdjusted()
        }
    }
    return result
}

private fun sanitizeStringValue(
    strings: MutableMap<String, String>,
    keyName: String,
    onAdjusted: () -> Unit,
    normalize: (String) -> String?
) {
    val raw = strings[keyName] ?: return
    val normalized = normalize(raw)
    if (normalized.isNullOrBlank()) {
        strings.remove(keyName)
        onAdjusted()
        return
    }
    if (normalized != raw) {
        onAdjusted()
    }
    strings[keyName] = normalized
}

private fun normalizeChoice(
    value: String,
    allowedValues: Set<String>,
    defaultValue: String
): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
    return normalized.takeIf { it in allowedValues } ?: defaultValue
}

private fun normalizePlaybackEqualizerPreset(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
    return normalized.takeIf { it in PLAYBACK_EQUALIZER_PRESET_IDS }
        ?: PlaybackEqualizerPresetId.FLAT
}

private fun normalizePlaybackEqualizerBandLevels(value: String): String? {
    val rawLevels = decodePlaybackEqualizerBandLevels(value)
    if (rawLevels.isEmpty()) {
        return null
    }
    val normalizedLevels = rawLevels
        .take(MAX_EQUALIZER_BAND_LEVELS)
        .map { level ->
            level.coerceIn(
                minimumValue = DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB.first,
                maximumValue = DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB.last
            )
        }
    return encodePlaybackEqualizerBandLevels(normalizedLevels)
}

private fun normalizeConfigHex(candidate: String): String? {
    val normalized = candidate.trim().removePrefix("#").uppercase(Locale.ROOT)
    return normalized.takeIf { HEX_COLOR_REGEX.matches(it) }
}

private fun normalizeThemeColorPalette(value: String): String? {
    val colors = value.split(',')
        .mapNotNull(::normalizeConfigHex)
        .distinct()
        .take(MAX_THEME_PALETTE_COLORS)
    return colors.takeIf { it.isNotEmpty() }?.joinToString(",")
}
