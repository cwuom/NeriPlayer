package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.ksp.annotations.AutoSetting
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import moe.ouom.neriplayer.ksp.annotations.autoSetting
import moe.ouom.neriplayer.ksp.annotations.autoStringSetting
import moe.ouom.neriplayer.ksp.annotations.autoSwitchSetting

abstract class PlaybackSettingsSection protected constructor() {
    @AutoSetting(
        key = "playback_fade_in",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 10,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackFadeIn = autoSetting(
        titleRes = R.string.settings_playback_fade_in,
        descriptionRes = R.string.settings_playback_fade_in_desc
    )

    @AutoSetting(
        key = "playback_crossfade_next",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 20,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackCrossfadeNext = autoSetting(
        titleRes = R.string.settings_playback_crossfade_next,
        descriptionRes = R.string.settings_playback_crossfade_next_desc
    )

    @AutoSetting(
        key = "playback_sleep_timer_finish_current_on_expiry",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 25,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val sleepTimerFinishCurrentOnExpiry = autoSetting(
        titleRes = R.string.settings_playback_sleep_timer_finish_current_on_expiry,
        descriptionRes = R.string.settings_playback_sleep_timer_finish_current_on_expiry_desc
    )

    @AutoSetting(
        key = "playback_fade_in_duration_ms",
        type = SettingValueType.Long,
        defaultLong = 500L,
        order = 30,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackFadeInDurationMs = autoSetting(
        titleRes = R.string.settings_playback_fade_in_duration
    )

    @AutoSetting(
        key = "playback_fade_out_duration_ms",
        type = SettingValueType.Long,
        defaultLong = 500L,
        order = 40,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackFadeOutDurationMs = autoSetting(
        titleRes = R.string.settings_playback_fade_out_duration
    )

    @AutoSetting(
        key = "playback_crossfade_in_duration_ms",
        type = SettingValueType.Long,
        defaultLong = 500L,
        order = 50,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackCrossfadeInDurationMs = autoSetting(
        titleRes = R.string.settings_playback_crossfade_in_duration
    )

    @AutoSetting(
        key = "playback_crossfade_out_duration_ms",
        type = SettingValueType.Long,
        defaultLong = 500L,
        order = 60,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackCrossfadeOutDurationMs = autoSetting(
        titleRes = R.string.settings_playback_crossfade_out_duration
    )

    @AutoSetting(
        key = "playback_speed",
        type = SettingValueType.Float,
        defaultFloat = DEFAULT_PLAYBACK_SPEED,
        order = 70,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackSpeed = autoSetting(
        titleRes = R.string.player_play
    )

    @AutoSetting(
        key = "playback_pitch",
        type = SettingValueType.Float,
        defaultFloat = DEFAULT_PLAYBACK_PITCH,
        order = 80,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackPitch = autoSetting(
        titleRes = R.string.settings_playback
    )

    @AutoSetting(
        key = "playback_equalizer_enabled",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 90,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackEqualizerEnabled = autoSetting(
        titleRes = R.string.settings_playback
    )

    @AutoSetting(
        key = "playback_equalizer_preset",
        type = SettingValueType.String,
        defaultString = PlaybackEqualizerPresetId.FLAT,
        order = 100,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackEqualizerPreset = autoSetting(
        titleRes = R.string.settings_playback
    )

    @AutoSetting(
        key = "playback_equalizer_custom_band_levels",
        type = SettingValueType.String,
        defaultString = "",
        order = 110,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackEqualizerCustomBandLevels = autoSetting(
        titleRes = R.string.settings_playback
    )

    @AutoSetting(
        key = "playback_loudness_gain_mb",
        type = SettingValueType.Int,
        defaultInt = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
        order = 120,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackLoudnessGainMb = autoSetting(
        titleRes = R.string.settings_playback
    )

    @AutoSetting(
        key = "playback_volume_normalization_enabled",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 123,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackVolumeNormalizationEnabled = autoSetting(
        titleRes = R.string.settings_playback_volume_normalization,
        descriptionRes = R.string.settings_playback_volume_normalization_desc
    )

    @AutoSetting(
        key = "playback_high_resolution_output_enabled",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 124,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackHighResolutionOutputEnabled = autoSetting(
        titleRes = R.string.settings_playback_high_resolution_output,
        descriptionRes = R.string.settings_playback_high_resolution_output_desc
    )

    @AutoSetting(
        key = "playback_volume_balance",
        type = SettingValueType.Float,
        defaultFloat = DEFAULT_PLAYBACK_VOLUME_BALANCE,
        order = 125,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val playbackVolumeBalance = autoSetting(
        titleRes = R.string.settings_playback_volume_balance,
        descriptionRes = R.string.settings_playback_volume_balance_desc
    )

    @AutoSetting(
        key = "keep_last_playback_progress",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 130,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val keepLastPlaybackProgress = autoSetting(
        titleRes = R.string.settings_keep_last_playback_progress,
        descriptionRes = R.string.settings_keep_last_playback_progress_desc
    )

    @AutoSetting(
        key = "remember_long_form_playback_progress",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 131,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val rememberLongFormPlaybackProgress = autoSetting(
        titleRes = R.string.settings_remember_long_form_playback_progress,
        descriptionRes = R.string.settings_remember_long_form_playback_progress_desc
    )

    @AutoSetting(
        key = "netease_auto_source_switch",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 135,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val neteaseAutoSourceSwitch = autoSetting(
        titleRes = R.string.settings_netease_auto_source_switch,
        descriptionRes = R.string.settings_netease_auto_source_switch_desc,
        iconRes = R.drawable.ic_bilibili
    )

    @AutoSetting(
        key = "netease_local_source_fallback",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 136,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val neteaseLocalSourceFallback = autoSetting(
        titleRes = R.string.settings_netease_local_source_fallback,
        descriptionRes = R.string.settings_netease_local_source_fallback_desc
    )

    @AutoSetting(
        key = "youtube_playback_source",
        type = SettingValueType.String,
        defaultString = DEFAULT_YOUTUBE_PLAYBACK_SOURCE,
        order = 137,
        ui = SettingUiType.Custom,
        normalizer = YouTubePlaybackSourcePreferencePolicy::class
    )
    val youtubePlaybackSource = autoStringSetting(
        key = "youtube_playback_source",
        defaultValue = DEFAULT_YOUTUBE_PLAYBACK_SOURCE,
        titleRes = R.string.settings_youtube_playback_source,
        descriptionRes = R.string.settings_youtube_playback_source_desc,
        iconRes = R.drawable.ic_youtube
    )

    @AutoSetting(order = 138)
    val biliSponsorBlockEnabled = autoSwitchSetting(
        key = "bili_sponsor_block_enabled",
        defaultValue = false,
        titleRes = R.string.settings_bili_sponsor_block,
        descriptionRes = R.string.settings_bili_sponsor_block_desc,
        icon = AutoSettingIcon.AdsClick
    )

    @AutoSetting(
        key = "keep_playback_mode_state",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 140,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val keepPlaybackModeState = autoSetting(
        titleRes = R.string.settings_keep_playback_mode_state,
        descriptionRes = R.string.settings_keep_playback_mode_state_desc
    )

    @AutoSetting(
        key = "stop_on_bluetooth_disconnect",
        type = SettingValueType.Boolean,
        defaultBoolean = true,
        order = 150,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val stopOnBluetoothDisconnect = autoSetting(
        titleRes = R.string.settings_stop_on_bluetooth_disconnect,
        descriptionRes = R.string.settings_stop_on_bluetooth_disconnect_desc
    )

    @AutoSetting(
        key = "usb_exclusive_playback",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 160,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusivePlayback = autoSetting(
        titleRes = R.string.settings_usb_exclusive_playback,
        descriptionRes = R.string.settings_usb_exclusive_playback_desc
    )

    @AutoSetting(
        key = "usb_exclusive_device_key",
        type = SettingValueType.String,
        defaultString = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY,
        order = 161,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveDeviceKey = Unit

    @AutoSetting(
        key = "usb_exclusive_sample_rate_mode",
        type = SettingValueType.String,
        defaultString = DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE,
        order = 162,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveSampleRateMode = Unit

    @AutoSetting(
        key = "usb_exclusive_bit_depth_mode",
        type = SettingValueType.String,
        defaultString = DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE,
        order = 163,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveBitDepthMode = Unit

    @AutoSetting(
        key = "usb_exclusive_bit_perfect",
        type = SettingValueType.Boolean,
        defaultBoolean = DEFAULT_USB_EXCLUSIVE_BIT_PERFECT,
        order = 172,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveBitPerfect = Unit

    @AutoSetting(
        key = "usb_exclusive_buffer_profile",
        type = SettingValueType.String,
        defaultString = DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE,
        order = 164,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveBufferProfile = Unit

    @AutoSetting(
        key = "usb_exclusive_unsupported_format_policy",
        type = SettingValueType.String,
        defaultString = DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
        order = 165,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveUnsupportedFormatPolicy = Unit

    @AutoSetting(
        key = "usb_exclusive_sample_rate_compatibility",
        type = SettingValueType.Boolean,
        defaultBoolean = DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
        order = 166,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveSampleRateCompatibility = Unit

    @AutoSetting(
        key = "usb_exclusive_bit_depth_compatibility",
        type = SettingValueType.Boolean,
        defaultBoolean = DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
        order = 167,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveBitDepthCompatibility = Unit

    @AutoSetting(
        key = "usb_exclusive_channel_compatibility",
        type = SettingValueType.Boolean,
        defaultBoolean = DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
        order = 168,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveChannelCompatibility = Unit

    @AutoSetting(
        key = "usb_exclusive_foreground_buffer_ms",
        type = SettingValueType.Int,
        defaultInt = DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
        order = 169,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveForegroundBufferMs = Unit

    @AutoSetting(
        key = "usb_exclusive_background_buffer_ms",
        type = SettingValueType.Int,
        defaultInt = DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
        order = 170,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveBackgroundBufferMs = Unit

    @AutoSetting(
        key = "usb_exclusive_volume_risk_threshold_dbfs",
        type = SettingValueType.Int,
        defaultInt = DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS,
        order = 171,
        access = SettingAccessMode.KeyOnly
    )
    val usbExclusiveVolumeRiskThresholdDbfs = Unit

    @AutoSetting(
        key = "allow_mixed_playback",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 180,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val allowMixedPlayback = autoSetting(
        titleRes = R.string.settings_allow_mixed_playback,
        descriptionRes = R.string.settings_allow_mixed_playback_desc
    )

    @AutoSetting(
        key = "preempt_audio_focus",
        type = SettingValueType.Boolean,
        defaultBoolean = false,
        order = 180,
        ui = SettingUiType.Custom,
        access = SettingAccessMode.KeyOnly
    )
    val preemptAudioFocus = autoSetting(
        titleRes = R.string.settings_preempt_audio_focus,
        descriptionRes = R.string.settings_preempt_audio_focus_desc
    )
}
