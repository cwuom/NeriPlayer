package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.ksp.annotations.AutoSetting
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsCatalog
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsSection
import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import moe.ouom.neriplayer.ksp.annotations.autoSetting

    /*
     * 设置项统一登记表
     *
     * 新增 DataStore 设置时优先只改这里，KSP 会自动生成 SettingsKeys、备份白名单、
     * AutoSettingsRepository、section 常量、section scope 和可复用元数据
     *
     * 放置规则：
     * - 能被通用开关直接保存的 Boolean，用 ui = Switch 和默认 access
     * - 有弹窗、Slider、平台可用性判断、多个设置互斥或额外持久化副作用的，用 ui = Custom
     * - 启动快照、主题快照、播放快照、路径权限这类不能绕过业务 setter 的，用 access = KeyOnly
     * - 分类用嵌套 object 表达，调用侧优先用 AutoSettingsScopes.display 这种 scope，不要再手写 "display"
     * - 需要在旧代码保持原常量名的，用 constantName 固定生成名
     * - 原本就是 drawable 的图标才写 iconRes，Material 图标用 icon 保留原 UI
     */
@AutoSettingsCatalog
object AutoSettingsSchema {
    /*
     * 基础行为
     *
     * 放和整 App 行为相关、但不属于某个播放/下载子系统的设置
     * 主题即时切换、首次启动状态和国际化检测有额外副作用，不能让通用 setter 绕过
     */
    @AutoSettingsSection(
        titleRes = "settings_title",
        descriptionRes = "settings_restart_hint",
        order = 10
    )
    object general {
        @AutoSetting(
            key = "dynamic_color",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_dynamic_color",
            descriptionRes = "settings_dynamic_color_desc",
            order = 10,
            ui = SettingUiType.Switch,
            access = SettingAccessMode.KeyOnly
        )
        val dynamicColor = autoSetting(icon = AutoSettingIcon.Brightness4)

        @AutoSetting(
            key = "force_dark",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 20,
            access = SettingAccessMode.KeyOnly
        )
        val forceDark = Unit

        @AutoSetting(
            key = "follow_system_dark",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 30,
            access = SettingAccessMode.KeyOnly
        )
        val followSystemDark = Unit

        @AutoSetting(
            key = "haptic_feedback_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_haptic",
            descriptionRes = "settings_haptic_desc",
            order = 40,
            ui = SettingUiType.Switch
        )
        val hapticFeedbackEnabled = autoSetting(icon = AutoSettingIcon.AdsClick)

        @AutoSetting(
            key = "dev_mode_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            constantName = "KEY_DEV_MODE",
            order = 50,
            ui = SettingUiType.Custom
        )
        val devModeEnabled = Unit

        @AutoSetting(
            key = "disclaimer_accepted_v2",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 60,
            access = SettingAccessMode.KeyOnly
        )
        val disclaimerAcceptedV2 = Unit

        @AutoSetting(
            key = "startup_onboarding_completed",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 70,
            access = SettingAccessMode.KeyOnly
        )
        val startupOnboardingCompleted = Unit

        @AutoSetting(
            key = "internationalization_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_internationalization",
            descriptionRes = "settings_internationalization_desc",
            order = 80,
            access = SettingAccessMode.KeyOnly
        )
        val internationalizationEnabled = autoSetting(iconRes = R.drawable.ic_i18n)
    }

    /*
     * 主题取色
     *
     * 只放主题色和调色盘这类纯视觉主题数据
     * 当前主题写入还会更新启动快照或触发页面动画，因此先保留 KeyOnly
     */
    @AutoSettingsSection(
        titleRes = "settings_theme_color",
        descriptionRes = "settings_theme_color_desc",
        order = 20
    )
    object theme {
        @AutoSetting(
            key = "theme_seed_color",
            type = SettingValueType.String,
            defaultString = ThemeDefaults.DEFAULT_SEED_COLOR_HEX,
            order = 10,
            access = SettingAccessMode.KeyOnly
        )
        val themeSeedColor = Unit

        @AutoSetting(
            key = "theme_color_palette_v2",
            type = SettingValueType.String,
            defaultString = "",
            constantName = "THEME_COLOR_PALETTE",
            order = 20,
            access = SettingAccessMode.KeyOnly
        )
        val themeColorPalette = Unit
    }

