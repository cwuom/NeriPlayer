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
 * File: moe.ouom.neriplayer.data.settings/PlaybackPreferenceSnapshot
 * Updated: 2026/4/5
 */

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.decodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.encodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.model.normalizePlaybackVolumeBalance
import androidx.core.content.edit

private const val PLAYBACK_SNAPSHOT_PREFS = "playback_snapshot_cache"
private const val PLAYBACK_SNAPSHOT_SCHEMA_VERSION = 2
private const val PLAYBACK_SNAPSHOT_SCHEMA_VERSION_KEY = "schema_version"
private const val PLAYBACK_SNAPSHOT_READY_KEY = "ready"
private const val PLAYBACK_AUDIO_QUALITY_KEY = "audio_quality"
private const val PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY = "youtube_audio_quality"
private const val PLAYBACK_BILI_AUDIO_QUALITY_KEY = "bili_audio_quality"
private const val PLAYBACK_MOBILE_DATA_DOWNGRADE_QUALITY_KEY = "mobile_data_downgrade_quality"
private const val PLAYBACK_MOBILE_DATA_FOLLOW_DEFAULT_AUDIO_QUALITY_KEY =
    "mobile_data_follow_default_audio_quality"
private const val PLAYBACK_MOBILE_DATA_NETEASE_AUDIO_QUALITY_KEY =
    "mobile_data_netease_audio_quality"
private const val PLAYBACK_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY_KEY =
    "mobile_data_youtube_audio_quality"
private const val PLAYBACK_MOBILE_DATA_BILI_AUDIO_QUALITY_KEY =
    "mobile_data_bili_audio_quality"
private const val PLAYBACK_KEEP_PROGRESS_KEY = "keep_last_playback_progress"
private const val PLAYBACK_KEEP_MODE_STATE_KEY = "keep_playback_mode_state"
private const val PLAYBACK_NETEASE_AUTO_SOURCE_SWITCH_KEY = "netease_auto_source_switch"
private const val PLAYBACK_FADE_IN_KEY = "playback_fade_in"
private const val PLAYBACK_CROSSFADE_NEXT_KEY = "playback_crossfade_next"
private const val PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY_KEY =
    "playback_sleep_timer_finish_current_on_expiry"
private const val PLAYBACK_FADE_IN_DURATION_KEY = "playback_fade_in_duration_ms"
private const val PLAYBACK_FADE_OUT_DURATION_KEY = "playback_fade_out_duration_ms"
private const val PLAYBACK_CROSSFADE_IN_DURATION_KEY = "playback_crossfade_in_duration_ms"
private const val PLAYBACK_CROSSFADE_OUT_DURATION_KEY = "playback_crossfade_out_duration_ms"
private const val PLAYBACK_SPEED_KEY = "playback_speed"
private const val PLAYBACK_PITCH_KEY = "playback_pitch"
private const val PLAYBACK_LOUDNESS_KEY = "playback_loudness_gain_mb"
private const val PLAYBACK_VOLUME_BALANCE_KEY = "playback_volume_balance"
private const val PLAYBACK_VOLUME_NORMALIZATION_KEY = "playback_volume_normalization_enabled"
private const val PLAYBACK_HIGH_RESOLUTION_OUTPUT_KEY =
    "playback_high_resolution_output_enabled"
private const val PLAYBACK_EQUALIZER_ENABLED_KEY = "playback_equalizer_enabled"
private const val PLAYBACK_EQUALIZER_PRESET_KEY = "playback_equalizer_preset"
private const val PLAYBACK_EQUALIZER_LEVELS_KEY = "playback_equalizer_custom_band_levels"
private const val PLAYBACK_STOP_ON_BLUETOOTH_KEY = "stop_on_bluetooth_disconnect"
private const val PLAYBACK_USB_EXCLUSIVE_KEY = "usb_exclusive_playback"
private const val PLAYBACK_USB_EXCLUSIVE_DEVICE_KEY = "usb_exclusive_device_key"
private const val PLAYBACK_ALLOW_MIXED_KEY = "allow_mixed_playback"
private const val PLAYBACK_PREEMPT_AUDIO_FOCUS_KEY = "preempt_audio_focus"
private const val PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY = "max_cache_size_bytes"
private const val PLAYBACK_CLOUD_MUSIC_LYRIC_OFFSET_KEY = "cloud_music_lyric_default_offset_ms"
private const val PLAYBACK_QQ_MUSIC_LYRIC_OFFSET_KEY = "qq_music_lyric_default_offset_ms"
private const val PLAYBACK_LYRICON_ENABLED_KEY = "lyricon_enabled"
private const val PLAYBACK_AMLL_LYRICS_ENABLED_KEY = "amll_lyrics_enabled"
private const val DEFAULT_MAX_CACHE_SIZE_BYTES = 1024L * 1024 * 1024
private val playbackPreferenceSnapshotWarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val playbackPreferenceSnapshotWarmLock = Any()

