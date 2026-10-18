package moe.ouom.neriplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Deblur
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFormat
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GTranslate
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VpnKeyOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape

/** Per-setting symbol on its own hue; makes each row recognizable at a glance in clean style. */
internal data class SettingGlyph(val icon: ImageVector, val tint: Color)

private val Indigo = Color(0xFF5E5CE6)
private val Graphite = Color(0xFF8E8E93)
private val Blue = Color(0xFF0A84FF)
private val Red = Color(0xFFFF453A)
private val Orange = Color(0xFFFF9F0A)
private val Pink = Color(0xFFFF375F)
private val Teal = Color(0xFF30B0C7)
private val Green = Color(0xFF30D158)
private val Purple = Color(0xFFBF5AF2)
private val Cyan = Color(0xFF40C8E0)
private val Violet = Color(0xFF7D7AFF)
private val Mint = Color(0xFF00C7BE)
private val Slate = Color(0xFF636366)

private fun g(icon: ImageVector, tint: Color) = SettingGlyph(icon, tint)

/**
 * Glyph for a setting key (schema key or search id). Rows on one page never share a symbol,
 * and platform-specific rows use the platform's own mark so lists of near-identical titles
 * (per-platform quality, per-source offsets) can be told apart without reading.
 */
internal fun settingGlyphFor(key: String): SettingGlyph? = when (key) {
    // general & interface
    "haptic_feedback_enabled" -> g(Icons.Rounded.Vibration, Indigo)
    "explore_search_history_enabled" -> g(Icons.Rounded.History, Blue)
    "usb_device_attach_handling_enabled" -> g(Icons.Rounded.Cable, Green)
    "ui_density_scale" -> g(Icons.Rounded.AspectRatio, Blue)
    "playback_service_idle_shutdown_minutes" -> g(Icons.Rounded.PowerSettingsNew, Red)
    "prefer_high_refresh_rate" -> g(Icons.Rounded.Speed, Orange)
    "always_record_logs_enabled" -> g(Icons.Rounded.BugReport, Graphite)
    "internationalization_enabled", "internationalization" -> g(Icons.Rounded.Language, Teal)
    "language" -> g(Icons.Rounded.Translate, Blue)
    "youtube_enabled" -> g(Icons.Rounded.SmartDisplay, Red)
    "bili_skip_segment_prompt_enabled" -> g(Icons.Rounded.FastForward, Pink)
    // theme
    "ui_style" -> g(Icons.Rounded.Style, Indigo)
    "dynamic_color" -> g(Icons.Rounded.ColorLens, Purple)
    "theme_mode" -> g(Icons.Rounded.DarkMode, Indigo)
    "theme_palette_style" -> g(Icons.Rounded.Palette, Purple)
    "theme_color_spec" -> g(Icons.Rounded.Contrast, Graphite)
    "theme_seed_color" -> g(Icons.Rounded.Colorize, Pink)
    // quality & traffic
    "audio_quality", "mobile_data_netease_audio_quality", "download_netease_audio_quality" ->
        g(Icons.Rounded.MusicNote, Red)
    "youtube_audio_quality", "mobile_data_youtube_audio_quality", "download_youtube_audio_quality" ->
        g(Icons.Rounded.SmartDisplay, Red)
    "bili_audio_quality", "mobile_data_bili_audio_quality", "download_bili_audio_quality" ->
        g(Icons.Rounded.LiveTv, Pink)
    "mobile_data_follow_default_audio_quality" -> g(Icons.Rounded.SignalCellularAlt, Green)
    "mobile_data_downgrade_quality" -> g(Icons.AutoMirrored.Rounded.TrendingDown, Orange)
    "mobile_data_high_risk_prompt_enabled" -> g(Icons.Rounded.Warning, Orange)
    // home & startup
    "default_start_destination" -> g(Icons.Rounded.RocketLaunch, Blue)
    "auto_show_keyboard" -> g(Icons.Rounded.Keyboard, Graphite)
    "home_card_continue" -> g(Icons.Rounded.PlayCircle, Red)
    "home_card_trending" -> g(Icons.Rounded.Whatshot, Orange)
    "home_card_radar" -> g(Icons.Rounded.Radar, Teal)
    "home_card_recommended" -> g(Icons.Rounded.Recommend, Pink)
    "always_use_new_tab_style" -> g(Icons.Rounded.Tab, Indigo)
    // now playing
    "show_cover_source_badge" -> g(Icons.Rounded.Sell, Teal)
    "nowplaying_song_title_marquee_enabled" -> g(Icons.Rounded.TextFields, Blue)
    "nowplaying_keep_screen_on" -> g(Icons.Rounded.LightMode, Orange)
    "nowplaying_toolbar_dock_enabled" -> g(Icons.Rounded.CallToAction, Slate)
    "nowplaying_immersive_mode" -> g(Icons.Rounded.Fullscreen, Indigo)
    "nowplaying_cover_lyrics_enabled" -> g(Icons.Rounded.Lyrics, Violet)
    "nowplaying_progress_show_quality_switch" -> g(Icons.Rounded.HighQuality, Orange)
    "nowplaying_progress_show_audio_codec" -> g(Icons.Rounded.AudioFile, Teal)
    "nowplaying_progress_show_audio_spec" -> g(Icons.Rounded.Equalizer, Pink)
    "nowplaying_control_placement" -> g(Icons.Rounded.SpaceDashboard, Blue)
    "nowplaying_control_size" -> g(Icons.Rounded.ZoomOutMap, Blue)
    // lyrics
    "lyric_font_scale", "lyrics_control_size" -> g(Icons.Rounded.FormatSize, Blue)
    "nowplaying_cover_lyric_font_scale" -> g(Icons.Rounded.TextIncrease, Violet)
    "nowplaying_cover_translation_font_scale" -> g(Icons.Rounded.Subtitles, Blue)
    "lyrics_page_lyric_font_scale" -> g(Icons.Rounded.TextFormat, Violet)
    "lyrics_page_translation_font_scale" -> g(Icons.Rounded.GTranslate, Blue)
    "show_lyric_translation" -> g(Icons.Rounded.Translate, Teal)
    "lyric_translation_use_phonetic" -> g(Icons.Rounded.RecordVoiceOver, Teal)
    "advanced_lyrics_enabled" -> g(Icons.Rounded.AutoAwesome, Violet)
    "lyric_blur_enabled" -> g(Icons.Rounded.BlurOn, Violet)
    "lyric_blur_amount" -> g(Icons.Rounded.BlurLinear, Violet)
    "prefer_word_timed_lyrics" -> g(Icons.Rounded.Timer, Orange)
    "default_lyric_source" -> g(Icons.Rounded.Source, Teal)
    "cloud_music_lyric_default_offset_ms" -> g(Icons.Rounded.Cloud, Red)
    "qq_music_lyric_default_offset_ms" -> g(Icons.Rounded.MusicNote, Green)
    "kugou_lyric_default_offset_ms" -> g(Icons.Rounded.GraphicEq, Blue)
    "lrclib_lyric_default_offset_ms" -> g(Icons.Rounded.Lyrics, Teal)
    "amll_ttml_lyric_default_offset_ms" -> g(Icons.Rounded.DataObject, Pink)
    // floating & external lyrics
    "floating_lyrics_enabled" -> g(SettingSymbols.FloatingLyrics, Mint)
    "status_bar_lyrics_enabled" -> g(SettingSymbols.StatusBarLyrics, Blue)
    "live_update_lyric_enabled" -> g(Icons.Rounded.NotificationsActive, Orange)
    "dynamic_island_lyrics_enabled" -> g(SettingSymbols.DynamicIsland, Slate)
    "xiaomi_super_island_lyric_enabled" -> g(SettingSymbols.SuperIsland, Orange)
    "lyricon_enabled" -> g(Icons.Rounded.Extension, Indigo)
    "amll_lyrics_enabled" -> g(Icons.Rounded.Animation, Pink)
    "external_bluetooth_lyrics_enabled" -> g(Icons.Rounded.Bluetooth, Blue)
    "external_bluetooth_translation_enabled" -> g(SettingSymbols.BluetoothTranslation, Teal)
    "bluetooth_metadata_mode" -> g(Icons.Rounded.DirectionsCar, Indigo)
    // motion & glass
    "coherent_feedback_enabled" -> g(Icons.Rounded.TouchApp, Orange)
    "predictive_back_preview_enabled" -> g(Icons.Rounded.SwipeLeft, Blue)
    "liquid_glass_enabled" -> g(Icons.Rounded.WaterDrop, Cyan)
    "advanced_blur_enabled" -> g(Icons.Rounded.BlurOn, Indigo)
    "enhanced_advanced_blur_enabled" -> g(Icons.Rounded.BlurCircular, Purple)
    "enhanced_advanced_blur_radius_dp" -> g(Icons.Rounded.BlurLinear, Purple)
    "advanced_blur_quality" -> g(Icons.Rounded.HighQuality, Graphite)
    // backgrounds
    "background_image_uri" -> g(Icons.Rounded.Image, Cyan)
    "background_image_blur" -> g(Icons.Rounded.BlurOn, Cyan)
    "background_image_alpha" -> g(Icons.Rounded.Opacity, Cyan)
    "nowplaying_audio_reactive_enabled" -> g(Icons.Rounded.GraphicEq, Pink)
    "nowplaying_dynamic_background_enabled" -> g(Icons.Rounded.Gradient, Purple)
    "nowplaying_cover_blur_background_enabled" -> g(Icons.Rounded.Wallpaper, Cyan)
    "nowplaying_cover_blur_amount" -> g(Icons.Rounded.Deblur, Teal)
    "nowplaying_cover_blur_darken" -> g(Icons.Rounded.Brightness6, Slate)
    // network, downloads & storage
    "bypass_proxy" -> g(Icons.Rounded.VpnKeyOff, Graphite)
    "download_follow_playback_audio_quality" -> g(Icons.Rounded.Link, Green)
    "download_metadata_post_processing_enabled" -> g(Icons.Rounded.LocalOffer, Orange)
    "standardized_lyric_embedding_enabled" -> g(Icons.Rounded.Lyrics, Violet)
    "download_parallelism" -> g(Icons.Rounded.DynamicFeed, Blue)
    "download_directory_uri" -> g(Icons.Rounded.Folder, Blue)
    "download_file_name_template" -> g(Icons.Rounded.DriveFileRenameOutline, Graphite)
    "max_cache_size_bytes" -> g(Icons.Rounded.Storage, Slate)
    "clear_cache" -> g(Icons.Rounded.CleaningServices, Red)
    // accounts, backup & listen together
    "netease_login" -> g(Icons.Rounded.MusicNote, Red)
    "bili_login" -> g(Icons.Rounded.LiveTv, Pink)
    "youtube_login" -> g(Icons.Rounded.SmartDisplay, Red)
    "playlist_export" -> g(Icons.Rounded.IosShare, Blue)
    "playlist_import" -> g(Icons.Rounded.FileDownload, Green)
    "config_export" -> g(Icons.Rounded.Upload, Blue)
    "config_import" -> g(Icons.Rounded.SettingsBackupRestore, Green)
    "github_sync" -> g(Icons.Rounded.CloudSync, Graphite)
    "github_auto_sync" -> g(Icons.Rounded.Autorenew, Graphite)
    "webdav_sync" -> g(Icons.Rounded.Dns, Blue)
    "webdav_auto_sync" -> g(Icons.Rounded.Sync, Blue)
    "silent_github_sync_failure" -> g(Icons.Rounded.NotificationsOff, Graphite)
    "listen_together_join_room" -> g(Icons.Rounded.GroupAdd, Pink)
    "listen_together_server" -> g(Icons.Rounded.Dns, Teal)
    "listen_together_nickname" -> g(Icons.Rounded.Badge, Orange)
    "about_debug" -> g(Icons.Rounded.BugReport, Graphite)
    else -> null
}

