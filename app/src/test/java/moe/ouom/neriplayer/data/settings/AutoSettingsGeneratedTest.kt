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
            R.drawable.ic_lyrics,
            AutoSettingsSchema.motion.advancedLyricsEnabled.iconRes
        )
        assertEquals(
            R.drawable.ic_lyricon,
            AutoSettingsSchema.lyrics.lyriconEnabled.iconRes
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
