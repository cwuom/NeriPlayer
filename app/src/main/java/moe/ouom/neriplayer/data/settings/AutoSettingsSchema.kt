package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.download.DEFAULT_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.ksp.annotations.AutoSetting
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsCatalog
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsSection
import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import moe.ouom.neriplayer.ksp.annotations.autoFloatSetting
import moe.ouom.neriplayer.ksp.annotations.autoIntSetting
import moe.ouom.neriplayer.ksp.annotations.autoSetting
import moe.ouom.neriplayer.ksp.annotations.autoSettingsSection
import moe.ouom.neriplayer.ksp.annotations.autoSwitchSetting

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
 * - Material 图标用 icon，已有 drawable 资源用 iconRes
 * - 简单开关可用 @AutoSetting(order = x) + autoSwitchSetting(...)，业务侧可直接
 *   用 SettingsRepository.settingFlow/setSetting 读取这个源代码对象
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
        order = 10
    )
    object general {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_general,
            descriptionRes = R.string.settings_general_desc,
            icon = AutoSettingIcon.Settings
        )

        @AutoSetting(
            key = "dynamic_color",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 10,
            ui = SettingUiType.Switch,
            access = SettingAccessMode.KeyOnly
        )
        val dynamicColor = autoSetting(
            titleRes = R.string.settings_dynamic_color,
            descriptionRes = R.string.settings_dynamic_color_desc,
            icon = AutoSettingIcon.Colorize
        )

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
            order = 40
        )
        val hapticFeedbackEnabled = autoSwitchSetting(
            key = "haptic_feedback_enabled",
            defaultValue = true,
            titleRes = R.string.settings_haptic,
            descriptionRes = R.string.settings_haptic_desc,
            icon = AutoSettingIcon.AdsClick
        )

        @AutoSetting(
            key = "playback_service_idle_shutdown_minutes",
            type = SettingValueType.Int,
            defaultInt = DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES,
            order = 45,
            ui = SettingUiType.Custom,
            normalizer = PlaybackServiceIdleShutdownPreference::class
        )
        val playbackServiceIdleShutdownMinutes = autoIntSetting(
            key = "playback_service_idle_shutdown_minutes",
            defaultValue = DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES,
            titleRes = R.string.settings_playback_idle_shutdown,
            descriptionRes = R.string.settings_playback_idle_shutdown_desc,
            icon = AutoSettingIcon.Bolt
        )

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
            order = 60
        )
        val alwaysRecordLogsEnabled = autoSwitchSetting(
            key = "always_record_logs_enabled",
            defaultValue = false,
            titleRes = R.string.settings_always_record_logs,
            descriptionRes = R.string.settings_always_record_logs_desc,
            icon = AutoSettingIcon.Info
        )

        @AutoSetting(
            key = "disclaimer_accepted_v2",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 70,
            access = SettingAccessMode.KeyOnly
        )
        val disclaimerAcceptedV2 = Unit

        @AutoSetting(
            key = "startup_onboarding_completed",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 80,
            access = SettingAccessMode.KeyOnly
        )
        val startupOnboardingCompleted = Unit

        @AutoSetting(
            key = "internationalization_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 90,
            access = SettingAccessMode.KeyOnly
        )
        val internationalizationEnabled = autoSetting(
            titleRes = R.string.settings_internationalization,
            descriptionRes = R.string.settings_internationalization_desc,
            iconRes = R.drawable.ic_i18n
        )
    }

    /*
     * 主题取色
     *
     * 只放主题色和调色盘这类纯视觉主题数据
     * 当前主题写入还会更新启动快照或触发页面动画，因此先保留 KeyOnly
     */
    @AutoSettingsSection(
        order = 20
    )
    object theme {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_theme,
            descriptionRes = R.string.settings_theme_desc,
            icon = AutoSettingIcon.Palette
        )

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

        @AutoSetting(
            key = "theme_palette_style",
            type = SettingValueType.String,
            defaultString = ThemeDefaults.DEFAULT_PALETTE_STYLE,
            constantName = "THEME_PALETTE_STYLE",
            order = 30,
            access = SettingAccessMode.KeyOnly
        )
        val themePaletteStyle = Unit

        @AutoSetting(
            key = "theme_color_spec",
            type = SettingValueType.String,
            defaultString = ThemeDefaults.DEFAULT_COLOR_SPEC,
            constantName = "THEME_COLOR_SPEC",
            order = 40,
            access = SettingAccessMode.KeyOnly
        )
        val themeColorSpec = Unit
    }

    /*
     * 音质偏好
     *
     * 放各平台默认音质选择，UI 通常是选项弹窗，不是简单开关
     * 写入后还要同步播放启动快照，所以这里统一标记为 Custom + KeyOnly
     */
    @AutoSettingsSection(
        order = 30
    )
    object audioQuality {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_audio_quality,
            descriptionRes = R.string.settings_audio_quality_expand,
            icon = AutoSettingIcon.Audiotrack
        )

        @AutoSetting(
            key = "audio_quality",
            type = SettingValueType.String,
            defaultString = "exhigh",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val audioQuality = autoSetting(
            titleRes = R.string.quality_netease_default,
            iconRes = R.drawable.ic_netease_cloud_music
        )

        @AutoSetting(
            key = "youtube_audio_quality",
            type = SettingValueType.String,
            defaultString = "high",
            order = 20,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val youtubeAudioQuality = autoSetting(
            titleRes = R.string.quality_youtube_default,
            iconRes = R.drawable.ic_youtube
        )

        @AutoSetting(
            key = "bili_audio_quality",
            type = SettingValueType.String,
            defaultString = "high",
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val biliAudioQuality = autoSetting(
            titleRes = R.string.quality_bili_default,
            iconRes = R.drawable.ic_bilibili
        )

        @AutoSetting(
            key = "mobile_data_follow_default_audio_quality",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 40,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val mobileDataFollowDefaultAudioQuality = autoSetting(
            titleRes = R.string.settings_mobile_data_follow_default_audio_quality,
            descriptionRes = R.string.settings_mobile_data_follow_default_audio_quality_desc,
            icon = AutoSettingIcon.Analytics
        )

        @AutoSetting(
            key = "mobile_data_netease_audio_quality",
            type = SettingValueType.String,
            defaultString = DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY,
            order = 50,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val mobileDataNeteaseAudioQuality = autoSetting(
            titleRes = R.string.settings_mobile_data_netease_audio_quality,
            iconRes = R.drawable.ic_netease_cloud_music
        )

        @AutoSetting(
            key = "mobile_data_youtube_audio_quality",
            type = SettingValueType.String,
            defaultString = DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY,
            order = 60,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val mobileDataYouTubeAudioQuality = autoSetting(
            titleRes = R.string.settings_mobile_data_youtube_audio_quality,
            iconRes = R.drawable.ic_youtube
        )

        @AutoSetting(
            key = "mobile_data_bili_audio_quality",
            type = SettingValueType.String,
            defaultString = DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY,
            order = 70,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val mobileDataBiliAudioQuality = autoSetting(
            titleRes = R.string.settings_mobile_data_bili_audio_quality,
            iconRes = R.drawable.ic_bilibili
        )

        @AutoSetting(
            key = "mobile_data_downgrade_quality",
            type = SettingValueType.String,
            defaultString = "low",
            order = 80,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val mobileDataDowngradeQuality = autoSetting(
            titleRes = R.string.settings_mobile_data_downgrade_quality,
            descriptionRes = R.string.settings_mobile_data_downgrade_quality_desc,
            icon = AutoSettingIcon.Analytics
        )
    }

    /*
     * 个性化入口
     *
     * 放首页入口、首页卡片、输入体验这类不直接影响播放器内核的偏好
     * 复杂首页卡片会根据国际化状态换文案，保留手写 UI 但元数据仍由这里生成
     */
    @AutoSettingsSection(
        order = 40
    )
    object personalization {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_personalization,
            descriptionRes = R.string.settings_personalization_expand,
            icon = AutoSettingIcon.Tune
        )

        @AutoSetting(
            key = "default_start_destination",
            type = SettingValueType.String,
            defaultString = "home",
            order = 10,
            ui = SettingUiType.Custom
        )
        val defaultStartDestination = autoSetting(
            titleRes = R.string.settings_default_start_screen,
            descriptionRes = R.string.settings_default_start_screen_desc
        )

        @AutoSetting(
            key = "auto_show_keyboard",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 20,
            ui = SettingUiType.Switch
        )
        val autoShowKeyboard = autoSetting(
            titleRes = R.string.settings_auto_show_keyboard,
            descriptionRes = R.string.settings_auto_show_keyboard_desc,
            icon = AutoSettingIcon.Keyboard
        )

        @AutoSetting(
            key = "home_card_continue",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 30,
            ui = SettingUiType.Custom
        )
        val homeCardContinue = autoSetting(
            titleRes = R.string.player_continue
        )

        @AutoSetting(
            key = "home_card_trending",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 40,
            ui = SettingUiType.Custom
        )
        val homeCardTrending = autoSetting(
            titleRes = R.string.settings_home_cards
        )

        @AutoSetting(
            key = "home_card_radar",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 50,
            ui = SettingUiType.Custom
        )
        val homeCardRadar = autoSetting(
            titleRes = R.string.settings_home_cards
        )

        @AutoSetting(
            key = "home_card_recommended",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 60,
            ui = SettingUiType.Custom
        )
        val homeCardRecommended = autoSetting(
            titleRes = R.string.settings_home_cards
        )
    }

    /*
     * 显示与歌词外观
     *
     * 放封面、播放页文案、歌词显示和背景图这类纯显示偏好
     * 图片选择和 Slider 需要自定义 UI，但 key、默认值、备份和元数据仍在这里统一登记
     */
    @AutoSettingsSection(
        order = 50
    )
    object display {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_display,
            descriptionRes = R.string.settings_display_desc,
            icon = AutoSettingIcon.Info
        )

        @AutoSetting(
            key = "show_cover_source_badge",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 10,
            ui = SettingUiType.Switch
        )
        val showCoverSourceBadge = autoSetting(
            titleRes = R.string.settings_cover_source_badge,
            descriptionRes = R.string.settings_cover_source_badge_desc,
            icon = AutoSettingIcon.Info
        )

        @AutoSetting(
            key = "nowplaying_show_title",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 20,
            ui = SettingUiType.Switch
        )
        val nowPlayingShowTitle = autoSetting(
            titleRes = R.string.settings_nowplaying_title,
            descriptionRes = R.string.settings_nowplaying_title_desc,
            icon = AutoSettingIcon.LibraryMusic
        )

        @AutoSetting(
            key = "nowplaying_keep_screen_on",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 30,
            ui = SettingUiType.Switch
        )
        val nowPlayingKeepScreenOn = autoSetting(
            titleRes = R.string.settings_nowplaying_keep_screen_on,
            descriptionRes = R.string.settings_nowplaying_keep_screen_on_desc,
            icon = AutoSettingIcon.Brightness4
        )

        @AutoSetting(
            key = "nowplaying_toolbar_dock_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 40,
            ui = SettingUiType.Switch
        )
        val nowPlayingToolbarDockEnabled = autoSetting(
            titleRes = R.string.settings_nowplaying_toolbar_dock,
            descriptionRes = R.string.settings_nowplaying_toolbar_dock_desc,
            icon = AutoSettingIcon.Home
        )

        @AutoSetting(
            key = "nowplaying_progress_show_quality_switch",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 50,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowQualitySwitch = autoSetting(
            titleRes = R.string.settings_nowplaying_progress_quality_switch,
            descriptionRes = R.string.settings_nowplaying_progress_quality_switch_desc,
            icon = AutoSettingIcon.Tune
        )

        @AutoSetting(
            key = "nowplaying_progress_show_audio_codec",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 60,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowAudioCodec = autoSetting(
            titleRes = R.string.settings_nowplaying_progress_audio_codec,
            descriptionRes = R.string.settings_nowplaying_progress_audio_codec_desc,
            icon = AutoSettingIcon.Info
        )

        @AutoSetting(
            key = "nowplaying_progress_show_audio_spec",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 70,
            ui = SettingUiType.Switch
        )
        val nowPlayingProgressShowAudioSpec = autoSetting(
            titleRes = R.string.settings_nowplaying_progress_audio_spec,
            descriptionRes = R.string.settings_nowplaying_progress_audio_spec_desc,
            icon = AutoSettingIcon.LibraryMusic
        )

        @AutoSetting(
            key = "lyric_font_scale",
            type = SettingValueType.Float,
            defaultFloat = 1.0f,
            order = 80,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val lyricFontScale = autoSetting(
            titleRes = R.string.lyrics_font_size
        )

        @AutoSetting(
            key = "ui_density_scale",
            type = SettingValueType.Float,
            defaultFloat = 1.0f,
            order = 90,
            ui = SettingUiType.Custom
        )
        val uiDensityScale = autoSetting(
            titleRes = R.string.settings_ui_scale_dpi
        )

        @AutoSetting(
            key = "background_image_uri",
            type = SettingValueType.String,
            defaultString = "",
            order = 100,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val backgroundImageUri = autoSetting(
            titleRes = R.string.background_custom
        )

        @AutoSetting(
            key = "background_image_blur",
            type = SettingValueType.Float,
            defaultFloat = 0f,
            order = 110,
            ui = SettingUiType.Custom
        )
        val backgroundImageBlur = autoSetting(
            titleRes = R.string.background_blur
        )

        @AutoSetting(
            key = "background_image_alpha",
            type = SettingValueType.Float,
            defaultFloat = 0.3f,
            order = 120,
            ui = SettingUiType.Custom
        )
        val backgroundImageAlpha = autoSetting(
            titleRes = R.string.background_opacity
        )

        @AutoSetting(
            key = "show_lyric_translation",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 130,
            ui = SettingUiType.Switch
        )
        val showLyricTranslation = autoSetting(
            titleRes = R.string.settings_show_lyric_translation,
            descriptionRes = R.string.settings_show_lyric_translation_desc,
            icon = AutoSettingIcon.Subtitles
        )

        @AutoSetting(
            key = "lyric_translation_use_phonetic",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 140,
            ui = SettingUiType.Switch
        )
        val lyricTranslationUsePhonetic = autoSetting(
            titleRes = R.string.lyrics_translation_use_phonetic,
            descriptionRes = R.string.lyrics_translation_use_phonetic_desc,
            icon = AutoSettingIcon.Keyboard
        )
    }

    /*
     * 动效与歌词运动
     *
     * 放播放页动效、歌词动效和模糊强度
     * 很多开关受 Android 版本或互斥关系影响，所以复杂项只生成元数据，不走通用开关
     */
    @AutoSettingsSection(
        order = 60
    )
    object motion {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_motion,
            descriptionRes = R.string.settings_motion_expand,
            icon = AutoSettingIcon.Bolt
        )

        @AutoSetting(
            key = "advanced_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 10,
            ui = SettingUiType.Switch
        )
        val advancedLyricsEnabled = autoSetting(
            titleRes = R.string.settings_advanced_lyrics,
            descriptionRes = R.string.settings_advanced_lyrics_desc,
            iconRes = R.drawable.ic_lyrics
        )

        @AutoSetting(
            key = "advanced_blur_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 20,
            ui = SettingUiType.Custom
        )
        val advancedBlurEnabled = autoSetting(
            titleRes = R.string.settings_advanced_blur,
            descriptionRes = R.string.settings_advanced_blur_desc,
            icon = AutoSettingIcon.BlurOn
        )

        @AutoSetting(
            key = "enhanced_advanced_blur_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 25,
            ui = SettingUiType.Custom
        )
        val enhancedAdvancedBlurEnabled = autoSwitchSetting(
            key = "enhanced_advanced_blur_enabled",
            defaultValue = false,
            titleRes = R.string.settings_enhanced_advanced_blur,
            descriptionRes = R.string.settings_enhanced_advanced_blur_desc,
            icon = AutoSettingIcon.Layers
        )

        @AutoSetting(
            key = "enhanced_advanced_blur_radius_dp",
            type = SettingValueType.Float,
            defaultFloat = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            order = 27,
            ui = SettingUiType.Custom,
            normalizer = EnhancedAdvancedBlurPreference::class
        )
        val enhancedAdvancedBlurRadiusDp = autoFloatSetting(
            key = "enhanced_advanced_blur_radius_dp",
            defaultValue = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            titleRes = R.string.settings_enhanced_advanced_blur_radius
        )

        @AutoSetting(
            key = "nowplaying_audio_reactive_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 30,
            ui = SettingUiType.Custom
        )
        val nowPlayingAudioReactiveEnabled = autoSetting(
            titleRes = R.string.settings_nowplaying_audio_reactive,
            descriptionRes = R.string.settings_nowplaying_audio_reactive_desc,
            icon = AutoSettingIcon.Analytics
        )

        @AutoSetting(
            key = "nowplaying_dynamic_background_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 40,
            ui = SettingUiType.Custom
        )
        val nowPlayingDynamicBackgroundEnabled = autoSetting(
            titleRes = R.string.settings_nowplaying_dynamic_background,
            descriptionRes = R.string.settings_nowplaying_dynamic_background_desc,
            icon = AutoSettingIcon.AutoAwesome
        )

        @AutoSetting(
            key = "nowplaying_cover_blur_background_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 50,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurBackgroundEnabled = autoSetting(
            titleRes = R.string.settings_nowplaying_cover_blur_background,
            descriptionRes = R.string.settings_nowplaying_cover_blur_background_desc,
            icon = AutoSettingIcon.Wallpaper
        )

        @AutoSetting(
            key = "nowplaying_cover_blur_amount",
            type = SettingValueType.Float,
            defaultFloat = 1.5f,
            order = 60,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurAmount = autoSetting(
            titleRes = R.string.settings_nowplaying_cover_blur_amount
        )

        @AutoSetting(
            key = "nowplaying_cover_blur_darken",
            type = SettingValueType.Float,
            defaultFloat = 0.2f,
            order = 70,
            ui = SettingUiType.Custom
        )
        val nowPlayingCoverBlurDarken = autoSetting(
            titleRes = R.string.settings_nowplaying_cover_blur_darken
        )

        @AutoSetting(
            key = "lyric_blur_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 80,
            ui = SettingUiType.Custom
        )
        val lyricBlurEnabled = autoSetting(
            titleRes = R.string.lyrics_blur_effect,
            descriptionRes = R.string.lyrics_blur_desc,
            icon = AutoSettingIcon.Subtitles
        )

        @AutoSetting(
            key = "lyric_blur_amount",
            type = SettingValueType.Float,
            defaultFloat = 1.5f,
            order = 90,
            ui = SettingUiType.Custom
        )
        val lyricBlurAmount = autoSetting(
            titleRes = R.string.lyrics_blur_amount
        )
    }

    /*
     * 歌词设置
     *
     * 放外部词幕适配和各来源默认歌词偏移
     * 偏移会影响已有歌曲的用户偏移重算，所以保留手写入口
     */
    @AutoSettingsSection(
        order = 65
    )
    object lyrics {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_lyrics_offset,
            descriptionRes = R.string.settings_lyrics_offset_expand,
            icon = AutoSettingIcon.Subtitles
        )

        @AutoSetting(
            key = "lyricon_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 10,
            ui = SettingUiType.Switch
        )
        val lyriconEnabled = autoSetting(
            titleRes = R.string.settings_lyric_api_enabled,
            descriptionRes = R.string.settings_lyric_api_enabled_desc,
            iconRes = R.drawable.ic_lyricon
        )

        @AutoSetting(
            key = "amll_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 11,
            ui = SettingUiType.Switch
        )
        val amllLyricsEnabled = autoSetting(
            titleRes = R.string.settings_amll_lyrics_enabled,
            descriptionRes = R.string.settings_amll_lyrics_enabled_desc,
            icon = AutoSettingIcon.Subtitles
        )

        @AutoSetting(
            key = "status_bar_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 13,
            ui = SettingUiType.Switch
        )
        val statusBarLyrics = autoSetting(
            titleRes = R.string.settings_status_bar_lyrics_title,
            descriptionRes = R.string.settings_status_bar_lyrics_summary,
            iconRes = R.drawable.ic_statusbar
        )

        @AutoSetting(
            key = "floating_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 14,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsEnabled = autoSetting(
            titleRes = R.string.settings_floating_lyrics_title,
            descriptionRes = R.string.settings_floating_lyrics_desc,
            icon = AutoSettingIcon.Subtitles
        )

        @AutoSetting(
            key = "floating_lyrics_hide_in_app",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 15,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsHideInApp = Unit

        @AutoSetting(
            key = "floating_lyrics_text_color",
            type = SettingValueType.String,
            defaultString = "FFFFFF",
            order = 16,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsTextColor = Unit

        @AutoSetting(
            key = "floating_lyrics_outline_color",
            type = SettingValueType.String,
            defaultString = "121212",
            order = 17,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsOutlineColor = Unit

        @AutoSetting(
            key = "floating_lyrics_font_size_sp",
            type = SettingValueType.Float,
            defaultFloat = 22f,
            order = 18,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsFontSizeSp = Unit

        @AutoSetting(
            key = "floating_lyrics_outline_width_dp",
            type = SettingValueType.Float,
            defaultFloat = 1.6f,
            order = 19,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsOutlineWidthDp = Unit

        @AutoSetting(
            key = "floating_lyrics_lyric_alpha",
            type = SettingValueType.Float,
            defaultFloat = 1f,
            order = 19,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsLyricAlpha = Unit

        @AutoSetting(
            key = "floating_lyrics_translation_outline_width_dp",
            type = SettingValueType.Float,
            defaultFloat = 1.152f,
            order = 19,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsTranslationOutlineWidthDp = Unit

        @AutoSetting(
            key = "floating_lyrics_translation_alpha",
            type = SettingValueType.Float,
            defaultFloat = 0.72f,
            order = 19,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsTranslationAlpha = Unit

        @AutoSetting(
            key = "floating_lyrics_max_width_dp",
            type = SettingValueType.Float,
            defaultFloat = 280f,
            order = 20,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsMaxWidthDp = Unit

        @AutoSetting(
            key = "floating_lyrics_position_x",
            type = SettingValueType.Float,
            defaultFloat = 0.1f,
            order = 21,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsPositionX = Unit

        @AutoSetting(
            key = "floating_lyrics_position_y",
            type = SettingValueType.Float,
            defaultFloat = 0.7f,
            order = 22,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsPositionY = Unit

        @AutoSetting(
            key = "floating_lyrics_alignment",
            type = SettingValueType.String,
            defaultString = FLOATING_LYRICS_ALIGNMENT_CENTER,
            order = 23,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsAlignment = Unit

        @AutoSetting(
            key = "floating_lyrics_show_translation",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 24,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsShowTranslation = Unit

        @AutoSetting(
            key = "floating_lyrics_reveal_animation_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 25,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val floatingLyricsRevealAnimationEnabled = Unit

        @AutoSetting(
            key = "external_bluetooth_lyrics_enabled",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 26,
            ui = SettingUiType.Switch
        )
        val externalBluetoothLyricsEnabled = autoSetting(
            titleRes = R.string.settings_external_bluetooth_lyrics_enabled,
            descriptionRes = R.string.settings_external_bluetooth_lyrics_enabled_desc,
            icon = AutoSettingIcon.BluetoothAudio
        )

        @AutoSetting(
            key = "cloud_music_lyric_default_offset_ms",
            type = SettingValueType.Long,
            defaultLong = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
            order = 20,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val cloudMusicLyricDefaultOffsetMs = autoSetting(
            titleRes = R.string.settings_lyrics_offset_cloud_music,
            descriptionRes = R.string.settings_lyrics_offset_cloud_music_desc
        )

        @AutoSetting(
            key = "qq_music_lyric_default_offset_ms",
            type = SettingValueType.Long,
            defaultLong = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val qqMusicLyricDefaultOffsetMs = autoSetting(
            titleRes = R.string.settings_lyrics_offset_qq_music,
            descriptionRes = R.string.settings_lyrics_offset_qq_music_desc
        )
    }

    /*
     * 网络
     *
     * 放会影响网络栈启动快照的开关
     * 这些值可能在进程早期读取，写入时必须同步 bootstrap snapshot
     */
    @AutoSettingsSection(
        order = 70
    )
    object network {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_network,
            descriptionRes = R.string.settings_network_expand,
            icon = AutoSettingIcon.Router
        )

        @AutoSetting(
            key = "bypass_proxy",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val bypassProxy = autoSetting(
            titleRes = R.string.settings_bypass_proxy,
            descriptionRes = R.string.settings_bypass_proxy_desc
        )
    }

    /*
     * 下载路径与命名
     *
     * 放下载目录、目录展示名和文件名模板
     * 目录权限、迁移流程和快照同步都必须走手写业务入口
     */
    @AutoSettingsSection(
        order = 80
    )
    object download {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_download_management,
            descriptionRes = R.string.settings_download_expand,
            icon = AutoSettingIcon.Download
        )

        @AutoSetting(
            key = "download_directory_uri",
            type = SettingValueType.String,
            defaultString = "",
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val downloadDirectoryUri = autoSetting(
            titleRes = R.string.settings_download_directory,
            descriptionRes = R.string.settings_download_directory_desc,
            icon = AutoSettingIcon.Download
        )

        @AutoSetting(
            key = "download_directory_label",
            type = SettingValueType.String,
            defaultString = "",
            order = 20,
            access = SettingAccessMode.KeyOnly
        )
        val downloadDirectoryLabel = autoSetting(
            titleRes = R.string.settings_download_directory_current
        )

        @AutoSetting(
            key = "download_file_name_template",
            type = SettingValueType.String,
            defaultString = "",
            order = 30,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val downloadFileNameTemplate = autoSetting(
            titleRes = R.string.settings_download_file_name_format,
            descriptionRes = R.string.settings_download_file_name_format_desc,
            icon = AutoSettingIcon.Download
        )

        @AutoSetting(order = 40)
        val downloadMetadataPostProcessingEnabled = autoSwitchSetting(
            key = "download_metadata_post_processing_enabled",
            defaultValue = true,
            titleRes = R.string.settings_download_metadata_post_processing,
            descriptionRes = R.string.settings_download_metadata_post_processing_desc,
            icon = AutoSettingIcon.AutoAwesome
        )

        @AutoSetting(order = 50)
        val standardizedLyricEmbeddingEnabled = autoSwitchSetting(
            key = "standardized_lyric_embedding_enabled",
            defaultValue = true,
            titleRes = R.string.settings_standardized_lyric_embedding,
            descriptionRes = R.string.settings_standardized_lyric_embedding_desc,
            icon = AutoSettingIcon.LibraryMusic
        )

        @AutoSetting(
            order = 60,
            ui = SettingUiType.Custom
        )
        val downloadParallelism = autoIntSetting(
            key = "download_parallelism",
            defaultValue = DEFAULT_DOWNLOAD_PARALLELISM,
            titleRes = R.string.settings_download_parallelism,
            descriptionRes = R.string.settings_download_parallelism_desc,
            icon = AutoSettingIcon.Tune
        )
    }

    /*
     * 流量管理
     *
     * 放流量统计展示和移动/漫游网络下的风险操作提示
     * 统计数据不进 DataStore，只有用户偏好开关在这里登记
     */
    @AutoSettingsSection(
        order = 85
    )
    object trafficManagement {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_traffic_management,
            descriptionRes = R.string.settings_traffic_management_desc,
            icon = AutoSettingIcon.Analytics
        )

        @AutoSetting(order = 10)
        val mobileDataHighRiskPromptEnabled = autoSwitchSetting(
            key = "mobile_data_high_risk_prompt_enabled",
            defaultValue = true,
            titleRes = R.string.settings_mobile_data_high_risk_prompt,
            descriptionRes = R.string.settings_mobile_data_high_risk_prompt_desc,
            icon = AutoSettingIcon.Error
        )
    }

    /*
     * 存储与缓存
     *
     * 放缓存容量、清理入口和本地存储展示相关的设置
     * 缓存容量会影响播放器启动快照，保留手写 setter
     */
    @AutoSettingsSection(
        order = 90
    )
    object storage {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_storage_cache,
            descriptionRes = R.string.settings_storage_expand,
            icon = AutoSettingIcon.Storage
        )

        @AutoSetting(
            key = "max_cache_size_bytes",
            type = SettingValueType.Long,
            defaultLong = 1024L * 1024L * 1024L,
            order = 10,
            ui = SettingUiType.Custom,
            access = SettingAccessMode.KeyOnly
        )
        val maxCacheSizeBytes = autoSetting(
            titleRes = R.string.settings_cache_limit,
            descriptionRes = R.string.settings_cache_notice
        )
    }

    /*
     * 备份与同步
     *
     * 放配置导入导出、GitHub/WebDAV 同步和备份提示偏好
     * token、远端配置和立即同步属于独立存储，不进入 DataStore schema
     */
    @AutoSettingsSection(
        order = 100
    )
    object backup {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_backup_restore,
            descriptionRes = R.string.settings_backup_expand,
            icon = AutoSettingIcon.Sync
        )

        @AutoSetting(
            key = "silent_github_sync_failure",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
            order = 10,
            ui = SettingUiType.Switch
        )
        val silentGitHubSyncFailure = autoSetting(
            titleRes = R.string.github_sync_silent_failure,
            descriptionRes = R.string.github_sync_silent_failure_desc,
            icon = AutoSettingIcon.Error
        )
    }

    /*
     * 播放行为
     *
     * 放播放器启动时就要知道的行为偏好，比如淡入淡出、状态恢复和音频焦点
     * 这些项会写 playback snapshot，不能让通用 setter 直接绕过
     */
    @AutoSettingsSection(
        order = 110
    )
    object playback {
        val metadata = autoSettingsSection(
            titleRes = R.string.settings_playback,
            descriptionRes = R.string.settings_playback_expand,
            icon = AutoSettingIcon.PlaylistPlay
        )

        @AutoSetting(
            key = "playback_fade_in",
            type = SettingValueType.Boolean,
            defaultBoolean = false,
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
            defaultBoolean = false,
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
            key = "netease_auto_source_switch",
            type = SettingValueType.Boolean,
            defaultBoolean = true,
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
}