/** Strips the `setting:` / `manual:` prefixes used by search ids and highlight targets. */
internal fun settingGlyphForTarget(targetId: String): SettingGlyph? =
    settingGlyphFor(targetId.substringAfter(':'))

@Composable
internal fun SettingGlyphTile(
    glyph: SettingGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(ContinuousRoundedCornerShape(size * 0.28f))
            .background(glyph.tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = glyph.icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.64f)
        )
    }
}

/** Hand-drawn symbols for concepts Material has no glyph for; 24-unit grid, even-odd cut-outs. */
internal object SettingSymbols {
    /** A lyric bubble floating over a screen, with two text lines cut out of it. */
    val FloatingLyrics: ImageVector by lazy {
        ImageVector.Builder("FloatingLyrics", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), fillAlpha = 0.45f) {
                // the screen behind
                moveTo(6f, 3f); lineTo(18f, 3f)
                curveTo(19.66f, 3f, 21f, 4.34f, 21f, 6f); lineTo(21f, 18f)
                curveTo(21f, 19.66f, 19.66f, 21f, 18f, 21f); lineTo(6f, 21f)
                curveTo(4.34f, 21f, 3f, 19.66f, 3f, 18f); lineTo(3f, 6f)
                curveTo(3f, 4.34f, 4.34f, 3f, 6f, 3f); close()
            }
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // floating bubble
                moveTo(5.5f, 8f); lineTo(18.5f, 8f)
                curveTo(19.88f, 8f, 21f, 9.12f, 21f, 10.5f); lineTo(21f, 13.5f)
                curveTo(21f, 14.88f, 19.88f, 16f, 18.5f, 16f); lineTo(5.5f, 16f)
                curveTo(4.12f, 16f, 3f, 14.88f, 3f, 13.5f); lineTo(3f, 10.5f)
                curveTo(3f, 9.12f, 4.12f, 8f, 5.5f, 8f); close()
                // lyric lines
                moveTo(6f, 10.25f); lineTo(16f, 10.25f); lineTo(16f, 11.5f); lineTo(6f, 11.5f); close()
                moveTo(6f, 12.75f); lineTo(12.5f, 12.75f); lineTo(12.5f, 14f); lineTo(6f, 14f); close()
            }
        }.build()
    }

    /** A phone top with a lyric line running through the status bar. */
    val StatusBarLyrics: ImageVector by lazy {
        ImageVector.Builder("StatusBarLyrics", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(7f, 2f); lineTo(17f, 2f)
                curveTo(18.66f, 2f, 20f, 3.34f, 20f, 5f); lineTo(20f, 22f); lineTo(18f, 22f); lineTo(18f, 10f)
                lineTo(6f, 10f); lineTo(6f, 22f); lineTo(4f, 22f); lineTo(4f, 5f)
                curveTo(4f, 3.34f, 5.34f, 2f, 7f, 2f); close()
                moveTo(7f, 5.4f); lineTo(14.5f, 5.4f); lineTo(14.5f, 6.8f); lineTo(7f, 6.8f); close()
            }
            path(fill = SolidColor(Color.Black), fillAlpha = 0.45f) {
                moveTo(6f, 10f); lineTo(18f, 10f); lineTo(18f, 22f); lineTo(6f, 22f); close()
            }
        }.build()
    }

    /** The pill-shaped island with a lyric line and a camera dot. */
    val DynamicIsland: ImageVector by lazy {
        ImageVector.Builder("DynamicIsland", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(7f, 7.5f); lineTo(17f, 7.5f)
                curveTo(19.49f, 7.5f, 21.5f, 9.51f, 21.5f, 12f)
                curveTo(21.5f, 14.49f, 19.49f, 16.5f, 17f, 16.5f); lineTo(7f, 16.5f)
                curveTo(4.51f, 16.5f, 2.5f, 14.49f, 2.5f, 12f)
                curveTo(2.5f, 9.51f, 4.51f, 7.5f, 7f, 7.5f); close()
                moveTo(5.5f, 11.3f); lineTo(13f, 11.3f); lineTo(13f, 12.7f); lineTo(5.5f, 12.7f); close()
                moveTo(17.2f, 10.6f)
                curveTo(17.97f, 10.6f, 18.6f, 11.23f, 18.6f, 12f)
                curveTo(18.6f, 12.77f, 17.97f, 13.4f, 17.2f, 13.4f)
                curveTo(16.43f, 13.4f, 15.8f, 12.77f, 15.8f, 12f)
                curveTo(15.8f, 11.23f, 16.43f, 10.6f, 17.2f, 10.6f); close()
            }
        }.build()
    }

    /** The island pill carrying a live waveform, for HyperOS super island lyrics. */
    val SuperIsland: ImageVector by lazy {
        ImageVector.Builder("SuperIsland", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(7f, 7f); lineTo(17f, 7f)
                curveTo(19.76f, 7f, 22f, 9.24f, 22f, 12f)
                curveTo(22f, 14.76f, 19.76f, 17f, 17f, 17f); lineTo(7f, 17f)
                curveTo(4.24f, 17f, 2f, 14.76f, 2f, 12f)
                curveTo(2f, 9.24f, 4.24f, 7f, 7f, 7f); close()
                // waveform bars
                moveTo(6.3f, 11f); lineTo(7.7f, 11f); lineTo(7.7f, 13f); lineTo(6.3f, 13f); close()
                moveTo(9.3f, 9.5f); lineTo(10.7f, 9.5f); lineTo(10.7f, 14.5f); lineTo(9.3f, 14.5f); close()
                moveTo(12.3f, 10.3f); lineTo(13.7f, 10.3f); lineTo(13.7f, 13.7f); lineTo(12.3f, 13.7f); close()
                moveTo(15.3f, 9f); lineTo(16.7f, 9f); lineTo(16.7f, 15f); lineTo(15.3f, 15f); close()
            }
        }.build()
    }

    /** Bluetooth rune with a small translation mark, for sending translated lines to devices. */
    val BluetoothTranslation: ImageVector by lazy {
        ImageVector.Builder("BluetoothTranslation", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
            ) {
                moveTo(4f, 7.5f); lineTo(11f, 14.5f); lineTo(7.5f, 18f); lineTo(7.5f, 4f)
                lineTo(11f, 7.5f); lineTo(4f, 14.5f)
            }
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // speech tile with an "A"
                moveTo(14.5f, 9f); lineTo(20.5f, 9f)
                curveTo(21.33f, 9f, 22f, 9.67f, 22f, 10.5f); lineTo(22f, 16.5f)
                curveTo(22f, 17.33f, 21.33f, 18f, 20.5f, 18f); lineTo(16f, 18f); lineTo(14f, 20f); lineTo(14f, 10.5f)
                curveTo(14f, 9.67f, 14.67f, 9f, 14.5f, 9f); close()
                moveTo(17.5f, 10.8f); lineTo(16f, 16f); lineTo(17.1f, 16f); lineTo(17.4f, 14.9f)
                lineTo(18.6f, 14.9f); lineTo(18.9f, 16f); lineTo(20f, 16f); lineTo(18.5f, 10.8f); close()
                moveTo(17.65f, 13.9f); lineTo(18f, 12.5f); lineTo(18.35f, 13.9f); close()
            }
        }.build()
    }
}

