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
 * File: moe.ouom.neriplayer.data.settings/SettingsStore
 * Updated: 2026/3/23
 */

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.dataStore by preferencesDataStore("settings")

object SettingsKeys {
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val FORCE_DARK = booleanPreferencesKey("force_dark")
    val FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
    val SHOW_COVER_SOURCE_BADGE = booleanPreferencesKey("show_cover_source_badge")
    val NOWPLAYING_TOOLBAR_DOCK_ENABLED =
        booleanPreferencesKey("nowplaying_toolbar_dock_enabled")
    val NOWPLAYING_SHOW_TITLE = booleanPreferencesKey("nowplaying_show_title")
    val NOWPLAYING_PROGRESS_SHOW_QUALITY_SWITCH =
        booleanPreferencesKey("nowplaying_progress_show_quality_switch")
    val NOWPLAYING_PROGRESS_SHOW_AUDIO_CODEC =
        booleanPreferencesKey("nowplaying_progress_show_audio_codec")
    val NOWPLAYING_PROGRESS_SHOW_AUDIO_SPEC =
        booleanPreferencesKey("nowplaying_progress_show_audio_spec")
    val SILENT_GITHUB_SYNC_FAILURE = booleanPreferencesKey("silent_github_sync_failure")
    val DISCLAIMER_ACCEPTED_V2 = booleanPreferencesKey("disclaimer_accepted_v2")
    val STARTUP_ONBOARDING_COMPLETED = booleanPreferencesKey("startup_onboarding_completed")
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
    val DOWNLOAD_DIRECTORY_URI = stringPreferencesKey("download_directory_uri")
    val DOWNLOAD_DIRECTORY_LABEL = stringPreferencesKey("download_directory_label")
    val DOWNLOAD_FILE_NAME_TEMPLATE = stringPreferencesKey("download_file_name_template")
    val BACKGROUND_IMAGE_BLUR = floatPreferencesKey("background_image_blur")
    val BACKGROUND_IMAGE_ALPHA = floatPreferencesKey("background_image_alpha")
    val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
    val MAX_CACHE_SIZE_BYTES = longPreferencesKey("max_cache_size_bytes")
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
    val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    val PLAYBACK_PITCH = floatPreferencesKey("playback_pitch")
    val PLAYBACK_EQUALIZER_ENABLED = booleanPreferencesKey("playback_equalizer_enabled")
    val PLAYBACK_EQUALIZER_PRESET = stringPreferencesKey("playback_equalizer_preset")
    val PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS =
        stringPreferencesKey("playback_equalizer_custom_band_levels")
    val PLAYBACK_LOUDNESS_GAIN_MB = intPreferencesKey("playback_loudness_gain_mb")
    val KEEP_LAST_PLAYBACK_PROGRESS = booleanPreferencesKey("keep_last_playback_progress")
    val KEEP_PLAYBACK_MODE_STATE = booleanPreferencesKey("keep_playback_mode_state")
    val STOP_ON_BLUETOOTH_DISCONNECT = booleanPreferencesKey("stop_on_bluetooth_disconnect")
    val ALLOW_MIXED_PLAYBACK = booleanPreferencesKey("allow_mixed_playback")
    val INTERNATIONALIZATION_ENABLED = booleanPreferencesKey("internationalization_enabled")
}
