package moe.ouom.neriplayer.data

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data/SettingsDataStore
 * Created: 2025/8/8
 */


import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

private val Context.dataStore by preferencesDataStore("settings")

object SettingsKeys {
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val FORCE_DARK = booleanPreferencesKey("force_dark")
    val FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
    val SHOW_COVER_SOURCE_BADGE = booleanPreferencesKey("show_cover_source_badge")
    val SILENT_GITHUB_SYNC_FAILURE = booleanPreferencesKey("silent_github_sync_failure")
    val DISCLAIMER_ACCEPTED_V2 = booleanPreferencesKey("disclaimer_accepted_v2")
    val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    val YOUTUBE_AUDIO_QUALITY = stringPreferencesKey("youtube_audio_quality")
    val BILI_AUDIO_QUALITY = stringPreferencesKey("bili_audio_quality")
    val KEY_DEV_MODE = booleanPreferencesKey("dev_mode_enabled")
    val THEME_SEED_COLOR = stringPreferencesKey("theme_seed_color")
    val THEME_COLOR_PALETTE = stringPreferencesKey("theme_color_palette_v2")
    val LYRIC_BLUR_ENABLED = booleanPreferencesKey("lyric_blur_enabled")
    val LYRIC_BLUR_AMOUNT = floatPreferencesKey("lyric_blur_amount")
    val ADVANCED_BLUR_ENABLED = booleanPreferencesKey("advanced_blur_enabled")
    val NOWPLAYING_AUDIO_REACTIVE_ENABLED = booleanPreferencesKey("nowplaying_audio_reactive_enabled")
    val NOWPLAYING_DYNAMIC_BACKGROUND_ENABLED = booleanPreferencesKey("nowplaying_dynamic_background_enabled")
    val NOWPLAYING_COVER_BLUR_BACKGROUND_ENABLED =
        booleanPreferencesKey("nowplaying_cover_blur_background_enabled")
    val NOWPLAYING_COVER_BLUR_AMOUNT = floatPreferencesKey("nowplaying_cover_blur_amount")
    val NOWPLAYING_COVER_BLUR_DARKEN = floatPreferencesKey("nowplaying_cover_blur_darken")
    val LYRIC_FONT_SCALE = floatPreferencesKey("lyric_font_scale")
    val UI_DENSITY_SCALE = floatPreferencesKey("ui_density_scale")
    val BYPASS_PROXY = booleanPreferencesKey("bypass_proxy")
    val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
    val BACKGROUND_IMAGE_BLUR = floatPreferencesKey("background_image_blur")
    val BACKGROUND_IMAGE_ALPHA = floatPreferencesKey("background_image_alpha")
    val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
    val MAX_CACHE_SIZE_BYTES = longPreferencesKey("max_cache_size_bytes")
    val SLEEP_TIMER_ENABLED = booleanPreferencesKey("sleep_timer_enabled")
    val SLEEP_TIMER_MODE = stringPreferencesKey("sleep_timer_mode")
    val SLEEP_TIMER_MINUTES = longPreferencesKey("sleep_timer_minutes")
    val SHOW_LYRIC_TRANSLATION = booleanPreferencesKey("show_lyric_translation")
    val DEFAULT_START_DESTINATION = stringPreferencesKey("default_start_destination")
    val AUTO_SHOW_KEYBOARD = booleanPreferencesKey("auto_show_keyboard")
    val HOME_CARD_CONTINUE = booleanPreferencesKey("home_card_continue")
    val HOME_CARD_TRENDING = booleanPreferencesKey("home_card_trending")
    val HOME_CARD_RADAR = booleanPreferencesKey("home_card_radar")
    val HOME_CARD_RECOMMENDED = booleanPreferencesKey("home_card_recommended")
    val PLAYBACK_FADE_IN = booleanPreferencesKey("playback_fade_in")
    val PLAYBACK_CROSSFADE_NEXT = booleanPreferencesKey("playback_crossfade_next")
    val PLAYBACK_FADE_IN_DURATION_MS = longPreferencesKey("playback_fade_in_duration_ms")
    val PLAYBACK_FADE_OUT_DURATION_MS = longPreferencesKey("playback_fade_out_duration_ms")
    val PLAYBACK_CROSSFADE_IN_DURATION_MS = longPreferencesKey("playback_crossfade_in_duration_ms")
    val PLAYBACK_CROSSFADE_OUT_DURATION_MS = longPreferencesKey("playback_crossfade_out_duration_ms")
    val KEEP_LAST_PLAYBACK_PROGRESS = booleanPreferencesKey("keep_last_playback_progress")
    val KEEP_PLAYBACK_MODE_STATE = booleanPreferencesKey("keep_playback_mode_state")
    val STOP_ON_BLUETOOTH_DISCONNECT = booleanPreferencesKey("stop_on_bluetooth_disconnect")
    val ALLOW_MIXED_PLAYBACK = booleanPreferencesKey("allow_mixed_playback")
}