@Volatile
private var playbackPreferenceSnapshotWarmScheduled = false

data class PlaybackPreferenceSnapshot(
    val audioQuality: String = "exhigh",
    val youtubeAudioQuality: String = "high",
    val biliAudioQuality: String = "high",
    val mobileDataFollowDefaultAudioQuality: Boolean = true,
    val mobileDataNeteaseAudioQuality: String = DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY,
    val mobileDataYouTubeAudioQuality: String = DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY,
    val mobileDataBiliAudioQuality: String = DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY,
    val keepLastPlaybackProgress: Boolean = true,
    val keepPlaybackModeState: Boolean = true,
    val neteaseAutoSourceSwitch: Boolean = true,
    val playbackFadeIn: Boolean = false,
    val playbackCrossfadeNext: Boolean = false,
    val sleepTimerFinishCurrentOnExpiry: Boolean = false,
    val playbackFadeInDurationMs: Long = 500L,
    val playbackFadeOutDurationMs: Long = 500L,
    val playbackCrossfadeInDurationMs: Long = 500L,
    val playbackCrossfadeOutDurationMs: Long = 500L,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val playbackPitch: Float = DEFAULT_PLAYBACK_PITCH,
    val playbackLoudnessGainMb: Int = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
    val playbackVolumeBalance: Float = DEFAULT_PLAYBACK_VOLUME_BALANCE,
    val playbackVolumeNormalizationEnabled: Boolean = false,
    val playbackHighResolutionOutputEnabled: Boolean = false,
    val playbackEqualizerEnabled: Boolean = false,
    val playbackEqualizerPreset: String = PlaybackEqualizerPresetId.FLAT,
    val playbackEqualizerCustomBandLevels: List<Int> = emptyList(),
    val stopOnBluetoothDisconnect: Boolean = true,
    val usbExclusivePlayback: Boolean = false,
    val usbExclusiveDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY,
    val usbExclusiveSampleRateMode: String = DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE,
    val usbExclusiveBitDepthMode: String = DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE,
    val usbExclusiveBufferProfile: String = DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE,
    val usbExclusiveUnsupportedFormatPolicy: String =
        DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
    val usbExclusiveSampleRateCompatibility: Boolean =
        DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
    val usbExclusiveBitDepthCompatibility: Boolean =
        DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
    val usbExclusiveChannelCompatibility: Boolean =
        DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
    val usbExclusiveForegroundBufferMs: Int = DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
    val usbExclusiveBackgroundBufferMs: Int = DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
    val allowMixedPlayback: Boolean = false,
    val preemptAudioFocus: Boolean = false,
    val cloudMusicLyricDefaultOffsetMs: Long = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
    val qqMusicLyricDefaultOffsetMs: Long = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
    val lyriconEnabled: Boolean = false,
    val amllLyricsEnabled: Boolean = true,
    val maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
) {
    fun sanitized(): PlaybackPreferenceSnapshot {
        return copy(
            audioQuality = audioQuality.trim().ifBlank { "exhigh" },
            youtubeAudioQuality = youtubeAudioQuality.trim().ifBlank { "high" },
            biliAudioQuality = biliAudioQuality.trim().ifBlank { "high" },
            mobileDataNeteaseAudioQuality =
                normalizeMobileDataNeteaseAudioQuality(mobileDataNeteaseAudioQuality),
            mobileDataYouTubeAudioQuality =
                normalizeMobileDataYouTubeAudioQuality(mobileDataYouTubeAudioQuality),
            mobileDataBiliAudioQuality =
                normalizeMobileDataBiliAudioQuality(mobileDataBiliAudioQuality),
            playbackFadeInDurationMs = playbackFadeInDurationMs.coerceAtLeast(0L),
            playbackFadeOutDurationMs = playbackFadeOutDurationMs.coerceAtLeast(0L),
            playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs.coerceAtLeast(0L),
            playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs.coerceAtLeast(0L),
            playbackSpeed = normalizePlaybackSpeed(playbackSpeed),
            playbackPitch = normalizePlaybackPitch(playbackPitch),
            playbackLoudnessGainMb = normalizePlaybackLoudnessGainMb(playbackLoudnessGainMb),
            playbackVolumeBalance = normalizePlaybackVolumeBalance(playbackVolumeBalance),
            playbackEqualizerPreset = playbackEqualizerPreset.trim()
                .ifBlank { PlaybackEqualizerPresetId.FLAT },
            usbExclusiveSampleRateMode = UsbExclusiveSampleRateMode
                .fromStorageValue(usbExclusiveSampleRateMode)
                .storageValue,
            usbExclusiveDeviceKey = normalizeUsbExclusiveDeviceKey(usbExclusiveDeviceKey),
            usbExclusiveBitDepthMode = UsbExclusiveBitDepthMode
                .fromStorageValue(usbExclusiveBitDepthMode)
                .storageValue,
            usbExclusiveBufferProfile = UsbExclusiveBufferProfile
                .fromStorageValue(usbExclusiveBufferProfile)
                .storageValue,
            usbExclusiveUnsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy
                .fromStorageValue(usbExclusiveUnsupportedFormatPolicy)
                .storageValue,
            usbExclusiveForegroundBufferMs = normalizeUsbExclusiveForegroundBufferMs(
                usbExclusiveForegroundBufferMs
            ),
            usbExclusiveBackgroundBufferMs = normalizeUsbExclusiveBackgroundBufferMs(
                usbExclusiveBackgroundBufferMs
            ),
            cloudMusicLyricDefaultOffsetMs = normalizeLyricDefaultOffsetMs(cloudMusicLyricDefaultOffsetMs),
            qqMusicLyricDefaultOffsetMs = normalizeLyricDefaultOffsetMs(qqMusicLyricDefaultOffsetMs),
            maxCacheSizeBytes = maxCacheSizeBytes.coerceAtLeast(0L)
        )
    }

    fun toPlaybackSoundConfig(): PlaybackSoundConfig {
        val normalizedSnapshot = sanitized()
        return PlaybackSoundConfig(
            speed = normalizedSnapshot.playbackSpeed,
            pitch = normalizedSnapshot.playbackPitch,
            loudnessGainMb = normalizedSnapshot.playbackLoudnessGainMb,
            volumeBalance = normalizedSnapshot.playbackVolumeBalance,
            volumeNormalizationEnabled = normalizedSnapshot.playbackVolumeNormalizationEnabled,
            equalizerEnabled = normalizedSnapshot.playbackEqualizerEnabled,
            presetId = normalizedSnapshot.playbackEqualizerPreset,
            customBandLevelsMb = normalizedSnapshot.playbackEqualizerCustomBandLevels
        )
    }

}

