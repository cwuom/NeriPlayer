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
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import java.util.Locale

class SettingsRepository(private val context: Context) {
    private val autoSettingsRepository = AutoSettingsRepository(context)
    private val autoSettingSpecRepository = AutoSettingSpecRepository(context)

    fun <T> settingFlow(setting: AutoSettingSpec<T>): Flow<T> {
        return autoSettingSpecRepository.flow(setting)
    }

    suspend fun <T> setSetting(setting: AutoSettingSpec<T>, value: T) {
        autoSettingSpecRepository.set(setting, value)
    }

    val dynamicColorFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.DYNAMIC_COLOR] ?: true }

    val forceDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FORCE_DARK] ?: false }

    val followSystemDarkFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true }

    val showCoverSourceBadgeFlow: Flow<Boolean> =
        autoSettingsRepository.showCoverSourceBadgeFlow

    val nowPlayingToolbarDockEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingToolbarDockEnabledFlow

    val nowPlayingKeepScreenOnFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingKeepScreenOnFlow

    val nowPlayingShowTitleFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingShowTitleFlow

    val nowPlayingProgressShowQualitySwitchFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingProgressShowQualitySwitchFlow

    val nowPlayingProgressShowAudioCodecFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingProgressShowAudioCodecFlow

    val nowPlayingProgressShowAudioSpecFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingProgressShowAudioSpecFlow

    val silentGitHubSyncFailureFlow: Flow<Boolean> =
        autoSettingsRepository.silentGitHubSyncFailureFlow

    val audioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.AUDIO_QUALITY] ?: "exhigh" }

    val youtubeAudioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.YOUTUBE_AUDIO_QUALITY] ?: "very_high" }

    val biliAudioQualityFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.BILI_AUDIO_QUALITY] ?: "high" }

    val devModeEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.devModeEnabledFlow

    val themeSeedColorFlow: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.THEME_SEED_COLOR] ?: ThemeDefaults.DEFAULT_SEED_COLOR_HEX }

    val themeColorPaletteFlow: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            parseColorPalette(prefs[SettingsKeys.THEME_COLOR_PALETTE])
        }

    val themePaletteStyleFlow: Flow<String> =
        context.dataStore.data.map {
            ThemeDefaults.normalizePaletteStyle(it[SettingsKeys.THEME_PALETTE_STYLE])
        }

    val themeColorSpecFlow: Flow<String> =
        context.dataStore.data.map {
            ThemeDefaults.normalizeColorSpec(it[SettingsKeys.THEME_COLOR_SPEC])
        }

    val lyricBlurEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.lyricBlurEnabledFlow

    val lyricBlurAmountFlow: Flow<Float> =
        autoSettingsRepository.lyricBlurAmountFlow

    val cloudMusicLyricDefaultOffsetMsFlow: Flow<Long> =
        context.dataStore.data.map {
            normalizeLyricDefaultOffsetMs(
                it[SettingsKeys.CLOUD_MUSIC_LYRIC_DEFAULT_OFFSET_MS]
                    ?: DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
            )
        }

    val qqMusicLyricDefaultOffsetMsFlow: Flow<Long> =
        context.dataStore.data.map {
            normalizeLyricDefaultOffsetMs(
                it[SettingsKeys.QQ_MUSIC_LYRIC_DEFAULT_OFFSET_MS]
                    ?: DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
            )
        }

    val advancedLyricsEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.advancedLyricsEnabledFlow

    val lyriconEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.lyriconEnabledFlow

    val statusBarLyricsEnabledFlow : Flow<Boolean> =
        autoSettingsRepository.statusBarLyricsFlow

    val externalBluetoothLyricsEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.externalBluetoothLyricsEnabledFlow

    val floatingLyricsPreferencesFlow: Flow<FloatingLyricsPreferences> =
        context.dataStore.data.map { prefs ->
            val outlineWidthDp = prefs[SettingsKeys.FLOATING_LYRICS_OUTLINE_WIDTH_DP] ?: 1.6f
            FloatingLyricsPreferences(
                enabled = prefs[SettingsKeys.FLOATING_LYRICS_ENABLED] ?: false,
                hideInApp = prefs[SettingsKeys.FLOATING_LYRICS_HIDE_IN_APP] ?: false,
                textColorHex = prefs[SettingsKeys.FLOATING_LYRICS_TEXT_COLOR] ?: "FFFFFF",
                outlineColorHex = prefs[SettingsKeys.FLOATING_LYRICS_OUTLINE_COLOR] ?: "121212",
                fontSizeSp = prefs[SettingsKeys.FLOATING_LYRICS_FONT_SIZE_SP] ?: 22f,
                outlineWidthDp = outlineWidthDp,
                lyricAlpha = resolveFloatingLyricsLyricAlpha(
                    prefs[SettingsKeys.FLOATING_LYRICS_LYRIC_ALPHA]
                ),
                translationOutlineWidthDp = resolveFloatingLyricsTranslationOutlineWidthDp(
                    prefs[SettingsKeys.FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP],
                    outlineWidthDp
                ),
                translationAlpha = resolveFloatingLyricsTranslationAlpha(
                    prefs[SettingsKeys.FLOATING_LYRICS_TRANSLATION_ALPHA]
                ),
                maxWidthDp = prefs[SettingsKeys.FLOATING_LYRICS_MAX_WIDTH_DP] ?: 280f,
                positionX = prefs[SettingsKeys.FLOATING_LYRICS_POSITION_X] ?: 0.1f,
                positionY = prefs[SettingsKeys.FLOATING_LYRICS_POSITION_Y] ?: 0.7f,
                alignment = prefs[SettingsKeys.FLOATING_LYRICS_ALIGNMENT]
                    ?: FLOATING_LYRICS_ALIGNMENT_CENTER,
                showTranslation = prefs[SettingsKeys.FLOATING_LYRICS_SHOW_TRANSLATION] ?: true,
                revealAnimationEnabled =
                    prefs[SettingsKeys.FLOATING_LYRICS_REVEAL_ANIMATION_ENABLED] ?: true
            ).normalized()
        }

    val advancedBlurEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.advancedBlurEnabledFlow

    val nowPlayingAudioReactiveEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingAudioReactiveEnabledFlow

    val nowPlayingDynamicBackgroundEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingDynamicBackgroundEnabledFlow

    val nowPlayingCoverBlurBackgroundEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.nowPlayingCoverBlurBackgroundEnabledFlow

    val nowPlayingCoverBlurAmountFlow: Flow<Float> =
        autoSettingsRepository.nowPlayingCoverBlurAmountFlow

    val nowPlayingCoverBlurDarkenFlow: Flow<Float> =
        autoSettingsRepository.nowPlayingCoverBlurDarkenFlow

    val lyricFontScaleFlow: Flow<Float> =
        context.dataStore.data.map {
            normalizeLyricFontScale(it[SettingsKeys.LYRIC_FONT_SCALE] ?: 1.0f)
        }

    val uiDensityScaleFlow: Flow<Float> =
        autoSettingsRepository.uiDensityScaleFlow

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

    val downloadMetadataPostProcessingEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.downloadMetadataPostProcessingEnabledFlow

    val backgroundImageBlurFlow: Flow<Float> =
        autoSettingsRepository.backgroundImageBlurFlow

    val backgroundImageAlphaFlow: Flow<Float> =
        autoSettingsRepository.backgroundImageAlphaFlow

    val hapticFeedbackEnabledFlow: Flow<Boolean> =
        autoSettingsRepository.hapticFeedbackEnabledFlow

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
        autoSettingsRepository.showLyricTranslationFlow

    val defaultStartDestinationFlow: Flow<String> =
        autoSettingsRepository.defaultStartDestinationFlow

    val autoShowKeyboardFlow: Flow<Boolean> =
        autoSettingsRepository.autoShowKeyboardFlow

    val homeCardContinueFlow: Flow<Boolean> =
        autoSettingsRepository.homeCardContinueFlow

    val homeCardTrendingFlow: Flow<Boolean> =
        autoSettingsRepository.homeCardTrendingFlow

    val homeCardRadarFlow: Flow<Boolean> =
        autoSettingsRepository.homeCardRadarFlow

    val homeCardRecommendedFlow: Flow<Boolean> =
        autoSettingsRepository.homeCardRecommendedFlow

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

    val neteaseAutoSourceSwitchFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH] ?: true }

    val stopOnBluetoothDisconnectFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] ?: true }

    val usbExclusivePlaybackFlow: Flow<Boolean> =
        context.dataStore.data.map { it[SettingsKeys.USB_EXCLUSIVE_PLAYBACK] ?: false }

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
        autoSettingsRepository.setShowCoverSourceBadge(enabled)
    }

    suspend fun setNowPlayingToolbarDockEnabled(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingToolbarDockEnabled(enabled)
    }

    suspend fun setNowPlayingKeepScreenOn(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingKeepScreenOn(enabled)
    }

    suspend fun setNowPlayingShowTitle(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingShowTitle(enabled)
    }

    suspend fun setNowPlayingProgressShowQualitySwitch(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingProgressShowQualitySwitch(enabled)
    }

    suspend fun setNowPlayingProgressShowAudioCodec(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingProgressShowAudioCodec(enabled)
    }

    suspend fun setNowPlayingProgressShowAudioSpec(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingProgressShowAudioSpec(enabled)
    }

    suspend fun setSilentGitHubSyncFailure(enabled: Boolean) {
        autoSettingsRepository.setSilentGitHubSyncFailure(enabled)
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
        autoSettingsRepository.setDevModeEnabled(enabled)
    }

    suspend fun setThemeSeedColor(hex: String) {
        context.dataStore.edit { it[SettingsKeys.THEME_SEED_COLOR] = hex }
    }

    suspend fun setThemePaletteStyle(style: String) {
        context.dataStore.edit {
            it[SettingsKeys.THEME_PALETTE_STYLE] = ThemeDefaults.normalizePaletteStyle(style)
        }
    }

    suspend fun setThemeColorSpec(spec: String) {
        context.dataStore.edit {
            it[SettingsKeys.THEME_COLOR_SPEC] = ThemeDefaults.normalizeColorSpec(spec)
        }
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
        autoSettingsRepository.setLyricBlurEnabled(enabled)
    }

    suspend fun setLyricBlurAmount(amount: Float) {
        autoSettingsRepository.setLyricBlurAmount(amount)
    }

    suspend fun setCloudMusicLyricDefaultOffsetMs(offsetMs: Long) {
        val normalized = normalizeLyricDefaultOffsetMs(offsetMs)
        context.dataStore.edit {
            it[SettingsKeys.CLOUD_MUSIC_LYRIC_DEFAULT_OFFSET_MS] = normalized
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(cloudMusicLyricDefaultOffsetMs = normalized)
        }
    }

    suspend fun setQqMusicLyricDefaultOffsetMs(offsetMs: Long) {
        val normalized = normalizeLyricDefaultOffsetMs(offsetMs)
        context.dataStore.edit {
            it[SettingsKeys.QQ_MUSIC_LYRIC_DEFAULT_OFFSET_MS] = normalized
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(qqMusicLyricDefaultOffsetMs = normalized)
        }
    }

    suspend fun setAdvancedLyricsEnabled(enabled: Boolean) {
        autoSettingsRepository.setAdvancedLyricsEnabled(enabled)
    }

    suspend fun setLyriconEnabled(enabled: Boolean) {
        autoSettingsRepository.setLyriconEnabled(enabled)
        updatePlaybackPreferenceSnapshot(context) { it.copy(lyriconEnabled = enabled) }
    }

    suspend fun setExternalBluetoothLyricsEnabled(enabled: Boolean) {
        autoSettingsRepository.setExternalBluetoothLyricsEnabled(enabled)
    }

    suspend fun setFloatingLyricsPreferences(preferences: FloatingLyricsPreferences) {
        val normalized = preferences.normalized()
        context.dataStore.edit { prefs ->
            prefs[SettingsKeys.FLOATING_LYRICS_ENABLED] = normalized.enabled
            prefs[SettingsKeys.FLOATING_LYRICS_HIDE_IN_APP] = normalized.hideInApp
            prefs[SettingsKeys.FLOATING_LYRICS_TEXT_COLOR] = normalized.textColorHex
            prefs[SettingsKeys.FLOATING_LYRICS_OUTLINE_COLOR] = normalized.outlineColorHex
            prefs[SettingsKeys.FLOATING_LYRICS_FONT_SIZE_SP] = normalized.fontSizeSp
            prefs[SettingsKeys.FLOATING_LYRICS_OUTLINE_WIDTH_DP] = normalized.outlineWidthDp
            prefs[SettingsKeys.FLOATING_LYRICS_LYRIC_ALPHA] = normalized.lyricAlpha
            prefs[SettingsKeys.FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP] =
                normalized.translationOutlineWidthDp
            prefs[SettingsKeys.FLOATING_LYRICS_TRANSLATION_ALPHA] =
                normalized.translationAlpha
            prefs[SettingsKeys.FLOATING_LYRICS_MAX_WIDTH_DP] = normalized.maxWidthDp
            prefs[SettingsKeys.FLOATING_LYRICS_POSITION_X] = normalized.positionX
            prefs[SettingsKeys.FLOATING_LYRICS_POSITION_Y] = normalized.positionY
            prefs[SettingsKeys.FLOATING_LYRICS_ALIGNMENT] = normalized.alignment
            prefs[SettingsKeys.FLOATING_LYRICS_SHOW_TRANSLATION] = normalized.showTranslation
            prefs[SettingsKeys.FLOATING_LYRICS_REVEAL_ANIMATION_ENABLED] =
                normalized.revealAnimationEnabled
        }
    }

    suspend fun setAdvancedBlurEnabled(enabled: Boolean) {
        autoSettingsRepository.setAdvancedBlurEnabled(enabled)
    }

    suspend fun setNowPlayingAudioReactiveEnabled(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingAudioReactiveEnabled(enabled)
    }

    suspend fun setNowPlayingDynamicBackgroundEnabled(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingDynamicBackgroundEnabled(enabled)
    }

    suspend fun setNowPlayingCoverBlurBackgroundEnabled(enabled: Boolean) {
        autoSettingsRepository.setNowPlayingCoverBlurBackgroundEnabled(enabled)
    }

    suspend fun setNowPlayingCoverBlurAmount(amount: Float) {
        autoSettingsRepository.setNowPlayingCoverBlurAmount(amount)
    }

    suspend fun setNowPlayingCoverBlurDarken(amount: Float) {
        autoSettingsRepository.setNowPlayingCoverBlurDarken(amount)
    }

    suspend fun setLyricFontScale(scale: Float) {
        context.dataStore.edit { it[SettingsKeys.LYRIC_FONT_SCALE] = normalizeLyricFontScale(scale) }
    }

    suspend fun setUiDensityScale(scale: Float) {
        autoSettingsRepository.setUiDensityScale(scale)
    }

    suspend fun setBypassProxy(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.BYPASS_PROXY] = enabled }
        updateBootstrapSettingsSnapshot(context) { it.copy(bypassProxy = enabled) }
    }

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        autoSettingsRepository.setHapticFeedbackEnabled(enabled)
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
        autoSettingsRepository.setBackgroundImageBlur(blur)
    }

    suspend fun setBackgroundImageAlpha(alpha: Float) {
        autoSettingsRepository.setBackgroundImageAlpha(alpha)
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

    suspend fun setDownloadMetadataPostProcessingEnabled(enabled: Boolean) {
        autoSettingsRepository.setDownloadMetadataPostProcessingEnabled(enabled)
    }

    suspend fun setShowLyricTranslation(enabled: Boolean) {
        autoSettingsRepository.setShowLyricTranslation(enabled)
    }

    suspend fun setDefaultStartDestination(route: String) {
        autoSettingsRepository.setDefaultStartDestination(route)
    }

    suspend fun setAutoShowKeyboard(enabled: Boolean) {
        autoSettingsRepository.setAutoShowKeyboard(enabled)
    }

    suspend fun setHomeCardContinue(enabled: Boolean) {
        autoSettingsRepository.setHomeCardContinue(enabled)
    }

    suspend fun setHomeCardTrending(enabled: Boolean) {
        autoSettingsRepository.setHomeCardTrending(enabled)
    }

    suspend fun setHomeCardRadar(enabled: Boolean) {
        autoSettingsRepository.setHomeCardRadar(enabled)
    }

    suspend fun setHomeCardRecommended(enabled: Boolean) {
        autoSettingsRepository.setHomeCardRecommended(enabled)
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

    suspend fun setNeteaseAutoSourceSwitch(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(neteaseAutoSourceSwitch = enabled)
        }
    }

    suspend fun setStopOnBluetoothDisconnect(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(stopOnBluetoothDisconnect = enabled)
        }
    }

    suspend fun setUsbExclusivePlayback(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.USB_EXCLUSIVE_PLAYBACK] = enabled }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(usbExclusivePlayback = enabled)
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
