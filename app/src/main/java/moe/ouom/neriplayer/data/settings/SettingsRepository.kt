package moe.ouom.neriplayer.data.settings

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
 * File: moe.ouom.neriplayer.data.settings/SettingsRepository
 * Created: 2025/8/8
 */


import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.decodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.encodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import java.util.Locale

class SettingsRepository(private val context: Context) {
    val dynamicColorFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.DYNAMIC_COLOR] ?: true }

    val forceDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FORCE_DARK] ?: false }

    val followSystemDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true }

    val showCoverSourceBadgeFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.SHOW_COVER_SOURCE_BADGE] ?: true }

    val nowPlayingToolbarDockEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_TOOLBAR_DOCK_ENABLED] ?: true }

    val nowPlayingShowTitleFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NOWPLAYING_SHOW_TITLE] ?: true }

    val nowPlayingProgressShowQualitySwitchFlow: Flow<Boolean> =
        context.dataStore.data.map {
            it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_QUALITY_SWITCH] ?: true
        }

    val nowPlayingProgressShowAudioCodecFlow: Flow<Boolean> =
        context.dataStore.data.map {
            it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_CODEC] ?: true
        }

    val nowPlayingProgressShowAudioSpecFlow: Flow<Boolean> =
        context.dataStore.data.map {
            it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_SPEC] ?: true
        }

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

    val advancedLyricsEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.ADVANCED_LYRICS_ENABLED] ?: true }

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
        context.dataStore.data.map {
            normalizeLyricFontScale(it[SettingsKeys.LYRIC_FONT_SCALE] ?: 1.0f)
        }

    val uiDensityScaleFlow: Flow<Float> =
        context.dataStore.data.map { it[SettingsKeys.UI_DENSITY_SCALE] ?: 1.0f }

    val bypassProxyFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.BYPASS_PROXY] ?: true }

    val backgroundImageUriFlow: Flow<String?> =
        context.dataStore.data.map { it[SettingsKeys.BACKGROUND_IMAGE_URI] }

    val downloadDirectoryUriFlow: Flow<String?> =
        context.dataStore.data.map { it[SettingsKeys.DOWNLOAD_DIRECTORY_URI] }

    val downloadDirectoryLabelFlow: Flow<String?> =
        context.dataStore.data.map { it[SettingsKeys.DOWNLOAD_DIRECTORY_LABEL] }

    val downloadFileNameTemplateFlow: Flow<String?> =
        context.dataStore.data.map {
            normalizeDownloadFileNameTemplate(it[SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE])
        }

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

    val startupOnboardingCompletedFlow: Flow<Boolean?> =
        flow {
            emit(null)
            val realFlow: Flow<Boolean> =
                context.dataStore.data.map { prefs ->
                    prefs[SettingsKeys.STARTUP_ONBOARDING_COMPLETED] ?: false
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

    val playbackSpeedFlow: Flow<Float> =
        context.dataStore.data.map {
            normalizePlaybackSpeed(it[SettingsKeys.PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED)
        }

    val playbackPitchFlow: Flow<Float> =
        context.dataStore.data.map {
            normalizePlaybackPitch(it[SettingsKeys.PLAYBACK_PITCH] ?: DEFAULT_PLAYBACK_PITCH)
        }

    val playbackEqualizerEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.PLAYBACK_EQUALIZER_ENABLED] ?: false }

    val playbackEqualizerPresetFlow: Flow<String> =
        context.dataStore.data.map {
            it[SettingsKeys.PLAYBACK_EQUALIZER_PRESET] ?: PlaybackEqualizerPresetId.FLAT
        }

    val playbackEqualizerCustomBandLevelsFlow: Flow<List<Int>> =
        context.dataStore.data.map {
            decodePlaybackEqualizerBandLevels(it[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS])
        }

    val playbackLoudnessGainMbFlow: Flow<Int> =
        context.dataStore.data.map {
            normalizePlaybackLoudnessGainMb(
                it[SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB] ?: DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
            )
        }

    val keepLastPlaybackProgressFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] ?: true }

    val keepPlaybackModeStateFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] ?: true }

    val stopOnBluetoothDisconnectFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] ?: true }

    val allowMixedPlaybackFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.ALLOW_MIXED_PLAYBACK] ?: false }

    // 中文系统默认关闭国际化
    private val defaultInternationalization: Boolean
        get() = !Locale.getDefault().language.startsWith("zh")

    val internationalizationEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.INTERNATIONALIZATION_ENABLED] ?: defaultInternationalization }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DYNAMIC_COLOR] = value }
        persistThemeDynamicColor(context, value)
    }

    suspend fun setForceDark(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.FORCE_DARK] = value }
        persistThemeForceDark(context, value)
    }

    suspend fun setFollowSystemDark(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.FOLLOW_SYSTEM_DARK] = value }
        persistThemeFollowSystemDark(context, value)
    }

    suspend fun setShowCoverSourceBadge(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SHOW_COVER_SOURCE_BADGE] = enabled }
    }

    suspend fun setNowPlayingToolbarDockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_TOOLBAR_DOCK_ENABLED] = enabled }
    }

    suspend fun setNowPlayingShowTitle(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_SHOW_TITLE] = enabled }
    }

    suspend fun setNowPlayingProgressShowQualitySwitch(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_QUALITY_SWITCH] = enabled }
    }

    suspend fun setNowPlayingProgressShowAudioCodec(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_CODEC] = enabled }
    }

    suspend fun setNowPlayingProgressShowAudioSpec(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_SPEC] = enabled }
    }

    suspend fun setSilentGitHubSyncFailure(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SILENT_GITHUB_SYNC_FAILURE] = enabled }
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DISCLAIMER_ACCEPTED_V2] = accepted }
    }

    suspend fun setStartupOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[SettingsKeys.STARTUP_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.AUDIO_QUALITY] = value }
        updatePlaybackPreferenceSnapshot(context) { it.copy(audioQuality = value) }
    }

    suspend fun setYouTubeAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.YOUTUBE_AUDIO_QUALITY] = value }
        updatePlaybackPreferenceSnapshot(context) { it.copy(youtubeAudioQuality = value) }
    }

    suspend fun setBiliAudioQuality(value: String) {
        context.dataStore.edit { it[SettingsKeys.BILI_AUDIO_QUALITY] = value }
        updatePlaybackPreferenceSnapshot(context) { it.copy(biliAudioQuality = value) }
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

    suspend fun setAdvancedLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.ADVANCED_LYRICS_ENABLED] = enabled }
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
        context.dataStore.edit { it[SettingsKeys.LYRIC_FONT_SCALE] = normalizeLyricFontScale(scale) }
    }

    suspend fun setUiDensityScale(scale: Float) {
        context.dataStore.edit { it[SettingsKeys.UI_DENSITY_SCALE] = scale }
    }

    suspend fun setBypassProxy(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.BYPASS_PROXY] = enabled }
        updateBootstrapSettingsSnapshot(context) { it.copy(bypassProxy = enabled) }
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

    suspend fun setDownloadDirectoryUri(uri: String?) {
        val normalizedUri = ManagedDownloadStorage.canonicalizeDirectoryUri(uri)
        context.dataStore.edit {
            if (normalizedUri == null) {
                it.remove(SettingsKeys.DOWNLOAD_DIRECTORY_URI)
            } else {
                it[SettingsKeys.DOWNLOAD_DIRECTORY_URI] = normalizedUri
            }
        }
        updateBootstrapSettingsSnapshot(context) { it.copy(downloadDirectoryUri = normalizedUri) }
    }

    suspend fun setBackgroundImageBlur(blur: Float) {
        context.dataStore.edit { it[SettingsKeys.BACKGROUND_IMAGE_BLUR] = blur }
    }

    suspend fun setBackgroundImageAlpha(alpha: Float) {
        context.dataStore.edit { it[SettingsKeys.BACKGROUND_IMAGE_ALPHA] = alpha }
    }

    suspend fun setMaxCacheSizeBytes(bytes: Long) {
        val normalized = bytes.coerceAtLeast(0L)
        context.dataStore.edit { it[SettingsKeys.MAX_CACHE_SIZE_BYTES] = normalized }
        updatePlaybackPreferenceSnapshot(context) { it.copy(maxCacheSizeBytes = normalized) }
    }

    suspend fun setDownloadDirectory(uri: String?, label: String?) {
        val normalizedUri = ManagedDownloadStorage.canonicalizeDirectoryUri(uri)
        context.dataStore.edit {
            if (normalizedUri.isNullOrBlank()) {
                it.remove(SettingsKeys.DOWNLOAD_DIRECTORY_URI)
                it.remove(SettingsKeys.DOWNLOAD_DIRECTORY_LABEL)
            } else {
                it[SettingsKeys.DOWNLOAD_DIRECTORY_URI] = normalizedUri
                if (label.isNullOrBlank()) {
                    it.remove(SettingsKeys.DOWNLOAD_DIRECTORY_LABEL)
                } else {
                    it[SettingsKeys.DOWNLOAD_DIRECTORY_LABEL] = label
                }
            }
        }
        updateBootstrapSettingsSnapshot(context) {
            it.copy(
                downloadDirectoryUri = normalizedUri,
                downloadDirectoryLabel = label
            )
        }
    }

    suspend fun setDownloadFileNameTemplate(template: String?) {
        val normalized = normalizeDownloadFileNameTemplate(template)
        context.dataStore.edit {
            if (normalized == null) {
                it.remove(SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE)
            } else {
                it[SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE] = normalized
            }
        }
        updateBootstrapSettingsSnapshot(context) {
            it.copy(downloadFileNameTemplate = normalized)
        }
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
        updatePlaybackPreferenceSnapshot(context) { it.copy(playbackFadeIn = enabled) }
    }

    suspend fun setPlaybackCrossfadeNext(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_NEXT] = enabled }
        updatePlaybackPreferenceSnapshot(context) { it.copy(playbackCrossfadeNext = enabled) }
    }

    suspend fun setPlaybackFadeInDurationMs(durationMs: Long) {
        val normalized = durationMs.coerceAtLeast(0L)
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS] = normalized }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackFadeInDurationMs = normalized)
        }
    }

    suspend fun setPlaybackFadeOutDurationMs(durationMs: Long) {
        val normalized = durationMs.coerceAtLeast(0L)
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS] = normalized }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackFadeOutDurationMs = normalized)
        }
    }

    suspend fun setPlaybackCrossfadeInDurationMs(durationMs: Long) {
        val normalized = durationMs.coerceAtLeast(0L)
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS] = normalized }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackCrossfadeInDurationMs = normalized)
        }
    }

    suspend fun setPlaybackCrossfadeOutDurationMs(durationMs: Long) {
        val normalized = durationMs.coerceAtLeast(0L)
        context.dataStore.edit { it[SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS] = normalized }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackCrossfadeOutDurationMs = normalized)
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val normalized = normalizePlaybackSpeed(speed)
        context.dataStore.edit {
            it[SettingsKeys.PLAYBACK_SPEED] = normalized
        }
        updatePlaybackPreferenceSnapshot(context) { it.copy(playbackSpeed = normalized) }
    }

    suspend fun setPlaybackPitch(pitch: Float) {
        val normalized = normalizePlaybackPitch(pitch)
        context.dataStore.edit {
            it[SettingsKeys.PLAYBACK_PITCH] = normalized
        }
        updatePlaybackPreferenceSnapshot(context) { it.copy(playbackPitch = normalized) }
    }

    suspend fun setPlaybackEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[SettingsKeys.PLAYBACK_EQUALIZER_ENABLED] = enabled
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackEqualizerEnabled = enabled)
        }
    }

    suspend fun setPlaybackEqualizerPreset(presetId: String) {
        context.dataStore.edit {
            it[SettingsKeys.PLAYBACK_EQUALIZER_PRESET] = presetId
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackEqualizerPreset = presetId)
        }
    }

    suspend fun setPlaybackEqualizerCustomBandLevels(levelsMb: List<Int>) {
        val normalizedLevels = levelsMb.toList()
        context.dataStore.edit { prefs ->
            val encoded = encodePlaybackEqualizerBandLevels(normalizedLevels)
            if (encoded.isNullOrBlank()) {
                prefs.remove(SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS)
            } else {
                prefs[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS] = encoded
            }
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackEqualizerCustomBandLevels = normalizedLevels)
        }
    }

    suspend fun setPlaybackLoudnessGainMb(levelMb: Int) {
        val normalized = normalizePlaybackLoudnessGainMb(levelMb)
        context.dataStore.edit {
            it[SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB] = normalized
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(playbackLoudnessGainMb = normalized)
        }
    }

    suspend fun setKeepLastPlaybackProgress(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(keepLastPlaybackProgress = enabled)
        }
    }

    suspend fun setKeepPlaybackModeState(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(keepPlaybackModeState = enabled)
        }
    }

    suspend fun setStopOnBluetoothDisconnect(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(stopOnBluetoothDisconnect = enabled)
        }
    }

    suspend fun setAllowMixedPlayback(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.ALLOW_MIXED_PLAYBACK] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(allowMixedPlayback = enabled)
        }
    }

    suspend fun setInternationalizationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.INTERNATIONALIZATION_ENABLED] = enabled }
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
