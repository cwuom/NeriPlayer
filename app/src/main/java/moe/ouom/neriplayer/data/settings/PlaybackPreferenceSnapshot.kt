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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.decodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.encodePlaybackEqualizerBandLevels
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import androidx.core.content.edit

private const val PLAYBACK_SNAPSHOT_PREFS = "playback_snapshot_cache"
private const val PLAYBACK_SNAPSHOT_READY_KEY = "ready"
private const val PLAYBACK_AUDIO_QUALITY_KEY = "audio_quality"
private const val PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY = "youtube_audio_quality"
private const val PLAYBACK_BILI_AUDIO_QUALITY_KEY = "bili_audio_quality"
private const val PLAYBACK_KEEP_PROGRESS_KEY = "keep_last_playback_progress"
private const val PLAYBACK_KEEP_MODE_STATE_KEY = "keep_playback_mode_state"
private const val PLAYBACK_FADE_IN_KEY = "playback_fade_in"
private const val PLAYBACK_CROSSFADE_NEXT_KEY = "playback_crossfade_next"
private const val PLAYBACK_FADE_IN_DURATION_KEY = "playback_fade_in_duration_ms"
private const val PLAYBACK_FADE_OUT_DURATION_KEY = "playback_fade_out_duration_ms"
private const val PLAYBACK_CROSSFADE_IN_DURATION_KEY = "playback_crossfade_in_duration_ms"
private const val PLAYBACK_CROSSFADE_OUT_DURATION_KEY = "playback_crossfade_out_duration_ms"
private const val PLAYBACK_SPEED_KEY = "playback_speed"
private const val PLAYBACK_PITCH_KEY = "playback_pitch"
private const val PLAYBACK_LOUDNESS_KEY = "playback_loudness_gain_mb"
private const val PLAYBACK_EQUALIZER_ENABLED_KEY = "playback_equalizer_enabled"
private const val PLAYBACK_EQUALIZER_PRESET_KEY = "playback_equalizer_preset"
private const val PLAYBACK_EQUALIZER_LEVELS_KEY = "playback_equalizer_custom_band_levels"
private const val PLAYBACK_STOP_ON_BLUETOOTH_KEY = "stop_on_bluetooth_disconnect"
private const val PLAYBACK_ALLOW_MIXED_KEY = "allow_mixed_playback"
private const val PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY = "max_cache_size_bytes"
private const val DEFAULT_MAX_CACHE_SIZE_BYTES = 1024L * 1024 * 1024

data class PlaybackPreferenceSnapshot(
    val audioQuality: String = "exhigh",
    val youtubeAudioQuality: String = "very_high",
    val biliAudioQuality: String = "high",
    val keepLastPlaybackProgress: Boolean = true,
    val keepPlaybackModeState: Boolean = true,
    val playbackFadeIn: Boolean = false,
    val playbackCrossfadeNext: Boolean = false,
    val playbackFadeInDurationMs: Long = 500L,
    val playbackFadeOutDurationMs: Long = 500L,
    val playbackCrossfadeInDurationMs: Long = 500L,
    val playbackCrossfadeOutDurationMs: Long = 500L,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val playbackPitch: Float = DEFAULT_PLAYBACK_PITCH,
    val playbackLoudnessGainMb: Int = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
    val playbackEqualizerEnabled: Boolean = false,
    val playbackEqualizerPreset: String = PlaybackEqualizerPresetId.FLAT,
    val playbackEqualizerCustomBandLevels: List<Int> = emptyList(),
    val stopOnBluetoothDisconnect: Boolean = true,
    val allowMixedPlayback: Boolean = false,
    val maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
) {
    fun sanitized(): PlaybackPreferenceSnapshot {
        return copy(
            audioQuality = audioQuality.trim().ifBlank { "exhigh" },
            youtubeAudioQuality = youtubeAudioQuality.trim().ifBlank { "very_high" },
            biliAudioQuality = biliAudioQuality.trim().ifBlank { "high" },
            playbackFadeInDurationMs = playbackFadeInDurationMs.coerceAtLeast(0L),
            playbackFadeOutDurationMs = playbackFadeOutDurationMs.coerceAtLeast(0L),
            playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs.coerceAtLeast(0L),
            playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs.coerceAtLeast(0L),
            playbackSpeed = normalizePlaybackSpeed(playbackSpeed),
            playbackPitch = normalizePlaybackPitch(playbackPitch),
            playbackLoudnessGainMb = normalizePlaybackLoudnessGainMb(playbackLoudnessGainMb),
            playbackEqualizerPreset = playbackEqualizerPreset.trim()
                .ifBlank { PlaybackEqualizerPresetId.FLAT },
            maxCacheSizeBytes = maxCacheSizeBytes.coerceAtLeast(0L)
        )
    }

    fun toPlaybackSoundConfig(): PlaybackSoundConfig {
        val normalizedSnapshot = sanitized()
        return PlaybackSoundConfig(
            speed = normalizedSnapshot.playbackSpeed,
            pitch = normalizedSnapshot.playbackPitch,
            loudnessGainMb = normalizedSnapshot.playbackLoudnessGainMb,
            equalizerEnabled = normalizedSnapshot.playbackEqualizerEnabled,
            presetId = normalizedSnapshot.playbackEqualizerPreset,
            customBandLevelsMb = normalizedSnapshot.playbackEqualizerCustomBandLevels
        )
    }
}

