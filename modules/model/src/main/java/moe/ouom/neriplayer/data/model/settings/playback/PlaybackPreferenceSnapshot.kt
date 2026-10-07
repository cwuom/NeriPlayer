package moe.ouom.neriplayer.data.model.settings.playback

import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LYRIC_SOURCE
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BIT_PERFECT
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY
import moe.ouom.neriplayer.data.model.settings.usb.DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS

data class PlaybackPreferenceSnapshot(
    val audioQuality: String = "exhigh",
    val youtubeAudioQuality: String = "high",
    val biliAudioQuality: String = "high",
    val mobileDataFollowDefaultAudioQuality: Boolean = true,
    val mobileDataNeteaseAudioQuality: String = DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY,
    val mobileDataYouTubeAudioQuality: String = DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY,
    val mobileDataBiliAudioQuality: String = DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY,
    val keepLastPlaybackProgress: Boolean = true,
    val rememberLongFormPlaybackProgress: Boolean = true,
    val keepPlaybackModeState: Boolean = true,
    val neteaseAutoSourceSwitch: Boolean = false,
    val neteaseLocalSourceFallback: Boolean = false,
    val playbackFadeIn: Boolean = true,
    val playbackCrossfadeNext: Boolean = true,
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
    val audioEffectsSettingsJson: String = "",
    val stopOnBluetoothDisconnect: Boolean = true,
    val usbExclusivePlayback: Boolean = false,
    val usbExclusiveDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY,
    val usbExclusiveSampleRateMode: String = DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE,
    val usbExclusiveBitDepthMode: String = DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE,
    val usbExclusiveBitPerfect: Boolean = DEFAULT_USB_EXCLUSIVE_BIT_PERFECT,
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
    val usbExclusiveVolumeRiskThresholdDbfs: Int =
        DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS,
    val allowMixedPlayback: Boolean = false,
    val preemptAudioFocus: Boolean = false,
    val cloudMusicLyricDefaultOffsetMs: Long = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
    val qqMusicLyricDefaultOffsetMs: Long = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
    val kugouLyricDefaultOffsetMs: Long = DEFAULT_KUGOU_LYRIC_OFFSET_MS,
    val lrclibLyricDefaultOffsetMs: Long = DEFAULT_LRCLIB_LYRIC_OFFSET_MS,
    val amllTtmlLyricDefaultOffsetMs: Long = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS,
    val lyriconEnabled: Boolean = false,
    val amllLyricsEnabled: Boolean = true,
    val preferWordTimedLyrics: Boolean = true,
    val defaultLyricSource: String = DEFAULT_LYRIC_SOURCE,
    val maxCacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES
)