/** Plain row glyph, unfilled like the rest of the detail-page icons. */
@Composable
internal fun SettingGlyphIcon(
    glyph: SettingGlyph,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = glyph.icon,
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Drop-in for a settings row's leading [Icon]: in clean style it swaps in the row's own glyph
 * (looked up by [key]); [fallbackHue] is kept for callers but rows stay unfilled.
 */
@Composable
internal fun SettingRowIcon(
    key: String?,
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
    fallbackHue: Color? = null
) {
    val glyph = if (moe.ouom.neriplayer.ui.theme.NeriDesign.isClean) key?.let(::settingGlyphFor) else null
    Icon(
        imageVector = glyph?.icon ?: imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

@Composable
internal fun SettingRowIcon(
    key: String?,
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current
) {
    val glyph = if (moe.ouom.neriplayer.ui.theme.NeriDesign.isClean) key?.let(::settingGlyphFor) else null
    if (glyph != null) {
        Icon(imageVector = glyph.icon, contentDescription = contentDescription, modifier = modifier, tint = tint)
        return
    }
    Icon(painter = painter, contentDescription = contentDescription, modifier = modifier, tint = tint)
}

/** Hue shared by the floating-lyrics detail rows, matching the page's own tile. */
internal val FloatingLyricsRowHue = Mint
