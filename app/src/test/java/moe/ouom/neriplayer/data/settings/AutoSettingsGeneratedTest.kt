package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsBackupKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSections
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSettingsGeneratedTest {
    @Test
    fun generatedBackupKeysCoverSettingsSchema() {
        val booleanKeyNames = AutoSettingsBackupKeys.booleanKeys.map { it.name }.toSet()
        val floatKeyNames = AutoSettingsBackupKeys.floatKeys.map { it.name }.toSet()
        val intKeyNames = AutoSettingsBackupKeys.intKeys.map { it.name }.toSet()
        val longKeyNames = AutoSettingsBackupKeys.longKeys.map { it.name }.toSet()
        val stringKeyNames = AutoSettingsBackupKeys.stringKeys.map { it.name }.toSet()

        assertTrue(
            "display switch should be exportable",
            "nowplaying_keep_screen_on" in booleanKeyNames
        )
        assertTrue(
            "general switch should be exportable",
            "haptic_feedback_enabled" in booleanKeyNames
        )
        assertTrue(
            "general idle shutdown duration should be exportable",
            "playback_service_idle_shutdown_minutes" in intKeyNames
        )
        assertTrue(
            "motion switch should be exportable",
            "advanced_lyrics_enabled" in booleanKeyNames
        )
        assertTrue(
            "backup switch should be exportable",
            "silent_github_sync_failure" in booleanKeyNames
        )
        assertTrue(
            "key-only setting should still be exportable",
            "dynamic_color" in booleanKeyNames
        )
        assertTrue(
            "display float should be exportable",
            "ui_density_scale" in floatKeyNames
        )
        assertTrue(
            "playback long should be exportable",
            "max_cache_size_bytes" in longKeyNames
        )
        assertTrue(
            "volume normalization should be exportable",
            "playback_volume_normalization_enabled" in booleanKeyNames
        )
        assertTrue(
            "theme string should be exportable",
            "theme_color_palette_v2" in stringKeyNames
        )
        assertTrue(
            "download string should be exportable",
            "download_directory_uri" in stringKeyNames
        )
        assertTrue(
            "display lyrics switch should be exportable",
            "show_lyric_translation" in booleanKeyNames
        )
        assertTrue(
            "external bluetooth lyrics switch should be exportable",
            "external_bluetooth_lyrics_enabled" in booleanKeyNames
        )
    }

    @Test
    fun generatedSectionConstantsCoverSettingsScopes() {
        assertEquals("general", AutoSettingsSections.general)
        assertEquals("theme", AutoSettingsSections.theme)
        assertEquals("audioQuality", AutoSettingsSections.audioQuality)
        assertEquals("personalization", AutoSettingsSections.personalization)
        assertEquals("display", AutoSettingsSections.display)
        assertEquals("motion", AutoSettingsSections.motion)
        assertEquals("lyrics", AutoSettingsSections.lyrics)
        assertEquals("network", AutoSettingsSections.network)
        assertEquals("download", AutoSettingsSections.download)
        assertEquals("trafficManagement", AutoSettingsSections.trafficManagement)
        assertEquals("storage", AutoSettingsSections.storage)
        assertEquals("backup", AutoSettingsSections.backup)
        assertEquals("playback", AutoSettingsSections.playback)
    }

    @Test
    fun generatedMetadataCoversSectionsAndCustomSettings() {
        val sectionKeys = AutoSettingsMetadata.sections.map { it.key }

        assertEquals(
            listOf(
                AutoSettingsSections.general,
                AutoSettingsSections.theme,
                AutoSettingsSections.audioQuality,
                AutoSettingsSections.personalization,
                AutoSettingsSections.display,
                AutoSettingsSections.motion,
                AutoSettingsSections.lyrics,
                AutoSettingsSections.network,
                AutoSettingsSections.download,
                AutoSettingsSections.trafficManagement,
                AutoSettingsSections.storage,
                AutoSettingsSections.backup,
                AutoSettingsSections.playback
            ),
            sectionKeys
        )
        assertEquals(
            R.string.settings_playback,
            AutoSettingsMetadata.section(AutoSettingsSections.playback)?.titleRes
        )
        assertEquals(
            R.string.settings_general,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.general).titleRes
        )
        assertEquals(
            R.string.settings_general_desc,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.general).descriptionRes
        )
        assertEquals(
            AutoSettingIcon.Settings,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.general).icon
        )
        assertEquals(
            AutoSettingIcon.Palette,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.theme).icon
        )
        assertEquals(
            AutoSettingIcon.Audiotrack,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.audioQuality).icon
        )
        assertEquals(
            AutoSettingIcon.PlaylistPlay,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.playback).icon
        )
        assertEquals(
            AutoSettingIcon.Sync,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.backup).icon
        )
        assertEquals(
            R.string.settings_traffic_management,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.trafficManagement).titleRes
        )
        assertEquals(
            AutoSettingIcon.Analytics,
            AutoSettingsMetadata.requireSection(AutoSettingsSections.trafficManagement).icon
        )

        val playbackFade = AutoSettingsMetadata.setting("playback_fade_in")
        assertEquals(SettingUiType.Custom, playbackFade?.ui)
        assertEquals(SettingAccessMode.KeyOnly, playbackFade?.access)
        assertEquals(R.string.settings_playback_fade_in, playbackFade?.titleRes)

        val audioQuality = AutoSettingsMetadata.setting("audio_quality")
        assertEquals(SettingValueType.String, audioQuality?.valueType)
        assertEquals(SettingUiType.Custom, audioQuality?.ui)
        assertEquals(AutoSettingsSections.audioQuality, audioQuality?.section)

        val displaySettings = AutoSettingsMetadata.settingsIn(AutoSettingsSections.display)
        assertTrue(
            "display metadata should include both generated switches and custom rows",
            displaySettings.any { it.keyName == "background_image_uri" && it.ui == SettingUiType.Custom }
        )
        assertTrue(
            "display metadata should include generated switch rows",
            displaySettings.any { it.keyName == "show_lyric_translation" && it.ui == SettingUiType.Switch }
        )

        val lyricsSettings = AutoSettingsMetadata.settingsIn(AutoSettingsSections.lyrics)
        assertTrue(
            "lyrics metadata should include Lyricon switch",
            lyricsSettings.any { it.keyName == "lyricon_enabled" && it.ui == SettingUiType.Switch }
        )
        assertTrue(
            "lyrics metadata should include status bar lyrics switch",
            lyricsSettings.any {
                it.keyName == "status_bar_lyrics_enabled" &&
                    it.valueType == SettingValueType.Boolean &&
                    it.ui == SettingUiType.Switch
            }
        )
        assertTrue(
            "lyrics metadata should include external bluetooth lyrics switch",
            lyricsSettings.any {
                it.keyName == "external_bluetooth_lyrics_enabled" &&
                    it.ui == SettingUiType.Switch
            }
        )
        assertTrue(
            "lyrics metadata should include source offset sliders",
            lyricsSettings.any { it.keyName == "cloud_music_lyric_default_offset_ms" && it.ui == SettingUiType.Custom }
        )
    }

    @Test
    fun generatedSectionScopesExposeConvenientMetadataAccess() {
        val displayScope = AutoSettingsScopes.display

        assertEquals(AutoSettingsSections.display, displayScope.key)
        assertEquals(AutoSettingsMetadata.requireSection(AutoSettingsSections.display), displayScope.info)
        assertTrue(
            "display scope should expose section settings",
            displayScope.settings.any { it.keyName == "show_lyric_translation" }
        )
        assertEquals(
            AutoSettingsMetadata.requireSetting(SettingsKeys.SHOW_LYRIC_TRANSLATION),
            displayScope.settings.first { it.keyName == "show_lyric_translation" }
        )
    }

    @Test
    fun generatedSettingsKeysKeepLegacyNames() {
        assertEquals("dev_mode_enabled", SettingsKeys.KEY_DEV_MODE.name)
        assertEquals("theme_color_palette_v2", SettingsKeys.THEME_COLOR_PALETTE.name)
    }

    @Test
    fun sourceFirstSettingSpecExposesDatastoreKeyAndDefaultValue() {
        val setting = AutoSettingsSchema.general.hapticFeedbackEnabled

        assertEquals("haptic_feedback_enabled", setting.preferencesKey.name)
        assertEquals(true, setting.defaultValue)
    }

    @Test
    fun schemaKeepsOriginalIconSources() {
        assertEquals(
            AutoSettingIcon.AdsClick,
            AutoSettingsSchema.general.hapticFeedbackEnabled.icon
        )
        assertEquals(
            AutoSettingIcon.Info,
            AutoSettingsSchema.display.showCoverSourceBadge.icon
        )
        assertEquals(
            AutoSettingIcon.Subtitles,
            AutoSettingsSchema.display.showLyricTranslation.icon
        )
        assertEquals(
            AutoSettingIcon.Keyboard,
            AutoSettingsSchema.display.lyricTranslationUsePhonetic.icon
        )
        assertEquals(
            R.drawable.ic_lyrics,
            AutoSettingsSchema.motion.advancedLyricsEnabled.iconRes
        )
        assertEquals(
            R.drawable.ic_lyricon,
            AutoSettingsSchema.lyrics.lyriconEnabled.iconRes
        )
        assertEquals(
            R.drawable.ic_statusbar,
            AutoSettingsSchema.lyrics.statusBarLyrics.iconRes
        )
        assertEquals(
            AutoSettingIcon.BluetoothAudio,
            AutoSettingsSchema.lyrics.externalBluetoothLyricsEnabled.icon
        )
        assertEquals(
            AutoSettingIcon.Error,
            AutoSettingsSchema.backup.silentGitHubSyncFailure.icon
        )
        assertEquals(
            R.drawable.ic_netease_cloud_music,
            AutoSettingsSchema.audioQuality.audioQuality.iconRes
        )
        assertEquals(
            R.drawable.ic_i18n,
            AutoSettingsSchema.general.internationalizationEnabled.iconRes
        )
    }
}