    /*
     * 音质偏好
     *
     * 放各平台默认音质选择，UI 通常是选项弹窗，不是简单开关
     * 写入后还要同步播放启动快照，所以这里统一标记为 Custom + KeyOnly
     */
    @AutoSettingsSection(
        titleRes = "settings_audio_quality",
        descriptionRes = "settings_audio_quality_expand",
        order = 30
    )
    object audioQuality {
        @AutoSetting(
            key = "audio_quality",
            type = SettingValueType.String,
            defaultString = "exhigh",
            titleRes = "quality_netease_default",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val audioQuality = autoSetting(iconRes = R.drawable.ic_netease_cloud_music)

        @AutoSetting(
            key = "youtube_audio_quality",
            type = SettingValueType.String,
            defaultString = "very_high",
            titleRes = "quality_youtube_default",
            order = 20,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val youtubeAudioQuality = autoSetting(iconRes = R.drawable.ic_youtube)

        @AutoSetting(
            key = "bili_audio_quality",
            type = SettingValueType.String,
            defaultString = "high",
            titleRes = "quality_bili_default",
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val biliAudioQuality = autoSetting(iconRes = R.drawable.ic_bilibili)
    }

    /*
     * 个性化入口
     *
     * 放首页入口、首页卡片、输入体验这类不直接影响播放器内核的偏好
     * 复杂首页卡片会根据国际化状态换文案，保留手写 UI 但元数据仍由这里生成
     */
    @AutoSettingsSection(
        titleRes = "settings_personalization",
        descriptionRes = "settings_personalization_expand",
        order = 40
    )
    object personalization {
        @AutoSetting(
            key = "default_start_destination",
            type = SettingValueType.String,
            defaultString = "home",
            titleRes = "settings_default_start_screen",
            descriptionRes = "settings_default_start_screen_desc",
            order = 10,
            ui = SettingUiType.Custom
        )
        val defaultStartDestination = Unit

        @AutoSetting(
            key = "auto_show_keyboard",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_auto_show_keyboard",
            descriptionRes = "settings_auto_show_keyboard_desc",
            order = 20,
            ui = SettingUiType.Switch
        )
        val autoShowKeyboard = autoSetting(icon = AutoSettingIcon.Keyboard)

        @AutoSetting(
            key = "home_card_continue",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "player_continue",
            order = 30,
            ui = SettingUiType.Custom
        )
        val homeCardContinue = Unit

        @AutoSetting(
            key = "home_card_trending",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_home_cards",
            order = 40,
            ui = SettingUiType.Custom
        )
        val homeCardTrending = Unit

        @AutoSetting(
            key = "home_card_radar",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_home_cards",
            order = 50,
            ui = SettingUiType.Custom
        )
        val homeCardRadar = Unit

        @AutoSetting(
            key = "home_card_recommended",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_home_cards",
            order = 60,
            ui = SettingUiType.Custom
        )
        val homeCardRecommended = Unit
    }

    /*
     * 显示与歌词外观
     *
     * 放封面、播放页文案、歌词显示和背景图这类纯显示偏好
     * 图片选择和 Slider 需要自定义 UI，但 key、默认值、备份和元数据仍在这里统一登记
     */
    @AutoSettingsSection(
        titleRes = "settings_display",
        descriptionRes = "settings_display_desc",
        order = 50
    )
    object display {
        @AutoSetting(
            key = "show_cover_source_badge",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_cover_source_badge",
            descriptionRes = "settings_cover_source_badge_desc",
            order = 10,
            ui = SettingUiType.Switch
        )
        val showCoverSourceBadge = autoSetting(icon = AutoSettingIcon.Info)

        @AutoSetting(
            key = "nowplaying_show_title",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_title",
            descriptionRes = "settings_nowplaying_title_desc",
            order = 20,
            ui = SettingUiType.Switch
        )
        val nowPlayingShowTitle = autoSetting(icon = AutoSettingIcon.LibraryMusic)

        @AutoSetting(
            key = "nowplaying_keep_screen_on",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_keep_screen_on",
            descriptionRes = "settings_nowplaying_keep_screen_on_desc",
            order = 30,
            ui = SettingUiType.Switch
        )
        val nowPlayingKeepScreenOn = autoSetting(icon = AutoSettingIcon.Brightness4)

        @AutoSetting(
            key = "nowplaying_toolbar_dock_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_toolbar_dock",
            descriptionRes = "settings_nowplaying_toolbar_dock_desc",
            order = 40,
            ui = SettingUiType.Switch
        )
        val nowPlayingToolbarDockEnabled = autoSetting(icon = AutoSettingIcon.Home)

        @AutoSetting(
            key = "nowplaying_progress_show_quality_switch",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_progress_quality_switch",
            descriptionRes = "settings_nowplaying_progress_quality_switch_desc",
            order = 50,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowQualitySwitch = autoSetting(icon = AutoSettingIcon.Tune)

        @AutoSetting(
            key = "nowplaying_progress_show_audio_codec",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_progress_audio_codec",
            descriptionRes = "settings_nowplaying_progress_audio_codec_desc",
            order = 60,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowAudioCodec = autoSetting(icon = AutoSettingIcon.Info)

        @AutoSetting(
            key = "nowplaying_progress_show_audio_spec",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_progress_audio_spec",
            descriptionRes = "settings_nowplaying_progress_audio_spec_desc",
            order = 70,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowAudioSpec = autoSetting(icon = AutoSettingIcon.LibraryMusic)

        @AutoSetting(
            key = "lyric_font_scale",
            type = SettingValueType.Float,
            defaultFloat = 1.0f,
            titleRes = "lyrics_font_size",
            order = 80,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val lyricFontScale = Unit

        @AutoSetting(
            key = "ui_density_scale",
            type = SettingValueType.Float,
            defaultFloat = 1.0f,
            titleRes = "settings_ui_scale_dpi",
            order = 90,
            ui = SettingUiType.Custom
        )
        val uiDensityScale = Unit

        @AutoSetting(
            key = "background_image_uri",
            type = SettingValueType.String,
            defaultString = "",
            titleRes = "background_custom",
            order = 100,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val backgroundImageUri = Unit

        @AutoSetting(
            key = "background_image_blur",
            type = SettingValueType.Float,
            defaultFloat = 0f,
            titleRes = "background_blur",
            order = 110,
            ui = SettingUiType.Custom
        )
        val backgroundImageBlur = Unit

        @AutoSetting(
            key = "background_image_alpha",
            type = SettingValueType.Float,
            defaultFloat = 0.3f,
            titleRes = "background_opacity",
            order = 120,
            ui = SettingUiType.Custom
        )
        val backgroundImageAlpha = Unit

        @AutoSetting(
            key = "show_lyric_translation",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_show_lyric_translation",
            descriptionRes = "settings_show_lyric_translation_desc",
            order = 130,
            ui = SettingUiType.Switch
        )
        val showLyricTranslation = autoSetting(icon = AutoSettingIcon.Subtitles)
    }

    /*
     * 动效与歌词运动
     *
     * 放播放页动效、歌词动效、模糊强度和歌词来源偏移
     * 很多开关受 Android 版本或互斥关系影响，所以复杂项只生成元数据，不走通用开关
     */
    @AutoSettingsSection(
        titleRes = "settings_motion",
        descriptionRes = "settings_motion_expand",
        order = 60
    )
    object motion {
        @AutoSetting(
            key = "lyricon_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_lyricon_enabled",
            descriptionRes = "settings_lyricon_enabled_desc",
            order = 5,
            ui = SettingUiType.Switch
        )
        val lyriconEnabled = autoSetting(icon = AutoSettingIcon.Subtitles)

        @AutoSetting(
            key = "advanced_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_advanced_lyrics",
            descriptionRes = "settings_advanced_lyrics_desc",
            order = 10,
            ui = SettingUiType.Switch
        )
        val advancedLyricsEnabled = autoSetting(iconRes = R.drawable.ic_lyrics)

        @AutoSetting(
            key = "advanced_blur_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_advanced_blur",
            descriptionRes = "settings_advanced_blur_desc",
            order = 20,
            ui = SettingUiType.Custom
        )
        val advancedBlurEnabled = autoSetting(icon = AutoSettingIcon.BlurOn)

        @AutoSetting(
            key = "nowplaying_audio_reactive_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_audio_reactive",
            descriptionRes = "settings_nowplaying_audio_reactive_desc",
            order = 30,
            ui = SettingUiType.Custom
        )
        val nowPlayingAudioReactiveEnabled = autoSetting(icon = AutoSettingIcon.Analytics)

        @AutoSetting(
            key = "nowplaying_dynamic_background_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_nowplaying_dynamic_background",
            descriptionRes = "settings_nowplaying_dynamic_background_desc",
            order = 40,
            ui = SettingUiType.Custom
        )
        val nowPlayingDynamicBackgroundEnabled = autoSetting(icon = AutoSettingIcon.AutoAwesome)

        @AutoSetting(
            key = "nowplaying_cover_blur_background_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_nowplaying_cover_blur_background",
            descriptionRes = "settings_nowplaying_cover_blur_background_desc",
            order = 50,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurBackgroundEnabled = autoSetting(icon = AutoSettingIcon.Wallpaper)

        @AutoSetting(
            key = "nowplaying_cover_blur_amount",
            type = SettingValueType.Float,
            defaultFloat = 1.5f,
            titleRes = "settings_nowplaying_cover_blur_amount",
            order = 60,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurAmount = Unit

        @AutoSetting(
            key = "nowplaying_cover_blur_darken",
            type = SettingValueType.Float,
            defaultFloat = 0.2f,
            titleRes = "settings_nowplaying_cover_blur_darken",
            order = 70,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurDarken = Unit

        @AutoSetting(
            key = "lyric_blur_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "lyrics_blur_effect",
            descriptionRes = "lyrics_blur_desc",
            order = 80,
            ui = SettingUiType.Custom
        )
        val lyricBlurEnabled = autoSetting(icon = AutoSettingIcon.Subtitles)

        @AutoSetting(
            key = "lyric_blur_amount",
            type = SettingValueType.Float,
            defaultFloat = 1.5f,
            titleRes = "lyrics_blur_amount",
            order = 90,
            ui = SettingUiType.Custom
        )
        val lyricBlurAmount = Unit

        @AutoSetting(
            key = "cloud_music_lyric_default_offset_ms",
            type = SettingValueType.Long,
            defaultLong = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
            titleRes = "settings_lyrics_offset_cloud_music",
            descriptionRes = "settings_lyrics_offset_cloud_music_desc",
            order = 100,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val cloudMusicLyricDefaultOffsetMs = Unit

        @AutoSetting(
            key = "qq_music_lyric_default_offset_ms",
            type = SettingValueType.Long,
            defaultLong = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
            titleRes = "settings_lyrics_offset_qq_music",
            descriptionRes = "settings_lyrics_offset_qq_music_desc",
            order = 110,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val qqMusicLyricDefaultOffsetMs = Unit
    }

    /*
     * 网络
     *
     * 放会影响网络栈启动快照的开关
     * 这些值可能在进程早期读取，写入时必须同步 bootstrap snapshot
     */
    @AutoSettingsSection(
        titleRes = "settings_network",
        descriptionRes = "settings_network_expand",
        order = 70
    )
    object network {
        @AutoSetting(
            key = "bypass_proxy",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_bypass_proxy",
            descriptionRes = "settings_bypass_proxy_desc",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val bypassProxy = Unit
    }

    /*
     * 下载路径与命名
     *
     * 放下载目录、目录展示名和文件名模板
     * 目录权限、迁移流程和快照同步都必须走手写业务入口
     */
    @AutoSettingsSection(
        titleRes = "settings_download_management",
        descriptionRes = "settings_download_expand",
        order = 80
    )
    object download {
        @AutoSetting(
            key = "download_directory_uri",
            type = SettingValueType.String,
            defaultString = "",
            titleRes = "settings_download_directory",
            descriptionRes = "settings_download_directory_desc",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val downloadDirectoryUri = autoSetting(icon = AutoSettingIcon.Download)

        @AutoSetting(
            key = "download_directory_label",
            type = SettingValueType.String,
            defaultString = "",
            titleRes = "settings_download_directory_current",
            order = 20,
            access = SettingAccessMode.KeyOnly
        )
        val downloadDirectoryLabel = Unit

        @AutoSetting(
            key = "download_file_name_template",
            type = SettingValueType.String,
            defaultString = "",
            titleRes = "settings_download_file_name_format",
            descriptionRes = "settings_download_file_name_format_desc",
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val downloadFileNameTemplate = autoSetting(icon = AutoSettingIcon.Download)
    }

    /*
     * 存储与缓存
     *
     * 放缓存容量、清理入口和本地存储展示相关的设置
     * 缓存容量会影响播放器启动快照，保留手写 setter
     */
    @AutoSettingsSection(
        titleRes = "settings_storage_cache",
        descriptionRes = "settings_storage_expand",
        order = 90
    )
    object storage {
        @AutoSetting(
            key = "max_cache_size_bytes",
            type = SettingValueType.Long,
            defaultLong = 1024L * 1024L * 1024L,
            titleRes = "settings_cache_limit",
            descriptionRes = "settings_cache_notice",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val maxCacheSizeBytes = Unit
    }

    /*
     * 备份与同步
     *
     * 放配置导入导出、GitHub/WebDAV 同步和备份提示偏好
     * token、远端配置和立即同步属于独立存储，不进入 DataStore schema
     */
    @AutoSettingsSection(
        titleRes = "settings_backup_restore",
        descriptionRes = "settings_backup_expand",
        order = 100
    )
    object backup {
        @AutoSetting(
            key = "silent_github_sync_failure",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "github_sync_silent_failure",
            descriptionRes = "github_sync_silent_failure_desc",
            order = 10,
            ui = SettingUiType.Switch
        )
        val silentGitHubSyncFailure = autoSetting(icon = AutoSettingIcon.Error)
    }

    /*
     * 播放行为
     *
     * 放播放器启动时就要知道的行为偏好，比如淡入淡出、状态恢复和音频焦点
     * 这些项会写 playback snapshot，不能让通用 setter 直接绕过
     */
    @AutoSettingsSection(
        titleRes = "settings_playback",
        descriptionRes = "settings_playback_expand",
        order = 110
    )
    object playback {
        @AutoSetting(
            key = "playback_fade_in",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_playback_fade_in",
            descriptionRes = "settings_playback_fade_in_desc",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackFadeIn = Unit

        @AutoSetting(
            key = "playback_crossfade_next",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_playback_crossfade_next",
            descriptionRes = "settings_playback_crossfade_next_desc",
            order = 20,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackCrossfadeNext = Unit

        @AutoSetting(
            key = "playback_fade_in_duration_ms",
            type = SettingValueType.Long,
            defaultLong = 500L,
            titleRes = "settings_playback_fade_in_duration",
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackFadeInDurationMs = Unit

        @AutoSetting(
            key = "playback_fade_out_duration_ms",
            type = SettingValueType.Long,
            defaultLong = 500L,
            titleRes = "settings_playback_fade_out_duration",
            order = 40,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackFadeOutDurationMs = Unit

        @AutoSetting(
            key = "playback_crossfade_in_duration_ms",
            type = SettingValueType.Long,
            defaultLong = 500L,
            titleRes = "settings_playback_crossfade_in_duration",
            order = 50,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackCrossfadeInDurationMs = Unit

        @AutoSetting(
            key = "playback_crossfade_out_duration_ms",
            type = SettingValueType.Long,
            defaultLong = 500L,
            titleRes = "settings_playback_crossfade_out_duration",
            order = 60,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackCrossfadeOutDurationMs = Unit

        @AutoSetting(
            key = "playback_speed",
            type = SettingValueType.Float,
            defaultFloat = DEFAULT_PLAYBACK_SPEED,
            titleRes = "player_play",
            order = 70,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackSpeed = Unit

        @AutoSetting(
            key = "playback_pitch",
            type = SettingValueType.Float,
            defaultFloat = DEFAULT_PLAYBACK_PITCH,
            titleRes = "settings_playback",
            order = 80,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackPitch = Unit

        @AutoSetting(
            key = "playback_equalizer_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_playback",
            order = 90,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackEqualizerEnabled = Unit

        @AutoSetting(
            key = "playback_equalizer_preset",
            type = SettingValueType.String,
            defaultString = PlaybackEqualizerPresetId.FLAT,
            titleRes = "settings_playback",
            order = 100,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackEqualizerPreset = Unit

        @AutoSetting(
            key = "playback_equalizer_custom_band_levels",
            type = SettingValueType.String,
            defaultString = "",
            titleRes = "settings_playback",
            order = 110,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackEqualizerCustomBandLevels = Unit

        @AutoSetting(
            key = "playback_loudness_gain_mb",
            type = SettingValueType.Int,
            defaultInt = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
            titleRes = "settings_playback",
            order = 120,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val playbackLoudnessGainMb = Unit

        @AutoSetting(
            key = "keep_last_playback_progress",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_keep_last_playback_progress",
            descriptionRes = "settings_keep_last_playback_progress_desc",
            order = 130,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val keepLastPlaybackProgress = Unit

        @AutoSetting(
            key = "keep_playback_mode_state",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_keep_playback_mode_state",
            descriptionRes = "settings_keep_playback_mode_state_desc",
            order = 140,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val keepPlaybackModeState = Unit

        @AutoSetting(
            key = "stop_on_bluetooth_disconnect",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            titleRes = "settings_stop_on_bluetooth_disconnect",
            descriptionRes = "settings_stop_on_bluetooth_disconnect_desc",
            order = 150,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val stopOnBluetoothDisconnect = Unit

        @AutoSetting(
            key = "allow_mixed_playback",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            titleRes = "settings_allow_mixed_playback",
            descriptionRes = "settings_allow_mixed_playback_desc",
            order = 160,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val allowMixedPlayback = Unit
    }
}
