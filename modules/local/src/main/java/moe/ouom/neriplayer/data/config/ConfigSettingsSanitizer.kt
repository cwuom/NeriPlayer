package moe.ouom.neriplayer.data.config

import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.policy.settings.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.policy.settings.normalizeDownloadParallelism
import moe.ouom.neriplayer.data.model.playback.DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettingsCodec
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresets
import moe.ouom.neriplayer.data.model.playback.decodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.data.model.playback.encodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackPitch
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackSpeed
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackVolumeBalance
import moe.ouom.neriplayer.data.settings.playback.PlaybackServiceIdleShutdownPreference
import moe.ouom.neriplayer.data.settings.storage.CacheSizePolicy
import moe.ouom.neriplayer.data.settings.lyrics.LyricSourcePreferencePolicy
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.platform.youtube.settings.YouTubePlaybackSourcePreferencePolicy
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsBackupKeys
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsAlignment
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsAlpha
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsColorHex
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsFontSizeSp
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsMaxWidthDp
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsOutlineWidthDp
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsPosition
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsRenderStyle
import moe.ouom.neriplayer.lyrics.offset.normalizeLyricDefaultOffsetMs
import moe.ouom.neriplayer.data.settings.lyrics.normalizeLyricFontScale
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
            warnings += context.getString(CoreCommonR.string.config_import_warning_invalid_setting_values)
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
            if (name !in SETTINGS_FLOAT_KEY_NAMES || !value.isFinite()) {
                onAdjusted()
                return@forEach
            }
            val normalized = FLOAT_SETTING_NORMALIZERS[name]?.invoke(value) ?: value
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
            val normalized = LONG_SETTING_NORMALIZERS[name]?.invoke(value) ?: value
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
        sanitizeStringValue(strings, SettingsKeys.YOUTUBE_PLAYBACK_SOURCE.name, onAdjusted) {
            YouTubePlaybackSourcePreferencePolicy.normalize(it)
        }
        sanitizeStringValue(strings, SettingsKeys.DEFAULT_LYRIC_SOURCE.name, onAdjusted) {
            LyricSourcePreferencePolicy.normalize(it)
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
        sanitizeStringValue(strings, SettingsKeys.AUDIO_EFFECTS_SETTINGS.name, onAdjusted) { raw ->
            AudioEffectsSettingsCodec.decodeOrNull(raw)?.let(AudioEffectsSettingsCodec::encode)
        }
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
        sanitizeStringValue(strings, SettingsKeys.FLOATING_LYRICS_RENDER_STYLE.name, onAdjusted) {
            normalizeFloatingLyricsRenderStyle(it)
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
            warnings += context.getString(CoreCommonR.string.config_import_warning_background_image)
        }
    }

    private fun sanitizeDownloadDirectory(
        strings: MutableMap<String, String>,
        warnings: MutableList<String>,
        onAdjusted: () -> Unit
    ) {
        strings[DOWNLOAD_DIRECTORY_KEY]?.let { importDownloadDirectory(strings, it, warnings, onAdjusted) }
        dropUnusableDownloadDirectoryLabel(strings, onAdjusted)
    }

    private fun importDownloadDirectory(
        strings: MutableMap<String, String>,
        rawDirectoryUri: String,
        warnings: MutableList<String>,
        onAdjusted: () -> Unit
    ) {
        val normalized = ManagedDownloadDirectoryIdentity.normalizeConfiguredDirectoryUri(rawDirectoryUri)
        if (normalized.isNullOrBlank()) {
            strings.removeDownloadDirectory()
            onAdjusted()
            return
        }
        val access = inspectPersistedTreeAccess(normalized)
        when {
            shouldClearImportedDownloadDirectory(access) -> {
                strings.removeDownloadDirectory()
                warnings += context.getString(CoreCommonR.string.config_import_warning_download_directory)
            }
            shouldWarnImportedDownloadDirectory(access) -> {
                warnings += context.getString(CoreCommonR.string.config_import_warning_download_directory)
            }
            normalized != rawDirectoryUri -> {
                strings[DOWNLOAD_DIRECTORY_KEY] = normalized
                onAdjusted()
            }
        }
    }

    private fun dropUnusableDownloadDirectoryLabel(
        strings: MutableMap<String, String>,
        onAdjusted: () -> Unit
    ) {
        val downloadDirectoryLabel = strings[DOWNLOAD_DIRECTORY_LABEL_KEY]
        if (downloadDirectoryLabel != null && downloadDirectoryLabel.isBlank()) {
            strings.remove(DOWNLOAD_DIRECTORY_LABEL_KEY)
            onAdjusted()
        }

        if (strings[DOWNLOAD_DIRECTORY_KEY].isNullOrBlank() && strings.containsKey(DOWNLOAD_DIRECTORY_LABEL_KEY)) {
            strings.removeDownloadDirectory()
            onAdjusted()
        }
    }

    private fun canAccessImportedContentUri(uriString: String): Boolean {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun inspectPersistedTreeAccess(uriString: String): PersistedTreeAccess {
        val uri = runCatching { uriString.toUri() }.getOrNull()
            ?: return PersistedTreeAccess.NoPersistedPermission
        if (!hasPersistedTreePermission(uri)) {
            return PersistedTreeAccess.NoPersistedPermission
        }
        return persistedTreeAccessOf(ManagedDownloadReferenceIo.inspectDirectory(context, uri))
    }

    private fun hasPersistedTreePermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && (permission.isReadPermission || permission.isWritePermission)
        }
}