suspend fun readPlaybackPreferenceSnapshot(context: Context): PlaybackPreferenceSnapshot {
    readCachedPlaybackPreferenceSnapshot(context)?.let { return it }

    return runCatching {
        context.dataStore.data.first().toPlaybackPreferenceSnapshot()
    }.getOrElse {
        PlaybackPreferenceSnapshot()
    }.also { snapshot ->
        persistPlaybackPreferenceSnapshot(context, snapshot)
    }
}

fun readPlaybackPreferenceSnapshotSync(context: Context): PlaybackPreferenceSnapshot {
    readPlaybackPreferenceSnapshotCached(context)?.let { return it }
    schedulePlaybackPreferenceSnapshotWarm(context.applicationContext)
    return PlaybackPreferenceSnapshot()
}

fun readPlaybackPreferenceSnapshotCached(context: Context): PlaybackPreferenceSnapshot? {
    return readCachedPlaybackPreferenceSnapshot(context)
}

private fun schedulePlaybackPreferenceSnapshotWarm(context: Context) {
    if (readCachedPlaybackPreferenceSnapshot(context) != null) {
        return
    }
    synchronized(playbackPreferenceSnapshotWarmLock) {
        if (playbackPreferenceSnapshotWarmScheduled) {
            return
        }
        playbackPreferenceSnapshotWarmScheduled = true
    }
    playbackPreferenceSnapshotWarmScope.launch {
        try {
            val snapshot = context.dataStore.data.first().toPlaybackPreferenceSnapshot()
            persistPlaybackPreferenceSnapshot(context, snapshot)
        } catch (_: Exception) {
            // 这里只做后台补热，失败时继续让调用方走默认值
        } finally {
            playbackPreferenceSnapshotWarmScheduled = false
        }
    }
}