object ThemeDefaults {
    const val DEFAULT_SEED_COLOR_HEX = "0061A4"
    val PRESET_COLORS = listOf(
        "0061A4",
        "6750A4",
        "B3261E",
        "C425A8",
        "00897B",
        "388E3C",
        "FBC02D",
        "E65100",
    )
    val PRESET_SET = PRESET_COLORS.map { it.uppercase(Locale.ROOT) }.toSet()
}

data class ThemePreferenceSnapshot(
    val dynamicColor: Boolean = true,
    val forceDark: Boolean = false,
    val followSystemDark: Boolean = true
) {
    fun resolveUseDark(systemDark: Boolean): Boolean {
        return when {
            forceDark -> true
            followSystemDark -> systemDark
            else -> false
        }
    }
}

fun readThemePreferenceSnapshotSync(context: Context): ThemePreferenceSnapshot {
    return runBlocking {
        val prefs = context.dataStore.data.first()
        ThemePreferenceSnapshot(
            dynamicColor = prefs[SettingsKeys.DYNAMIC_COLOR] ?: true,
            forceDark = prefs[SettingsKeys.FORCE_DARK] ?: false,
            followSystemDark = prefs[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true
        )
    }
}

class SettingsRepository(private val context: Context) {
    val dynamicColorFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.DYNAMIC_COLOR] ?: true }

    val forceDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FORCE_DARK] ?: false }

    val followSystemDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true }

    val showCoverSourceBadgeFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.SHOW_COVER_SOURCE_BADGE] ?: true }

    val silentGitHubSyncFailureFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.SILENT_GITHUB_SYNC_FAILURE] ?: false }

    val audioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.AUDIO_QUALITY] ?: "exhigh" }

    val youtubeAudioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.YOUTUBE_AUDIO_QUALITY] ?: "very_high" }

    val biliAudioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.BILI_AUDIO_QUALITY] ?: "high" }

    val devModeEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.KEY_DEV_MODE] ?: false }

    val themeSeedColorFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.THEME_SEED_COLOR] ?: ThemeDefaults.DEFAULT_SEED_COLOR_HEX }

    val themeColorPaletteFlow: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            parseColorPalette(prefs[SettingsKeys.THEME_COLOR_PALETTE])
        }

    val lyricBlurEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.LYRIC_BLUR_ENABLED] ?: true }

    val lyricBlurAmountFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.LYRIC_BLUR_AMOUNT] ?: 1.5f }

    val advancedBlurEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.ADVANCED_BLUR_ENABLED] ?: true }

    val nowPlayingAudioReactiveEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_AUDIO_REACTIVE_ENABLED] ?: true }

    val nowPlayingDynamicBackgroundEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_DYNAMIC_BACKGROUND_ENABLED] ?: true }

    val nowPlayingCoverBlurBackgroundEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_COVER_BLUR_BACKGROUND_ENABLED] ?: false }

    val nowPlayingCoverBlurAmountFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_COVER_BLUR_AMOUNT] ?: 1.5f }

    val nowPlayingCoverBlurDarkenFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_COVER_BLUR_DARKEN] ?: 0.2f }

    val lyricFontScaleFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.LYRIC_FONT_SCALE] ?: 1.0f }

    val uiDensityScaleFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.UI_DENSITY_SCALE] ?: 1.0f }

    val bypassProxyFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.BYPASS_PROXY] ?: true }

    val backgroundImageUriFlow: Flow<String?> =
        context.dataStore.data.map { it[SettingsKeys.BACKGROUND_IMAGE_URI] }

    val backgroundImageBlurFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.BACKGROUND_IMAGE_BLUR] ?: 0f }

    val backgroundImageAlphaFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.BACKGROUND_IMAGE_ALPHA] ?: 0.3f }

    val hapticFeedbackEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.HAPTIC_FEEDBACK_ENABLED] ?: true }

    val disclaimerAcceptedFlow: Flow<Boolean?> =
        flow {
            emit(null) // 加载态
            val realFlow: Flow<Boolean> =
                context.dataStore.data.map { prefs ->
                    prefs[SettingsKeys.DISCLAIMER_ACCEPTED_V2] ?: false
                }
            emitAll(realFlow)
        }

    val maxCacheSizeBytesFlow: Flow<Long> =
        context.dataStore.data.map { it[SettingsKeys.MAX_CACHE_SIZE_BYTES] ?: (1024L * 1024 * 1024) }

    val showLyricTranslationFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.SHOW_LYRIC_TRANSLATION] ?: true }

    val defaultStartDestinationFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.DEFAULT_START_DESTINATION] ?: "home" }

    val autoShowKeyboardFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.AUTO_SHOW_KEYBOARD] ?: false }

    val homeCardContinueFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.HOME_CARD_CONTINUE] ?: true }

    val homeCardTrendingFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.HOME_CARD_TRENDING] ?: true }

    val homeCardRadarFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.HOME_CARD_RADAR] ?: true }

    val homeCardRecommendedFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.HOME_CARD_RECOMMENDED] ?: true }

    val playbackFadeInFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_FADE_IN] ?: false }

    val playbackCrossfadeNextFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_CROSSFADE_NEXT] ?: false }

    val playbackFadeInDurationMsFlow: Flow<Long> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS] ?: 500L }

    val playbackFadeOutDurationMsFlow: Flow<Long> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS] ?: 500L }

    val playbackCrossfadeInDurationMsFlow: Flow<Long> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS] ?: 500L }

    val playbackCrossfadeOutDurationMsFlow: Flow<Long> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS] ?: 500L }

    val keepLastPlaybackProgressFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] ?: true }

    val keepPlaybackModeStateFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] ?: true }

    val stopOnBluetoothDisconnectFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] ?: true }

    val allowMixedPlaybackFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.ALLOW_MIXED_PLAYBACK] ?: false }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DYNAMIC_COLOR] = value }
    }

    suspend fun setForceDark(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.FORCE_DARK] = value }
    }

    suspend fun setFollowSystemDark(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.FOLLOW_SYSTEM_DARK] = value }
    }

    suspend fun setShowCoverSourceBadge(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SHOW_COVER_SOURCE_BADGE] = enabled }
    }

    suspend fun setSilentGitHubSyncFailure(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SILENT_GITHUB_SYNC_FAILURE] = enabled }
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DISCLAIMER_ACCEPTED_V2] = accepted }
    }

    suspend fun setAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.AUDIO_QUALITY] = value }
    }

    suspend fun setYouTubeAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.YOUTUBE_AUDIO_QUALITY] = value }
    }

    suspend fun setBiliAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.BILI_AUDIO_QUALITY] = value }
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.KEY_DEV_MODE] = enabled }
    }

    suspend fun setThemeSeedColor(hex: String) {
        context.dataStore.edit { it[SettingsKeys.THEME_SEED_COLOR] = hex }
    }


    suspend fun addThemePaletteColor(hex: String) {
        val normalized = normalizeHex(hex) ?: return
        if (ThemeDefaults.PRESET_SET.contains(normalized)) return  // 预设不可 新增/覆盖
        updateThemePalette { current ->
            if (current.any { it.equals(normalized, ignoreCase = true) }) current else current + normalized
        }
    }

    suspend fun removeThemePaletteColor(hex: String) {
        val normalized = normalizeHex(hex) ?: return
        if (ThemeDefaults.PRESET_SET.contains(normalized)) return  // 预设不可删除
        updateThemePalette { current ->
            current.filterNot { it.equals(normalized, ignoreCase = true) }
        }
    }

    private fun mergePresetAndCustom(customs: List<String>): List<String> {
        val customClean = customs
            .mapNotNull(::normalizeHex)
            .map { it.uppercase(Locale.ROOT) }
            .filterNot { ThemeDefaults.PRESET_SET.contains(it) }
            .distinct()
        return ThemeDefaults.PRESET_COLORS + customClean
    }

    private suspend fun updateThemePalette(transform: (List<String>) -> List<String>) {
        context.dataStore.edit { prefs ->
            val current = parseColorPalette(prefs[SettingsKeys.THEME_COLOR_PALETTE])
            val updated = transform(current)

            val final = mergePresetAndCustom(updated)

            val hasCustom = final.any { !ThemeDefaults.PRESET_SET.contains(it.uppercase(Locale.ROOT)) }
            if (!hasCustom) {
                prefs.remove(SettingsKeys.THEME_COLOR_PALETTE)
            } else {
                prefs[SettingsKeys.THEME_COLOR_PALETTE] = final.joinToString(",")
            }
        }
    }

    suspend fun setLyricBlurEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.LYRIC_BLUR_ENABLED] = enabled }
    }

    suspend fun setLyricBlurAmount(amount: Float) {
        context.dataStore.edit { it[SettingsKeys.LYRIC_BLUR_AMOUNT] = amount }
    }

    suspend fun setAdvancedBlurEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.ADVANCED_BLUR_ENABLED] = enabled }
    }

    suspend fun setNowPlayingAudioReactiveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_AUDIO_REACTIVE_ENABLED] = enabled }
    }

    suspend fun setNowPlayingDynamicBackgroundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_DYNAMIC_BACKGROUND_ENABLED] = enabled }
    }

    suspend fun setNowPlayingCoverBlurBackgroundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_COVER_BLUR_BACKGROUND_ENABLED] = enabled }
    }

    suspend fun setNowPlayingCoverBlurAmount(amount: Float) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_COVER_BLUR_AMOUNT] = amount }
    }

    suspend fun setNowPlayingCoverBlurDarken(amount: Float) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_COVER_BLUR_DARKEN] = amount }
    }

    suspend fun setLyricFontScale(scale: Float) {
        context.dataStore.edit { it[SettingsKeys.LYRIC_FONT_SCALE] = scale }
    }

    suspend fun setUiDensityScale(scale: Float) {
        context.dataStore.edit { it[SettingsKeys.UI_DENSITY_SCALE] = scale }
    }

    suspend fun setBypassProxy(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.BYPASS_PROXY] = enabled }
    }

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.HAPTIC_FEEDBACK_ENABLED] = enabled }
    }

    suspend fun setBackgroundImageUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) {
                it.remove(SettingsKeys.BACKGROUND_IMAGE_URI)
            } else {
                it[SettingsKeys.BACKGROUND_IMAGE_URI] = uri
            }
        }
    }

    suspend fun setBackgroundImageBlur(blur: Float) {
        context.dataStore.edit { it[SettingsKeys.BACKGROUND_IMAGE_BLUR] = blur }
    }

    suspend fun setBackgroundImageAlpha(alpha: Float) {
        context.dataStore.edit { it[SettingsKeys.BACKGROUND_IMAGE_ALPHA] = alpha }
    }

    suspend fun setMaxCacheSizeBytes(bytes: Long) {
        context.dataStore.edit { it[SettingsKeys.MAX_CACHE_SIZE_BYTES] = bytes }
    }

    suspend fun setShowLyricTranslation(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SHOW_LYRIC_TRANSLATION] = enabled }
    }

    suspend fun setDefaultStartDestination(route: String) {
        context.dataStore.edit { it[SettingsKeys.DEFAULT_START_DESTINATION] = route }
    }

    suspend fun setAutoShowKeyboard(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.AUTO_SHOW_KEYBOARD] = enabled }
    }

    suspend fun setHomeCardContinue(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.HOME_CARD_CONTINUE] = enabled }
    }

    suspend fun setHomeCardTrending(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.HOME_CARD_TRENDING] = enabled }
    }

    suspend fun setHomeCardRadar(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.HOME_CARD_RADAR] = enabled }
    }

    suspend fun setHomeCardRecommended(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.HOME_CARD_RECOMMENDED] = enabled }
    }

    suspend fun setPlaybackFadeIn(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_FADE_IN] = enabled }
    }

    suspend fun setPlaybackCrossfadeNext(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_NEXT] = enabled }
    }

    suspend fun setPlaybackFadeInDurationMs(durationMs: Long) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS] = durationMs }
    }

    suspend fun setPlaybackFadeOutDurationMs(durationMs: Long) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS] = durationMs }
    }

    suspend fun setPlaybackCrossfadeInDurationMs(durationMs: Long) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS] = durationMs }
    }

    suspend fun setPlaybackCrossfadeOutDurationMs(durationMs: Long) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS] = durationMs }
    }

    suspend fun setKeepLastPlaybackProgress(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] = enabled }
    }

    suspend fun setKeepPlaybackModeState(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] = enabled }
    }

    suspend fun setStopOnBluetoothDisconnect(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] = enabled }
    }

    suspend fun setAllowMixedPlayback(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.ALLOW_MIXED_PLAYBACK] = enabled }
    }
}

private val HEX_COLOR_REGEX = Regex("^[0-9A-F]{6}$")

private fun normalizeHex(candidate: String): String? {
    val normalized = candidate.trim().removePrefix("#").uppercase(Locale.ROOT)
    return normalized.takeIf { HEX_COLOR_REGEX.matches(it) }
}

private fun parseColorPalette(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return ThemeDefaults.PRESET_COLORS
    val parsed = raw.split(',')
        .mapNotNull(::normalizeHex)
        .distinct()
    return parsed.ifEmpty { ThemeDefaults.PRESET_COLORS }
}