internal fun persistedTreeAccessOf(result: ManagedDownloadReferenceIo.AccessResult): PersistedTreeAccess =
    when (result) {
        ManagedDownloadReferenceIo.AccessResult.Accessible -> PersistedTreeAccess.Accessible
        ManagedDownloadReferenceIo.AccessResult.Missing -> PersistedTreeAccess.Missing
        ManagedDownloadReferenceIo.AccessResult.PermissionLost -> PersistedTreeAccess.PermissionLost
        is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> PersistedTreeAccess.ProviderFailure
    }

internal enum class PersistedTreeAccess {
    Accessible,
    Missing,
    NoPersistedPermission,
    PermissionLost,
    ProviderFailure
}

internal fun shouldClearImportedDownloadDirectory(access: PersistedTreeAccess): Boolean {
    return access == PersistedTreeAccess.NoPersistedPermission ||
        access == PersistedTreeAccess.Missing
}

internal fun shouldWarnImportedDownloadDirectory(access: PersistedTreeAccess): Boolean {
    return access == PersistedTreeAccess.PermissionLost ||
        access == PersistedTreeAccess.ProviderFailure
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
private val DOWNLOAD_DIRECTORY_KEY = SettingsKeys.DOWNLOAD_DIRECTORY_URI.name
private val DOWNLOAD_DIRECTORY_LABEL_KEY = SettingsKeys.DOWNLOAD_DIRECTORY_LABEL.name

private val FLOAT_SETTING_NORMALIZERS: Map<String, (Float) -> Float> = buildMap {
    listOf(
        SettingsKeys.LYRIC_FONT_SCALE,
        SettingsKeys.NOWPLAYING_COVER_LYRIC_FONT_SCALE,
        SettingsKeys.NOWPLAYING_COVER_TRANSLATION_FONT_SCALE,
        SettingsKeys.LYRICS_PAGE_LYRIC_FONT_SCALE,
        SettingsKeys.LYRICS_PAGE_TRANSLATION_FONT_SCALE
    ).forEach { key -> put(key.name) { normalizeLyricFontScale(it) } }
    listOf(
        SettingsKeys.FLOATING_LYRICS_POSITION_X,
        SettingsKeys.FLOATING_LYRICS_POSITION_Y,
        SettingsKeys.FLOATING_LYRICS_LANDSCAPE_POSITION_X,
        SettingsKeys.FLOATING_LYRICS_LANDSCAPE_POSITION_Y
    ).forEach { key -> put(key.name) { normalizeFloatingLyricsPosition(it) } }
    put(SettingsKeys.FLOATING_LYRICS_FONT_SIZE_SP.name) { normalizeFloatingLyricsFontSizeSp(it) }
    put(SettingsKeys.FLOATING_LYRICS_OUTLINE_WIDTH_DP.name) { normalizeFloatingLyricsOutlineWidthDp(it) }
    put(SettingsKeys.FLOATING_LYRICS_LYRIC_ALPHA.name) { normalizeFloatingLyricsAlpha(it, fallback = 1f) }
    put(SettingsKeys.FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP.name) { normalizeFloatingLyricsOutlineWidthDp(it) }
    put(SettingsKeys.FLOATING_LYRICS_TRANSLATION_ALPHA.name) { normalizeFloatingLyricsAlpha(it) }
    put(SettingsKeys.FLOATING_LYRICS_MAX_WIDTH_DP.name) { normalizeFloatingLyricsMaxWidthDp(it) }
    put(SettingsKeys.UI_DENSITY_SCALE.name) { it.coerceIn(UI_DENSITY_SCALE_RANGE) }
    put(SettingsKeys.BACKGROUND_IMAGE_BLUR.name) { it.coerceIn(BACKGROUND_IMAGE_BLUR_RANGE) }
    put(SettingsKeys.BACKGROUND_IMAGE_ALPHA.name) { it.coerceIn(BACKGROUND_IMAGE_ALPHA_RANGE) }
    put(SettingsKeys.NOWPLAYING_COVER_BLUR_AMOUNT.name) { it.coerceIn(NOW_PLAYING_COVER_BLUR_AMOUNT_RANGE) }
    put(SettingsKeys.NOWPLAYING_COVER_BLUR_DARKEN.name) { it.coerceIn(NOW_PLAYING_COVER_BLUR_DARKEN_RANGE) }
    put(SettingsKeys.LYRIC_BLUR_AMOUNT.name) { it.coerceIn(LYRIC_BLUR_AMOUNT_RANGE) }
    put(SettingsKeys.PLAYBACK_SPEED.name) { normalizePlaybackSpeed(it) }
    put(SettingsKeys.PLAYBACK_PITCH.name) { normalizePlaybackPitch(it) }
    put(SettingsKeys.PLAYBACK_VOLUME_BALANCE.name) { normalizePlaybackVolumeBalance(it) }
}

private val LONG_SETTING_NORMALIZERS: Map<String, (Long) -> Long> = buildMap {
    listOf(
        SettingsKeys.CLOUD_MUSIC_LYRIC_DEFAULT_OFFSET_MS,
        SettingsKeys.QQ_MUSIC_LYRIC_DEFAULT_OFFSET_MS,
        SettingsKeys.KUGOU_LYRIC_DEFAULT_OFFSET_MS,
        SettingsKeys.LRCLIB_LYRIC_DEFAULT_OFFSET_MS,
        SettingsKeys.AMLL_TTML_LYRIC_DEFAULT_OFFSET_MS
    ).forEach { key -> put(key.name) { normalizeLyricDefaultOffsetMs(it) } }
    listOf(
        SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS,
        SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS,
        SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS,
        SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS
    ).forEach { key -> put(key.name) { it.coerceIn(PLAYBACK_FADE_DURATION_RANGE_MS) } }
    put(SettingsKeys.MAX_CACHE_SIZE_BYTES.name) { CacheSizePolicy.normalizeCacheSizeBytes(it) }
}
private const val DEFAULT_NETEASE_AUDIO_QUALITY = "exhigh"
private const val DEFAULT_YOUTUBE_AUDIO_QUALITY = "high"
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
    "very_high",
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

private fun MutableMap<String, String>.removeDownloadDirectory() {
    remove(DOWNLOAD_DIRECTORY_KEY)
    remove(DOWNLOAD_DIRECTORY_LABEL_KEY)
}

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