internal suspend fun updatePlaybackPreferenceSnapshot(
    context: Context,
    transform: (PlaybackPreferenceSnapshot) -> PlaybackPreferenceSnapshot
) {
    val currentSnapshot = readCachedPlaybackPreferenceSnapshot(context)
        ?: context.dataStore.data.first().toPlaybackPreferenceSnapshot()
    persistPlaybackPreferenceSnapshot(context, transform(currentSnapshot))
}

internal fun persistPlaybackPreferenceSnapshot(
    context: Context,
    snapshot: PlaybackPreferenceSnapshot
) {
    val normalizedSnapshot = snapshot.sanitized()
    val encodedBandLevels = encodePlaybackEqualizerBandLevels(
        normalizedSnapshot.playbackEqualizerCustomBandLevels
    )
    context.getSharedPreferences(PLAYBACK_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
        .edit {
            putBoolean(PLAYBACK_SNAPSHOT_READY_KEY, true)
                .putInt(PLAYBACK_SNAPSHOT_SCHEMA_VERSION_KEY, PLAYBACK_SNAPSHOT_SCHEMA_VERSION)
                .putString(PLAYBACK_AUDIO_QUALITY_KEY, normalizedSnapshot.audioQuality)
                .putString(
                    PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.youtubeAudioQuality
                )
                .putString(PLAYBACK_BILI_AUDIO_QUALITY_KEY, normalizedSnapshot.biliAudioQuality)
                .putBoolean(
                    PLAYBACK_MOBILE_DATA_FOLLOW_DEFAULT_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.mobileDataFollowDefaultAudioQuality
                )
                .putString(
                    PLAYBACK_MOBILE_DATA_NETEASE_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.mobileDataNeteaseAudioQuality
                )
                .putString(
                    PLAYBACK_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.mobileDataYouTubeAudioQuality
                )
                .putString(
                    PLAYBACK_MOBILE_DATA_BILI_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.mobileDataBiliAudioQuality
                )
                .putBoolean(PLAYBACK_KEEP_PROGRESS_KEY, normalizedSnapshot.keepLastPlaybackProgress)
                .putBoolean(PLAYBACK_KEEP_MODE_STATE_KEY, normalizedSnapshot.keepPlaybackModeState)
                .putBoolean(
                    PLAYBACK_NETEASE_AUTO_SOURCE_SWITCH_KEY,
                    normalizedSnapshot.neteaseAutoSourceSwitch
                )
                .putBoolean(PLAYBACK_FADE_IN_KEY, normalizedSnapshot.playbackFadeIn)
                .putBoolean(PLAYBACK_CROSSFADE_NEXT_KEY, normalizedSnapshot.playbackCrossfadeNext)
                .putBoolean(
                    PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY_KEY,
                    normalizedSnapshot.sleepTimerFinishCurrentOnExpiry
                )
                .putLong(PLAYBACK_FADE_IN_DURATION_KEY, normalizedSnapshot.playbackFadeInDurationMs)
                .putLong(
                    PLAYBACK_FADE_OUT_DURATION_KEY,
                    normalizedSnapshot.playbackFadeOutDurationMs
                )
                .putLong(
                    PLAYBACK_CROSSFADE_IN_DURATION_KEY,
                    normalizedSnapshot.playbackCrossfadeInDurationMs
                )
                .putLong(
                    PLAYBACK_CROSSFADE_OUT_DURATION_KEY,
                    normalizedSnapshot.playbackCrossfadeOutDurationMs
                )
                .putFloat(PLAYBACK_SPEED_KEY, normalizedSnapshot.playbackSpeed)
                .putFloat(PLAYBACK_PITCH_KEY, normalizedSnapshot.playbackPitch)
                .putInt(PLAYBACK_LOUDNESS_KEY, normalizedSnapshot.playbackLoudnessGainMb)
                .putFloat(PLAYBACK_VOLUME_BALANCE_KEY, normalizedSnapshot.playbackVolumeBalance)
                .putBoolean(
                    PLAYBACK_VOLUME_NORMALIZATION_KEY,
                    normalizedSnapshot.playbackVolumeNormalizationEnabled
                )
                .putBoolean(
                    PLAYBACK_HIGH_RESOLUTION_OUTPUT_KEY,
                    normalizedSnapshot.playbackHighResolutionOutputEnabled
                )
                .putBoolean(
                    PLAYBACK_EQUALIZER_ENABLED_KEY,
                    normalizedSnapshot.playbackEqualizerEnabled
                )
                .putString(
                    PLAYBACK_EQUALIZER_PRESET_KEY,
                    normalizedSnapshot.playbackEqualizerPreset
                )
                .putString(PLAYBACK_EQUALIZER_LEVELS_KEY, encodedBandLevels)
                .putBoolean(
                    PLAYBACK_STOP_ON_BLUETOOTH_KEY,
                    normalizedSnapshot.stopOnBluetoothDisconnect
                )
                .putBoolean(
                    PLAYBACK_USB_EXCLUSIVE_KEY,
                    normalizedSnapshot.usbExclusivePlayback
                )
                .putString(
                    PLAYBACK_USB_EXCLUSIVE_DEVICE_KEY,
                    normalizedSnapshot.usbExclusiveDeviceKey
                )
                .putUsbExclusivePreferences(normalizedSnapshot.toUsbExclusivePreferences())
                .putBoolean(PLAYBACK_ALLOW_MIXED_KEY, normalizedSnapshot.allowMixedPlayback)
                .putBoolean(
                    PLAYBACK_PREEMPT_AUDIO_FOCUS_KEY,
                    normalizedSnapshot.preemptAudioFocus
                )
                .putLong(
                    PLAYBACK_CLOUD_MUSIC_LYRIC_OFFSET_KEY,
                    normalizedSnapshot.cloudMusicLyricDefaultOffsetMs
                )
                .putLong(
                    PLAYBACK_QQ_MUSIC_LYRIC_OFFSET_KEY,
                    normalizedSnapshot.qqMusicLyricDefaultOffsetMs
                )
                .putBoolean(PLAYBACK_LYRICON_ENABLED_KEY, normalizedSnapshot.lyriconEnabled)
                .putBoolean(
                    PLAYBACK_AMLL_LYRICS_ENABLED_KEY,
                    normalizedSnapshot.amllLyricsEnabled
                )
                .putLong(PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY, normalizedSnapshot.maxCacheSizeBytes)
        }
    }

internal fun Preferences.toPlaybackPreferenceSnapshot(): PlaybackPreferenceSnapshot {
    val legacyMobileDataQuality = this[SettingsKeys.MOBILE_DATA_DOWNGRADE_QUALITY]
    return PlaybackPreferenceSnapshot(
        audioQuality = this[SettingsKeys.AUDIO_QUALITY] ?: "exhigh",
        youtubeAudioQuality = this[SettingsKeys.YOUTUBE_AUDIO_QUALITY] ?: "high",
        biliAudioQuality = this[SettingsKeys.BILI_AUDIO_QUALITY] ?: "high",
        mobileDataFollowDefaultAudioQuality =
            this[SettingsKeys.MOBILE_DATA_FOLLOW_DEFAULT_AUDIO_QUALITY]
                ?: resolveLegacyMobileDataFollowDefaultAudioQuality(legacyMobileDataQuality)
                ?: true,
        mobileDataNeteaseAudioQuality = normalizeMobileDataNeteaseAudioQuality(
            this[SettingsKeys.MOBILE_DATA_NETEASE_AUDIO_QUALITY]
                ?: resolveLegacyMobileDataNeteaseAudioQuality(legacyMobileDataQuality)
        ),
        mobileDataYouTubeAudioQuality = normalizeMobileDataYouTubeAudioQuality(
            this[SettingsKeys.MOBILE_DATA_YOUTUBE_AUDIO_QUALITY]
                ?: resolveLegacyMobileDataYouTubeAudioQuality(legacyMobileDataQuality)
        ),
        mobileDataBiliAudioQuality = normalizeMobileDataBiliAudioQuality(
            this[SettingsKeys.MOBILE_DATA_BILI_AUDIO_QUALITY]
                ?: resolveLegacyMobileDataBiliAudioQuality(legacyMobileDataQuality)
        ),
        keepLastPlaybackProgress = this[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] ?: true,
        keepPlaybackModeState = this[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] ?: true,
        neteaseAutoSourceSwitch = this[SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH] ?: true,
        playbackFadeIn = this[SettingsKeys.PLAYBACK_FADE_IN] ?: false,
        playbackCrossfadeNext = this[SettingsKeys.PLAYBACK_CROSSFADE_NEXT] ?: false,
        sleepTimerFinishCurrentOnExpiry =
            this[SettingsKeys.PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY] ?: false,
        playbackFadeInDurationMs = this[SettingsKeys.PLAYBACK_FADE_IN_DURATION_MS] ?: 500L,
        playbackFadeOutDurationMs = this[SettingsKeys.PLAYBACK_FADE_OUT_DURATION_MS] ?: 500L,
        playbackCrossfadeInDurationMs =
            this[SettingsKeys.PLAYBACK_CROSSFADE_IN_DURATION_MS] ?: 500L,
        playbackCrossfadeOutDurationMs =
            this[SettingsKeys.PLAYBACK_CROSSFADE_OUT_DURATION_MS] ?: 500L,
        playbackSpeed = this[SettingsKeys.PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED,
        playbackPitch = this[SettingsKeys.PLAYBACK_PITCH] ?: DEFAULT_PLAYBACK_PITCH,
        playbackLoudnessGainMb =
            this[SettingsKeys.PLAYBACK_LOUDNESS_GAIN_MB] ?: DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
        playbackVolumeBalance =
            this[SettingsKeys.PLAYBACK_VOLUME_BALANCE] ?: DEFAULT_PLAYBACK_VOLUME_BALANCE,
        playbackVolumeNormalizationEnabled =
            this[SettingsKeys.PLAYBACK_VOLUME_NORMALIZATION_ENABLED] ?: false,
        playbackHighResolutionOutputEnabled =
            this[SettingsKeys.PLAYBACK_HIGH_RESOLUTION_OUTPUT_ENABLED] ?: false,
        playbackEqualizerEnabled = this[SettingsKeys.PLAYBACK_EQUALIZER_ENABLED] ?: false,
        playbackEqualizerPreset =
            this[SettingsKeys.PLAYBACK_EQUALIZER_PRESET] ?: PlaybackEqualizerPresetId.FLAT,
        playbackEqualizerCustomBandLevels = decodePlaybackEqualizerBandLevels(
            this[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS]
        ),
        stopOnBluetoothDisconnect = this[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] ?: true,
        usbExclusivePlayback = this[SettingsKeys.USB_EXCLUSIVE_PLAYBACK] ?: false,
        usbExclusiveDeviceKey = normalizeUsbExclusiveDeviceKey(
            this[SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY]
        ),
        usbExclusiveSampleRateMode =
            this[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE]
                ?: DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE,
        usbExclusiveBitDepthMode =
            this[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE]
                ?: DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE,
        usbExclusiveBufferProfile =
            this[SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE]
                ?: DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE,
        usbExclusiveUnsupportedFormatPolicy =
            this[SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY]
                ?: DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
        usbExclusiveSampleRateCompatibility =
            this[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY]
                ?: DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
        usbExclusiveBitDepthCompatibility =
            this[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY]
                ?: DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
        usbExclusiveChannelCompatibility =
            this[SettingsKeys.USB_EXCLUSIVE_CHANNEL_COMPATIBILITY]
                ?: DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
        usbExclusiveForegroundBufferMs =
            this[SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS]
                ?: DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
        usbExclusiveBackgroundBufferMs =
            this[SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS]
                ?: DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
        allowMixedPlayback = this[SettingsKeys.ALLOW_MIXED_PLAYBACK] ?: false,
        preemptAudioFocus = this[SettingsKeys.PREEMPT_AUDIO_FOCUS] ?: false,
        cloudMusicLyricDefaultOffsetMs =
            this[SettingsKeys.CLOUD_MUSIC_LYRIC_DEFAULT_OFFSET_MS]
                ?: DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
        qqMusicLyricDefaultOffsetMs =
            this[SettingsKeys.QQ_MUSIC_LYRIC_DEFAULT_OFFSET_MS]
                ?: DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
        lyriconEnabled = this[SettingsKeys.LYRICON_ENABLED] ?: false,
        amllLyricsEnabled = this[SettingsKeys.AMLL_LYRICS_ENABLED] ?: true,
        maxCacheSizeBytes =
            this[SettingsKeys.MAX_CACHE_SIZE_BYTES] ?: DEFAULT_MAX_CACHE_SIZE_BYTES
    ).sanitized()
}

private fun readCachedPlaybackPreferenceSnapshot(context: Context): PlaybackPreferenceSnapshot? {
    val prefs = context.getSharedPreferences(PLAYBACK_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PLAYBACK_SNAPSHOT_READY_KEY, false)) {
        return null
    }
    val cacheVersion = prefs.getInt(PLAYBACK_SNAPSHOT_SCHEMA_VERSION_KEY, 1)
    val legacyMobileDataQuality = prefs.getString(
        PLAYBACK_MOBILE_DATA_DOWNGRADE_QUALITY_KEY,
        null
    )
    val usbExclusivePreferences = migrateCachedUsbExclusivePreferencesIfNeeded(
        prefs = prefs,
        cacheVersion = cacheVersion
    )
    return PlaybackPreferenceSnapshot(
        audioQuality = prefs.getString(PLAYBACK_AUDIO_QUALITY_KEY, "exhigh") ?: "exhigh",
        youtubeAudioQuality =
            prefs.getString(PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY, "high") ?: "high",
        biliAudioQuality = prefs.getString(PLAYBACK_BILI_AUDIO_QUALITY_KEY, "high") ?: "high",
        mobileDataFollowDefaultAudioQuality = if (
            prefs.contains(PLAYBACK_MOBILE_DATA_FOLLOW_DEFAULT_AUDIO_QUALITY_KEY)
        ) {
            prefs.getBoolean(PLAYBACK_MOBILE_DATA_FOLLOW_DEFAULT_AUDIO_QUALITY_KEY, true)
        } else {
            resolveLegacyMobileDataFollowDefaultAudioQuality(legacyMobileDataQuality) ?: true
        },
        mobileDataNeteaseAudioQuality = normalizeMobileDataNeteaseAudioQuality(
            if (prefs.contains(PLAYBACK_MOBILE_DATA_NETEASE_AUDIO_QUALITY_KEY)) {
                prefs.getString(
                    PLAYBACK_MOBILE_DATA_NETEASE_AUDIO_QUALITY_KEY,
                    DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY
                )
            } else {
                resolveLegacyMobileDataNeteaseAudioQuality(legacyMobileDataQuality)
            }
        ),
        mobileDataYouTubeAudioQuality = normalizeMobileDataYouTubeAudioQuality(
            if (prefs.contains(PLAYBACK_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY_KEY)) {
                prefs.getString(
                    PLAYBACK_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY_KEY,
                    DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY
                )
            } else {
                resolveLegacyMobileDataYouTubeAudioQuality(legacyMobileDataQuality)
            }
        ),
        mobileDataBiliAudioQuality = normalizeMobileDataBiliAudioQuality(
            if (prefs.contains(PLAYBACK_MOBILE_DATA_BILI_AUDIO_QUALITY_KEY)) {
                prefs.getString(
                    PLAYBACK_MOBILE_DATA_BILI_AUDIO_QUALITY_KEY,
                    DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY
                )
            } else {
                resolveLegacyMobileDataBiliAudioQuality(legacyMobileDataQuality)
            }
        ),
        keepLastPlaybackProgress = prefs.getBoolean(PLAYBACK_KEEP_PROGRESS_KEY, true),
        keepPlaybackModeState = prefs.getBoolean(PLAYBACK_KEEP_MODE_STATE_KEY, true),
        neteaseAutoSourceSwitch =
            prefs.getBoolean(PLAYBACK_NETEASE_AUTO_SOURCE_SWITCH_KEY, true),
        playbackFadeIn = prefs.getBoolean(PLAYBACK_FADE_IN_KEY, false),
        playbackCrossfadeNext = prefs.getBoolean(PLAYBACK_CROSSFADE_NEXT_KEY, false),
        sleepTimerFinishCurrentOnExpiry = prefs.getBoolean(
            PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY_KEY,
            false
        ),
        playbackFadeInDurationMs = prefs.getLong(PLAYBACK_FADE_IN_DURATION_KEY, 500L),
        playbackFadeOutDurationMs = prefs.getLong(PLAYBACK_FADE_OUT_DURATION_KEY, 500L),
        playbackCrossfadeInDurationMs =
            prefs.getLong(PLAYBACK_CROSSFADE_IN_DURATION_KEY, 500L),
        playbackCrossfadeOutDurationMs =
            prefs.getLong(PLAYBACK_CROSSFADE_OUT_DURATION_KEY, 500L),
        playbackSpeed = prefs.getFloat(PLAYBACK_SPEED_KEY, DEFAULT_PLAYBACK_SPEED),
        playbackPitch = prefs.getFloat(PLAYBACK_PITCH_KEY, DEFAULT_PLAYBACK_PITCH),
        playbackLoudnessGainMb = prefs.getInt(
            PLAYBACK_LOUDNESS_KEY,
            DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
        ),
        playbackVolumeBalance = prefs.getFloat(
            PLAYBACK_VOLUME_BALANCE_KEY,
            DEFAULT_PLAYBACK_VOLUME_BALANCE
        ),
        playbackVolumeNormalizationEnabled = prefs.getBoolean(
            PLAYBACK_VOLUME_NORMALIZATION_KEY,
            false
        ),
        playbackHighResolutionOutputEnabled = prefs.getBoolean(
            PLAYBACK_HIGH_RESOLUTION_OUTPUT_KEY,
            false
        ),
        playbackEqualizerEnabled =
            prefs.getBoolean(PLAYBACK_EQUALIZER_ENABLED_KEY, false),
        playbackEqualizerPreset =
            prefs.getString(PLAYBACK_EQUALIZER_PRESET_KEY, PlaybackEqualizerPresetId.FLAT)
                ?: PlaybackEqualizerPresetId.FLAT,
        playbackEqualizerCustomBandLevels = decodePlaybackEqualizerBandLevels(
            prefs.getString(PLAYBACK_EQUALIZER_LEVELS_KEY, null)
        ),
        stopOnBluetoothDisconnect = prefs.getBoolean(PLAYBACK_STOP_ON_BLUETOOTH_KEY, true),
        usbExclusivePlayback = prefs.getBoolean(PLAYBACK_USB_EXCLUSIVE_KEY, false),
        usbExclusiveDeviceKey = normalizeUsbExclusiveDeviceKey(
            prefs.getString(
                PLAYBACK_USB_EXCLUSIVE_DEVICE_KEY,
                usbExclusivePreferences.selectedDeviceKey
            )
        ),
        usbExclusiveSampleRateMode = usbExclusivePreferences.sampleRateMode.storageValue,
        usbExclusiveBitDepthMode = usbExclusivePreferences.bitDepthMode.storageValue,
        usbExclusiveBufferProfile = usbExclusivePreferences.bufferProfile.storageValue,
        usbExclusiveUnsupportedFormatPolicy =
            usbExclusivePreferences.unsupportedFormatPolicy.storageValue,
        usbExclusiveSampleRateCompatibility =
            usbExclusivePreferences.sampleRateCompatibilityEnabled,
        usbExclusiveBitDepthCompatibility =
            usbExclusivePreferences.bitDepthCompatibilityEnabled,
        usbExclusiveChannelCompatibility =
            usbExclusivePreferences.channelCompatibilityEnabled,
        usbExclusiveForegroundBufferMs = usbExclusivePreferences.foregroundBufferMs,
        usbExclusiveBackgroundBufferMs = usbExclusivePreferences.backgroundBufferMs,
        allowMixedPlayback = prefs.getBoolean(PLAYBACK_ALLOW_MIXED_KEY, false),
        preemptAudioFocus = prefs.getBoolean(PLAYBACK_PREEMPT_AUDIO_FOCUS_KEY, false),
        cloudMusicLyricDefaultOffsetMs = prefs.getLong(
            PLAYBACK_CLOUD_MUSIC_LYRIC_OFFSET_KEY,
            DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
        ),
        qqMusicLyricDefaultOffsetMs = prefs.getLong(
            PLAYBACK_QQ_MUSIC_LYRIC_OFFSET_KEY,
            DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
        ),
        lyriconEnabled = prefs.getBoolean(PLAYBACK_LYRICON_ENABLED_KEY, false),
        amllLyricsEnabled = prefs.getBoolean(PLAYBACK_AMLL_LYRICS_ENABLED_KEY, true),
        maxCacheSizeBytes = prefs.getLong(
            PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY,
            DEFAULT_MAX_CACHE_SIZE_BYTES
        )
    ).sanitized()
}

private fun migrateCachedUsbExclusivePreferencesIfNeeded(
    prefs: android.content.SharedPreferences,
    cacheVersion: Int
): UsbExclusivePreferences {
    val preferences = prefs.readUsbExclusivePreferences()
    val shouldMigrateLegacyDefault = cacheVersion < PLAYBACK_SNAPSHOT_SCHEMA_VERSION &&
        preferences.sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
        preferences.bitDepthMode == UsbExclusiveBitDepthMode.AUTO &&
        preferences.bufferProfile == UsbExclusiveBufferProfile.BALANCED &&
        preferences.unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK
    val migrated = if (shouldMigrateLegacyDefault) {
        preferences.copy(
            unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        )
    } else {
        preferences
    }
    if (cacheVersion < PLAYBACK_SNAPSHOT_SCHEMA_VERSION) {
        prefs.edit {
            putInt(PLAYBACK_SNAPSHOT_SCHEMA_VERSION_KEY, PLAYBACK_SNAPSHOT_SCHEMA_VERSION)
            putUsbExclusivePreferences(migrated)
        }
    }
    return migrated
}