fun readPlaybackPreferenceSnapshotSync(context: Context): PlaybackPreferenceSnapshot {
    readCachedPlaybackPreferenceSnapshot(context)?.let { return it }

    return runBlocking {
        context.dataStore.data.first().toPlaybackPreferenceSnapshot()
    }.also { snapshot ->
        persistPlaybackPreferenceSnapshot(context, snapshot)
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
                .putString(PLAYBACK_AUDIO_QUALITY_KEY, normalizedSnapshot.audioQuality)
                .putString(
                    PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY,
                    normalizedSnapshot.youtubeAudioQuality
                )
                .putString(PLAYBACK_BILI_AUDIO_QUALITY_KEY, normalizedSnapshot.biliAudioQuality)
                .putBoolean(PLAYBACK_KEEP_PROGRESS_KEY, normalizedSnapshot.keepLastPlaybackProgress)
                .putBoolean(PLAYBACK_KEEP_MODE_STATE_KEY, normalizedSnapshot.keepPlaybackModeState)
                .putBoolean(PLAYBACK_FADE_IN_KEY, normalizedSnapshot.playbackFadeIn)
                .putBoolean(PLAYBACK_CROSSFADE_NEXT_KEY, normalizedSnapshot.playbackCrossfadeNext)
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
                .putBoolean(PLAYBACK_ALLOW_MIXED_KEY, normalizedSnapshot.allowMixedPlayback)
                .putLong(PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY, normalizedSnapshot.maxCacheSizeBytes)
        }
}

internal fun Preferences.toPlaybackPreferenceSnapshot(): PlaybackPreferenceSnapshot {
    return PlaybackPreferenceSnapshot(
        audioQuality = this[SettingsKeys.AUDIO_QUALITY] ?: "exhigh",
        youtubeAudioQuality = this[SettingsKeys.YOUTUBE_AUDIO_QUALITY] ?: "very_high",
        biliAudioQuality = this[SettingsKeys.BILI_AUDIO_QUALITY] ?: "high",
        keepLastPlaybackProgress = this[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] ?: true,
        keepPlaybackModeState = this[SettingsKeys.KEEP_PLAYBACK_MODE_STATE] ?: true,
        playbackFadeIn = this[SettingsKeys.PLAYBACK_FADE_IN] ?: false,
        playbackCrossfadeNext = this[SettingsKeys.PLAYBACK_CROSSFADE_NEXT] ?: false,
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
        playbackEqualizerEnabled = this[SettingsKeys.PLAYBACK_EQUALIZER_ENABLED] ?: false,
        playbackEqualizerPreset =
            this[SettingsKeys.PLAYBACK_EQUALIZER_PRESET] ?: PlaybackEqualizerPresetId.FLAT,
        playbackEqualizerCustomBandLevels = decodePlaybackEqualizerBandLevels(
            this[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS]
        ),
        stopOnBluetoothDisconnect = this[SettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT] ?: true,
        allowMixedPlayback = this[SettingsKeys.ALLOW_MIXED_PLAYBACK] ?: false,
        maxCacheSizeBytes =
            this[SettingsKeys.MAX_CACHE_SIZE_BYTES] ?: DEFAULT_MAX_CACHE_SIZE_BYTES
    ).sanitized()
}

private fun readCachedPlaybackPreferenceSnapshot(context: Context): PlaybackPreferenceSnapshot? {
    val prefs = context.getSharedPreferences(PLAYBACK_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PLAYBACK_SNAPSHOT_READY_KEY, false)) {
        return null
    }
    return PlaybackPreferenceSnapshot(
        audioQuality = prefs.getString(PLAYBACK_AUDIO_QUALITY_KEY, "exhigh") ?: "exhigh",
        youtubeAudioQuality =
            prefs.getString(PLAYBACK_YOUTUBE_AUDIO_QUALITY_KEY, "very_high") ?: "very_high",
        biliAudioQuality = prefs.getString(PLAYBACK_BILI_AUDIO_QUALITY_KEY, "high") ?: "high",
        keepLastPlaybackProgress = prefs.getBoolean(PLAYBACK_KEEP_PROGRESS_KEY, true),
        keepPlaybackModeState = prefs.getBoolean(PLAYBACK_KEEP_MODE_STATE_KEY, true),
        playbackFadeIn = prefs.getBoolean(PLAYBACK_FADE_IN_KEY, false),
        playbackCrossfadeNext = prefs.getBoolean(PLAYBACK_CROSSFADE_NEXT_KEY, false),
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
        playbackEqualizerEnabled =
            prefs.getBoolean(PLAYBACK_EQUALIZER_ENABLED_KEY, false),
        playbackEqualizerPreset =
            prefs.getString(PLAYBACK_EQUALIZER_PRESET_KEY, PlaybackEqualizerPresetId.FLAT)
                ?: PlaybackEqualizerPresetId.FLAT,
        playbackEqualizerCustomBandLevels = decodePlaybackEqualizerBandLevels(
            prefs.getString(PLAYBACK_EQUALIZER_LEVELS_KEY, null)
        ),
        stopOnBluetoothDisconnect = prefs.getBoolean(PLAYBACK_STOP_ON_BLUETOOTH_KEY, true),
        allowMixedPlayback = prefs.getBoolean(PLAYBACK_ALLOW_MIXED_KEY, false),
        maxCacheSizeBytes = prefs.getLong(
            PLAYBACK_MAX_CACHE_SIZE_BYTES_KEY,
            DEFAULT_MAX_CACHE_SIZE_BYTES
        )
    ).sanitized()
}
