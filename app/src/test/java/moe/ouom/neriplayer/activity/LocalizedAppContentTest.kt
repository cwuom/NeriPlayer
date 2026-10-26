package moe.ouom.neriplayer.activity

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.overlay.LocalizedDialog
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.SongEncyclopediaRelatedPlaylistsSection
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.util.platform.applyOnePlusHighDensityDisplayCorrection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.util.Locale
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 32], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalizedAppContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val language = mutableStateOf(LanguageManager.Language.CHINESE)
    private val systemConfiguration = mutableStateOf(Configuration(Resources.getSystem().configuration))
    private val originalSystemConfiguration = Configuration(Resources.getSystem().configuration)
    private val observedWindowContexts = mutableMapOf<String, Pair<Context, String>>()

    @After
    fun restoreSystemConfiguration() {
        Resources.getSystem().configuration.setTo(originalSystemConfiguration)
    }

    @Test
    fun systemLanguageContinuesFollowingConfigurationAfterLeavingAnExplicitLanguage() {
        renderWithChineseBaseContext()
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        composeRule.runOnIdle { language.value = LanguageManager.Language.SYSTEM }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        changeSystemLanguage("zh-CN")
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        changeSystemLanguage("en-US")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun explicitLanguageRemainsSelectedWhenTheSystemLanguageChanges() {
        language.value = LanguageManager.Language.ENGLISH
        renderWithChineseBaseContext()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        changeSystemLanguage("zh-CN")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-sw360dp-w360dp-h800dp-normal-port-notnight-560dpi")
    fun changingLanguageKeepsOnePlusResourceAndConfigurationDensity() {
        ShadowBuild.setManufacturer("OnePlus")
        ShadowBuild.setBrand("OnePlus")
        val application = ApplicationProvider.getApplicationContext<Application>()
        val originalLanguage = LanguageManager.getCurrentLanguage(application)
        val originalLocale = Locale.getDefault()
        try {
            LanguageManager.setLanguage(application, LanguageManager.Language.CHINESE)
            val corrected = applyOnePlusHighDensityDisplayCorrection(LanguageManager.applyLanguage(application))
            var resourceDensityDpi = 0
            var configurationDensityDpi = 0
            fun assertCorrectedDensity() = composeRule.runOnIdle {
                assertEquals(532, resourceDensityDpi)
                assertEquals(532, configurationDensityDpi)
            }
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalContext provides corrected,
                    LocalConfiguration provides corrected.resources.configuration
                ) {
                    LocalizedAppContent(language.value) {
                        val resourceDensity = LocalResources.current.displayMetrics.densityDpi
                        val configurationDensity = LocalConfiguration.current.densityDpi
                        SideEffect {
                            resourceDensityDpi = resourceDensity
                            configurationDensityDpi = configurationDensity
                        }
                        Text(stringResource(AppR.string.nav_settings))
                    }
                }
            }
            composeRule.onNodeWithText("设置").assertIsDisplayed()
            assertCorrectedDensity()

            listOf(
                LanguageManager.Language.ENGLISH,
                LanguageManager.Language.SYSTEM,
                LanguageManager.Language.CHINESE
            ).forEach { selected ->
                composeRule.runOnIdle {
                    LanguageManager.setLanguage(application, selected)
                    language.value = selected
                }
                composeRule.onNodeWithText(
                    if (selected == LanguageManager.Language.CHINESE) "设置" else "Settings"
                ).assertIsDisplayed()
                assertCorrectedDensity()
            }
        } finally {
            LanguageManager.setLanguage(application, originalLanguage)
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun localizedDialogFollowsRepeatedLanguageChangesAndKeepsOpenContent() {
        assertOverlayLanguageChanges(LocalizationOverlay.NativeDialog)
    }

    @Test
    fun materialScaledAlertDialogFollowsRepeatedLanguageChangesAndKeepsOpenContent() {
        assertOverlayLanguageChanges(LocalizationOverlay.MaterialDialog)
    }

    @Test
    fun cleanScaledAlertDialogFollowsRepeatedLanguageChangesAndKeepsOpenContent() {
        assertOverlayLanguageChanges(LocalizationOverlay.CleanDialog)
    }

    @Test
    fun scaledModalBottomSheetFollowsRepeatedLanguageChangesAndKeepsOpenContent() {
        assertOverlayLanguageChanges(LocalizationOverlay.BottomSheet)
    }

    @Test
    fun rawMaterialAlertDialogFollowsRootResourcesAcrossRepeatedLanguageChanges() {
        language.value = LanguageManager.Language.ENGLISH
        val visible = mutableStateOf(false)
        composeRule.setContent {
            LocalizedAppContent(language.value) {
                if (visible.value) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = { Text(stringResource(CoreCommonR.string.action_confirm)) },
                        text = { LocalizedResourceText("raw") }
                    )
                }
            }
        }
        composeRule.runOnIdle { language.value = LanguageManager.Language.CHINESE }
        composeRule.runOnIdle { visible.value = true }
        assertResourceLanguage("raw", LanguageManager.Language.CHINESE)
        listOf(
            LanguageManager.Language.ENGLISH,
            LanguageManager.Language.CHINESE,
            LanguageManager.Language.ENGLISH,
            LanguageManager.Language.CHINESE
        ).forEach { selected ->
            composeRule.runOnIdle { language.value = selected }
            assertResourceLanguage("raw", selected)
        }
    }

    private fun assertOverlayLanguageChanges(overlay: LocalizationOverlay) {
        language.value = LanguageManager.Language.ENGLISH
        val visible = mutableStateOf(false)
        val rootLifetime = ContentLifetime()
        val overlayLifetime = ContentLifetime()
        var hostLanguage: String? = null
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { hostLanguage = view.context.resources.configuration.locales[0].language }
            LocalizedAppContent(language.value) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = overlay.style,
                    animateColors = false
                ) {
                    LocalizationProbe("root", rootLifetime)
                    if (visible.value) {
                        LocalizationOverlayContent(overlay, overlayLifetime)
                    }
                }
            }
        }

        assertProbeLanguage("root", LanguageManager.Language.ENGLISH)
        composeRule.onNodeWithTag("root-editor").performTextReplacement("root draft")
        composeRule.onNodeWithTag("root-counter").performClick()

        // Activity 保持英文，首次弹窗在切换中文后创建，避免平台语言更新掩盖独立 Composition 的回归
        composeRule.runOnIdle { language.value = LanguageManager.Language.CHINESE }
        assertProbeLanguage("root", LanguageManager.Language.CHINESE)
        composeRule.runOnIdle { visible.value = true }
        assertProbeLanguage("overlay", LanguageManager.Language.CHINESE)
        composeRule.onNodeWithTag("overlay-editor").performTextReplacement("overlay draft")
        composeRule.onNodeWithTag("overlay-counter").performClick()

        listOf(
            LanguageManager.Language.ENGLISH,
            LanguageManager.Language.CHINESE,
            LanguageManager.Language.ENGLISH,
            LanguageManager.Language.CHINESE
        ).forEach { selected ->
            composeRule.runOnIdle { language.value = selected }
            assertProbeLanguage("root", selected)
            assertProbeLanguage("overlay", selected)
            composeRule.onNodeWithTag("root-editor").assertTextEquals("root draft")
            composeRule.onNodeWithTag("overlay-editor").assertTextEquals("overlay draft")
            composeRule.onNodeWithTag("root-counter").assertTextEquals("count:1")
            composeRule.onNodeWithTag("overlay-counter").assertTextEquals("count:1")
            composeRule.runOnIdle {
                assertEquals("en", hostLanguage)
                assertContentWasNotReplaced(rootLifetime)
                assertContentWasNotReplaced(overlayLifetime)
            }
        }
    }

    @Composable
    private fun LocalizationProbe(tag: String, lifetime: ContentLifetime) {
        var draft by remember { mutableStateOf("") }
        var count by remember { mutableStateOf(0) }
        DisposableEffect(Unit) {
            lifetime.starts++
            onDispose { lifetime.disposals++ }
        }
        LaunchedEffect(Unit) { lifetime.launches++ }
        val context = LocalContext.current
        LaunchedEffect(context.applicationContext) { lifetime.applicationLoads++ }
        val configurationLanguage = LocalConfiguration.current.locales[0].language
        SideEffect {
            observedWindowContexts[tag] = context to configurationLanguage
        }
        Column {
            LocalizedResourceText(tag)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.testTag("$tag-editor"),
                singleLine = true
            )
            TextButton(onClick = { count++ }, modifier = Modifier.testTag("$tag-counter")) {
                Text("count:$count")
            }
            Box(Modifier.testTag("$tag-playlist")) {
                SongEncyclopediaRelatedPlaylistsSection(
                    playlists = listOf(
                        EncyclopediaRelatedPlaylist(
                            playlistId = 1L,
                            name = "Saved playlist",
                            playCount = 15_000L
                        )
                    ),
                    onOpenPlaylist = {}
                )
            }
        }
    }

    @Composable
    private fun LocalizedResourceText(tag: String) {
        val resources = LocalResources.current
        val confirm = resources.getString(CoreCommonR.string.action_confirm)
        val cancel = stringResource(CoreCommonR.string.action_cancel)
        val singleSong = pluralStringResource(AppR.plurals.home_song_count_format, 1, 1)
        val multipleSongs = pluralStringResource(AppR.plurals.home_song_count_format, 2, 2)
        val formattedNumber = stringResource(CoreCommonR.string.number_ten_thousand, 1.5)
        Text(
            text = "$confirm|$cancel|$singleSong|$multipleSongs|$formattedNumber",
            modifier = Modifier.testTag("$tag-resources")
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LocalizationOverlayContent(overlay: LocalizationOverlay, lifetime: ContentLifetime) {
        val confirmButton: @Composable () -> Unit = {
            TextButton(onClick = {}) { Text(stringResource(CoreCommonR.string.action_confirm)) }
        }
        val dismissButton: @Composable () -> Unit = {
            TextButton(onClick = {}) { Text(stringResource(CoreCommonR.string.action_cancel)) }
        }
        val title: @Composable () -> Unit = { Text(stringResource(AppR.string.nav_settings)) }
        val content: @Composable () -> Unit = { LocalizationProbe("overlay", lifetime) }
        when (overlay) {
            LocalizationOverlay.NativeDialog -> LocalizedDialog(onDismissRequest = {}) {
                Surface(content = content)
            }
            LocalizationOverlay.MaterialDialog, LocalizationOverlay.CleanDialog -> DensityScaledAlertDialog(
                onDismissRequest = {},
                confirmButton = confirmButton,
                dismissButton = dismissButton,
                title = title,
                text = content
            )
            LocalizationOverlay.BottomSheet -> DensityScaledModalBottomSheet(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                content()
            }
        }
    }

    private fun assertProbeLanguage(tag: String, selected: LanguageManager.Language) {
        assertResourceLanguage(tag, selected)
        val expectedContext = if (selected == LanguageManager.Language.CHINESE) "确定|zh|zh" else "Confirm|en|en"
        composeRule.runOnIdle {
            val (context, configurationLanguage) = checkNotNull(observedWindowContexts[tag])
            val confirm = context.getString(CoreCommonR.string.action_confirm)
            val locale = context.resources.configuration.locales[0].language
            assertEquals(expectedContext, "$confirm|$locale|$configurationLanguage")
        }
        val expectedPlayCount = if (selected == LanguageManager.Language.CHINESE) "1.5万" else "15.0K"
        val expectedPlaylistCount = if (selected == LanguageManager.Language.CHINESE) {
            "$expectedPlayCount 次播放"
        } else {
            "$expectedPlayCount plays"
        }
        composeRule.onNode(
            hasText(expectedPlaylistCount) and hasAnyAncestor(hasTestTag("$tag-playlist")),
            useUnmergedTree = true
        ).assertTextEquals(expectedPlaylistCount)
    }

    private fun assertResourceLanguage(tag: String, selected: LanguageManager.Language) {
        val expected = when (selected) {
            LanguageManager.Language.CHINESE -> "确定|取消|1首|2首|1.5万"
            LanguageManager.Language.ENGLISH -> "Confirm|Cancel|1 song|2 songs|1.5K"
            LanguageManager.Language.SYSTEM -> error("This fixture exercises explicit language changes")
        }
        composeRule.onNodeWithTag("$tag-resources").assertTextEquals(expected)
    }

    private fun assertContentWasNotReplaced(lifetime: ContentLifetime) {
        assertEquals(1, lifetime.starts)
        assertEquals(1, lifetime.launches)
        assertEquals(1, lifetime.applicationLoads)
        assertEquals(0, lifetime.disposals)
    }

    private class ContentLifetime {
        var starts = 0
        var launches = 0
        var applicationLoads = 0
        var disposals = 0
    }

    private enum class LocalizationOverlay(val style: UiStyle) {
        NativeDialog(UiStyle.Material),
        MaterialDialog(UiStyle.Material),
        CleanDialog(UiStyle.Clean),
        BottomSheet(UiStyle.Material)
    }

    private fun renderWithChineseBaseContext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val baseContext = LanguageManager.localizedContext(application, LanguageManager.Language.CHINESE)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides baseContext,
                LocalConfiguration provides systemConfiguration.value
            ) {
                LocalizedAppContent(language.value) {
                    Text(stringResource(AppR.string.nav_settings))
                }
            }
        }
    }

    private fun changeSystemLanguage(languageTags: String) {
        composeRule.runOnIdle {
            Resources.getSystem().configuration.setLocales(LocaleList.forLanguageTags(languageTags))
            systemConfiguration.value = Configuration(Resources.getSystem().configuration)
        }
    }
}
