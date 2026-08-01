package moe.ouom.neriplayer.ui.screen.tab.settings.page

import android.content.Context
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSections
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.search.SearchTextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class SettingsPageTest {
    @Test
    fun settingsHomePageGroupsDoNotCaptureNullDuringEnumInitialization() {
        @Suppress("UNCHECKED_CAST")
        val groups = SettingsHomePageGroups as List<List<SettingsPage?>>

        groups.flatten().forEach { page ->
            assertNotNull(page)
        }
    }

    @Test
    fun schemaBackedPagesUseSourceMetadata() {
        assertEquals(AutoSettingsSchema.general.metadata.titleRes, SettingsPage.General.titleRes)
        assertEquals(AutoSettingsSchema.general.metadata.descriptionRes, SettingsPage.General.descriptionRes)
        assertEquals(AutoSettingsSchema.backup.metadata.titleRes, SettingsPage.Backup.titleRes)
        assertEquals(AutoSettingsSchema.backup.metadata.descriptionRes, SettingsPage.Backup.descriptionRes)
    }

    @Test
    fun usbExclusiveBackTargetReturnsPlaybackPage() {
        assertEquals(SettingsPage.Playback, SettingsPage.UsbExclusive.backTargetPage())
        assertEquals(null, SettingsPage.Playback.backTargetPage())
    }

    @Test
    fun displaySectionSettingsOpenPersonalizationPage() {
        assertEquals(SettingsPage.Personalization, settingsPageForSection(AutoSettingsSections.display))
    }

    @Test
    fun accountsPageIsPinnedAsTheFirstHomeItem() {
        assertEquals(SettingsPage.Accounts, SettingsHomePageGroups.first().first())
    }

    @Test
    fun themePersonalizationAndMotionStayInOneHomeGroup() {
        val visualGroup = SettingsHomePageGroups.first {
            SettingsPage.Theme in it
        }

        assertEquals(
            listOf(SettingsPage.Theme, SettingsPage.Personalization, SettingsPage.Motion),
            visualGroup
        )
    }

    @Test
    fun homeCardSettingsUseConcreteTitlesForSearch() {
        fun titleRes(keyName: String): Int {
            return AutoSettingsMetadata.settings.first { it.keyName == keyName }.titleRes
        }

        assertEquals(R.string.player_continue, titleRes("home_card_continue"))
        assertEquals(R.string.recommend_trending, titleRes("home_card_trending"))
        assertEquals(R.string.recommend_radar, titleRes("home_card_radar"))
        assertEquals(R.string.recommend_for_you, titleRes("home_card_recommended"))
    }

    @Test
    fun neteaseSourceFallbackSettingsOpenPlaybackSourcePage() {
        val autoSourceSwitch = AutoSettingsMetadata.settings.first {
            it.keyName == "netease_auto_source_switch"
        }
        val localSourceFallback = AutoSettingsMetadata.settings.first {
            it.keyName == "netease_local_source_fallback"
        }

        assertEquals(SettingsPage.PlaybackSource, autoSourceSwitch.settingsPage())
        assertEquals(SettingsPage.PlaybackSource, localSourceFallback.settingsPage())
    }

    @Test
    fun dynamicColorSettingOpensThemePage() {
        val dynamicColor = AutoSettingsMetadata.settings.first {
            it.keyName == "dynamic_color"
        }

        assertEquals(AutoSettingsSections.theme, dynamicColor.section)
        assertEquals(SettingsPage.Theme, dynamicColor.settingsPage())
        assertEquals("setting:dynamic_color", dynamicColor.searchTargetId())
    }

    @Test
    fun dynamicColorOnlyRedirectsHiddenThemeColorHighlight() {
        fun themeEntry(targetId: String): SettingsSearchEntry {
            return SettingsSearchEntry(
                id = targetId,
                page = SettingsPage.Theme,
                title = "主题",
                description = "主题",
                tokens = listOf(targetId),
                targetId = targetId,
                order = 0
            )
        }

        assertEquals(
            "manual:theme_palette_style",
            resolveSettingsSearchHighlightTarget(themeEntry("manual:theme_palette_style"), dynamicColor = true)
        )
        assertEquals(
            "manual:theme_color_spec",
            resolveSettingsSearchHighlightTarget(themeEntry("manual:theme_color_spec"), dynamicColor = true)
        )
        assertEquals(
            "setting:dynamic_color",
            resolveSettingsSearchHighlightTarget(themeEntry("manual:theme_seed_color"), dynamicColor = true)
        )
        assertEquals(
            "manual:theme_seed_color",
            resolveSettingsSearchHighlightTarget(themeEntry("manual:theme_seed_color"), dynamicColor = false)
        )
    }

    @Test
    fun themeSearchUsesSeparateColorCardAnchors() {
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Theme,
                targetId = "manual:theme_mode"
            ).itemIndex
        )
        assertEquals(
            2,
            settingsSearchScrollAnchor(
                page = SettingsPage.Theme,
                targetId = "setting:dynamic_color"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Theme,
                targetId = "manual:theme_palette_style"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Theme,
                targetId = "manual:theme_color_spec"
            ).itemIndex
        )
    }

    @Test
    fun mobileDataQualitySearchHighlightsItsRequiredControlWhenHidden() {
        val entry = SettingsSearchEntry(
            id = "setting:mobile_data_bili_audio_quality",
            page = SettingsPage.AudioQuality,
            title = "哔哩哔哩流量音质",
            description = "",
            tokens = listOf("流量音质"),
            targetId = "setting:mobile_data_bili_audio_quality",
            order = 0
        )

        assertEquals(
            "setting:mobile_data_bili_audio_quality",
            resolveSettingsSearchHighlightTarget(
                entry = entry,
                dynamicColor = false,
                mobileDataFollowDefaultAudioQuality = false
            )
        )
        assertEquals(
            "setting:mobile_data_follow_default_audio_quality",
            resolveSettingsSearchHighlightTarget(
                entry = entry,
                dynamicColor = false,
                mobileDataFollowDefaultAudioQuality = true
            )
        )
    }

    @Test
    fun backgroundControlsHighlightTheirRequiredImageSettingWhenHidden() {
        val entry = SettingsSearchEntry(
            id = "setting:background_image_alpha",
            page = SettingsPage.Personalization,
            title = "背景透明度",
            description = "",
            tokens = listOf("透明度"),
            targetId = "setting:background_image_alpha",
            order = 0
        )

        assertEquals(
            "setting:background_image_alpha",
            resolveSettingsSearchHighlightTarget(
                entry = entry,
                dynamicColor = false,
                hasCustomBackground = true
            )
        )
        assertEquals(
            "setting:background_image_uri",
            resolveSettingsSearchHighlightTarget(
                entry = entry,
                dynamicColor = false,
                hasCustomBackground = false
            )
        )
    }

    @Test
    fun personalizationSearchUsesTheMatchingCardAnchor() {
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:default_start_destination"
            ).itemIndex
        )
        assertEquals(
            2,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:home_card_radar"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:nowplaying_progress_show_audio_spec"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:nowplaying_toolbar_dock_enabled"
            ).itemIndex
        )
        assertEquals(
            5,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:lyrics_page_lyric_font_scale"
            ).itemIndex
        )
        assertEquals(
            6,
            settingsSearchScrollAnchor(
                page = SettingsPage.Personalization,
                targetId = "setting:background_image_uri"
            ).itemIndex
        )
    }

    @Test
    fun generalSearchUsesDefaultAnchorForUiScale() {
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.General,
                targetId = "setting:ui_density_scale"
            ).itemIndex
        )
    }

    @Test
    fun automaticSyncSearchTargetsEachProviderControl() {
        val entries = listOf(
            SettingsSearchEntry(
                id = "manual:github_auto_sync",
                page = SettingsPage.Backup,
                title = "自动同步",
                description = "修改后自动同步到 GitHub",
                tokens = listOf("自动同步", "github", "zidongtongbu"),
                targetId = "manual:github_auto_sync",
                order = 0
            ),
            SettingsSearchEntry(
                id = "manual:webdav_auto_sync",
                page = SettingsPage.Backup,
                title = "自动同步",
                description = "修改后自动同步到 WebDAV",
                tokens = listOf("自动同步", "webdav", "zidongtongbu"),
                targetId = "manual:webdav_auto_sync",
                order = 1
            )
        )

        assertEquals(
            listOf("manual:github_auto_sync", "manual:webdav_auto_sync"),
            searchSettingsEntries(entries, "自动同步").map { it.targetId }
        )
    }

    @Test
    fun generalPageAliasesDoNotLeakLanguageIntoEveryGeneralSetting() {
        val haptic = AutoSettingsMetadata.settings.first {
            it.keyName == "haptic_feedback_enabled"
        }
        val tokens = haptic.searchTokens(
            title = "触感反馈",
            description = "开启后点击按钮时会有震动反馈"
        )

        assertNull(SearchTextMatcher.score("语言", tokens))
        assertNull(SearchTextMatcher.score("yuyan", tokens))
    }

    @Test
    fun languageQueryReturnsLanguageTargetWithoutHapticFeedback() {
        val haptic = AutoSettingsMetadata.settings.first {
            it.keyName == "haptic_feedback_enabled"
        }
        val entries = listOf(
            SettingsSearchEntry(
                id = "manual:language",
                page = SettingsPage.General,
                title = "语言",
                description = "选择语言",
                tokens = listOf("语言", "语言设置", "yuyan", "language"),
                targetId = "manual:language",
                order = 4
            ),
            SettingsSearchEntry(
                id = "setting:haptic_feedback_enabled",
                page = SettingsPage.General,
                title = "触感反馈",
                description = "开启后点击按钮时会有震动反馈",
                tokens = haptic.searchTokens(
                    title = "触感反馈",
                    description = "开启后点击按钮时会有震动反馈"
                ),
                targetId = "setting:haptic_feedback_enabled",
                order = 45
            )
        )

        assertEquals(
            listOf("manual:language"),
            searchSettingsEntries(entries, "语言").map { it.id }
        )
    }

    @Test
    fun backupSearchUsesProviderCardAnchors() {
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:playlist_export"
            ).itemIndex
        )
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:playlist_import"
            ).itemIndex
        )
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:config_export"
            ).itemIndex
        )
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:config_import"
            ).itemIndex
        )
        assertEquals(
            2,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:backup_history"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:github_sync"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:github_auto_sync"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "setting:silent_github_sync_failure"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:webdav_sync"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Backup,
                targetId = "manual:webdav_auto_sync"
            ).itemIndex
        )
    }

    @Test
    fun backupImportExportActionsAreSearchable() {
        val entries = manualSettingsSearchEntries(settingsStringContext())

        assertEquals(
            listOf("manual:playlist_export"),
            searchSettingsEntries(entries, "导出歌单").map { it.targetId }
        )
        assertEquals(
            listOf("manual:playlist_import"),
            searchSettingsEntries(entries, "drgd").map { it.targetId }
        )
        assertEquals(
            listOf("manual:config_export"),
            searchSettingsEntries(entries, "导出配置").map { it.targetId }
        )
        assertEquals(
            listOf("manual:config_import"),
            searchSettingsEntries(entries, "drpz").map { it.targetId }
        )
    }

    @Test
    fun dependentSettingsHighlightTheirPrimaryControls() {
        fun targetId(keyName: String): String {
            return AutoSettingsMetadata.settings.first { it.keyName == keyName }.searchTargetId()
        }

        assertEquals("setting:playback_fade_in", targetId("playback_fade_in_duration_ms"))
        assertEquals("setting:playback_fade_in", targetId("playback_fade_out_duration_ms"))
        assertEquals("setting:playback_crossfade_next", targetId("playback_crossfade_in_duration_ms"))
        assertEquals("setting:playback_crossfade_next", targetId("playback_crossfade_out_duration_ms"))
        assertEquals("setting:enhanced_advanced_blur_enabled", targetId("enhanced_advanced_blur_radius_dp"))
        assertEquals("setting:nowplaying_cover_blur_background_enabled", targetId("nowplaying_cover_blur_amount"))
        assertEquals("setting:lyric_blur_enabled", targetId("lyric_blur_amount"))
        assertEquals(
            "setting:download_metadata_post_processing_enabled",
            targetId("standardized_lyric_embedding_enabled")
        )
    }

    @Test
    fun motionSearchUsesTheMatchingCardAnchor() {
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Motion,
                targetId = "setting:advanced_lyrics_enabled"
            ).itemIndex
        )
        assertEquals(
            2,
            settingsSearchScrollAnchor(
                page = SettingsPage.Motion,
                targetId = "setting:enhanced_advanced_blur_enabled"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Motion,
                targetId = "setting:nowplaying_dynamic_background_enabled"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Motion,
                targetId = "setting:lyric_blur_enabled"
            ).itemIndex
        )
    }

    @Test
    fun playbackLyricsAndStorageSearchUseMatchingCardAnchors() {
        assertEquals(
            2,
            settingsSearchScrollAnchor(
                page = SettingsPage.Playback,
                targetId = "setting:playback_volume_normalization_enabled"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Playback,
                targetId = "setting:playback_fade_in"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Playback,
                targetId = "setting:playback_crossfade_next"
            ).itemIndex
        )
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Lyrics,
                targetId = "setting:floating_lyrics_enabled"
            ).itemIndex
        )
        assertEquals(
            3,
            settingsSearchScrollAnchor(
                page = SettingsPage.Lyrics,
                targetId = "setting:cloud_music_lyric_default_offset_ms"
            ).itemIndex
        )
        assertEquals(
            1,
            settingsSearchScrollAnchor(
                page = SettingsPage.Storage,
                targetId = "setting:download_directory_uri"
            ).itemIndex
        )
        assertEquals(
            4,
            settingsSearchScrollAnchor(
                page = SettingsPage.Storage,
                targetId = "manual:clear_cache"
            ).itemIndex
        )
    }

    @Test
    fun independentSliderSettingsKeepTheirOwnHighlightTargets() {
        fun targetId(keyName: String): String {
            return AutoSettingsMetadata.settings.first { it.keyName == keyName }.searchTargetId()
        }

        assertEquals("setting:background_image_blur", targetId("background_image_blur"))
        assertEquals("setting:background_image_alpha", targetId("background_image_alpha"))
    }

    @Test
    fun storageOwnedDownloadSettingsOpenStoragePage() {
        fun settingOf(keyName: String) = AutoSettingsMetadata.settings.first { it.keyName == keyName }

        fun pageOf(keyName: String): SettingsPage {
            return settingOf(keyName).settingsPage()
                ?: error("missing page for $keyName")
        }

        assertEquals(AutoSettingsSections.storage, settingOf("download_directory_uri").section)
        assertEquals(AutoSettingsSections.storage, settingOf("download_file_name_template").section)
        assertEquals(AutoSettingsSections.download, settingOf("download_parallelism").section)
        assertEquals(SettingsPage.Storage, pageOf("download_directory_uri"))
        assertEquals(SettingsPage.Storage, pageOf("download_file_name_template"))
        assertEquals(SettingsPage.Downloads, pageOf("download_parallelism"))
        assertEquals(SettingsPage.Downloads, pageOf("download_metadata_post_processing_enabled"))
    }

    @Test
    fun githubSearchDoesNotReturnDownloadThreadSetting() {
        val entries = listOf(
            SettingsSearchEntry(
                id = "manual:github_auto_sync",
                page = SettingsPage.Backup,
                title = "自动同步",
                description = "修改后自动同步到 GitHub",
                tokens = listOf("自动同步", "github", "zidongtongbu"),
                targetId = "manual:github_auto_sync",
                order = 0
            ),
            SettingsSearchEntry(
                id = "setting:download_parallelism",
                page = SettingsPage.Downloads,
                title = "下载线程数量",
                description = "控制同时下载的歌曲任务数量",
                tokens = AutoSettingsMetadata.settings
                    .first { it.keyName == "download_parallelism" }
                    .searchTokens(
                        title = "下载线程数量",
                        description = "控制同时下载的歌曲任务数量"
                    ),
                targetId = "setting:download_parallelism",
                order = 1
            )
        )

        assertEquals(
            listOf("manual:github_auto_sync"),
            searchSettingsEntries(entries, "GitHub").map { it.id }
        )
    }

    @Test
    fun settingsSearchResultsDeduplicateSameVisibleEntry() {
        val entries = listOf(
            SettingsSearchEntry(
                id = "page:About",
                page = SettingsPage.About,
                title = "关于",
                description = "关于",
                tokens = listOf("github", "关于"),
                targetId = "page:About",
                order = 10
            ),
            SettingsSearchEntry(
                id = "manual:about_debug",
                page = SettingsPage.About,
                title = "关于",
                description = "关于",
                tokens = listOf("github", "debug"),
                targetId = "manual:about_debug",
                order = 20
            )
        )

        val results = searchSettingsEntries(entries, "github")

        assertEquals(listOf("page:About"), results.map { it.id })
    }

    private fun settingsStringContext(): Context {
        return Mockito.mock(Context::class.java, Answer { invocation ->
            val resourceId = invocation.arguments.firstOrNull() as? Int
            if (invocation.method.name == "getString" && resourceId != null) {
                TestSettingsStrings[resourceId] ?: "res:$resourceId"
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
    }

    private companion object {
        val TestSettingsStrings = mapOf(
            R.string.playlist_export to "导出歌单",
            R.string.playlist_import to "导入歌单",
            R.string.playlist_export_desc to "将歌单导出为备份文件",
            R.string.playlist_import_desc to "从备份文件恢复歌单",
            R.string.settings_export_config to "导出配置文件",
            R.string.settings_import_config to "导入配置文件",
            R.string.settings_export_config_desc to "导出设置、登录信息和同步配置",
            R.string.settings_import_config_desc to "从配置文件恢复设置、登录信息和同步配置"
        )
    }
}
