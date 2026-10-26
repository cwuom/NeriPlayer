package moe.ouom.neriplayer.activity

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.locale.LanguageManager.Language
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsPageTransitionSceneTag
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsPageRowTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.io.File
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class MainActivityLanguageContinuityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun onboardingLanguageRoundTripsKeepActivityAndStepAndLocalizeTheNextDialog() =
        withPreparedActivity(onboarding = true) { scenario, originalActivity ->
            waitForText(label(AppR.string.onboarding_language_title, Language.CHINESE))
            LanguageRoundTrips.forEach { language ->
                val previous = LanguageManager.getCurrentLanguage(context)
                val option = when (language) {
                    Language.CHINESE -> CoreCommonR.string.language_display_chinese
                    Language.ENGLISH -> CoreCommonR.string.language_display_english
                    Language.SYSTEM -> error("此回归只切换显式语言")
                }
                composeRule.onNodeWithText(label(option, previous)).performScrollTo().performClick()
                waitForText(label(AppR.string.onboarding_language_title, language))
                assertSameActivity(scenario, originalActivity)
                assertStep(language, 1)
                capture("onboarding-${language.code}")

                clickText(AppR.string.onboarding_action_next, language)
                waitForText(label(AppR.string.onboarding_platforms_title, language))
                assertStep(language, 2)
                assertSameActivity(scenario, originalActivity)
                clickText(CoreCommonR.string.action_back, language)
                waitForText(label(AppR.string.onboarding_language_title, language))
                assertStep(language, 1)
            }

            clickText(AppR.string.onboarding_action_next, Language.CHINESE)
            waitForText(label(AppR.string.onboarding_platforms_title, Language.CHINESE))
            assertStep(Language.CHINESE, 2)
            clickText(AppR.string.onboarding_action_next, Language.CHINESE)
            awaitUi {
                composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() ||
                    textIsDisplayed(AppR.string.onboarding_playback_sources_title, Language.CHINESE)
            }
            if (composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                assertDialogText(AppR.string.onboarding_platforms_no_login_title)
                assertDialogText(AppR.string.onboarding_platforms_no_login_desc)
                clickText(AppR.string.onboarding_platforms_no_login_connect, Language.CHINESE)
                waitForText(label(AppR.string.onboarding_platforms_title, Language.CHINESE))
                assertStep(Language.CHINESE, 2)
            } else {
                // 已有授权时使用播放源说明弹窗，不点击连接、管理或退出账号
                composeRule.onNode(isToggleable()).performScrollTo().performClick()
                awaitUi { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
                assertDialogText(AppR.string.onboarding_playback_sources_confirm_title)
                assertDialogText(AppR.string.onboarding_playback_sources_confirm_desc)
                clickText(CoreCommonR.string.action_cancel, Language.CHINESE)
                assertStep(Language.CHINESE, 3)
            }
            assertSameActivity(scenario, originalActivity)
            assertFalse(runBlocking {
                SettingsRepository(context).startupOnboardingCompletedFlow.filterNotNull().first()
            })
        }

    @Test
    fun settingsLanguageRoundTripsKeepTheLanguagePageAndLocalizeItsDialog() =
        withPreparedActivity(onboarding = false) { scenario, originalActivity ->
            val languagePage = settingsPageRowTestTag(SettingsPage.Language)
            val homeScene = settingsPageTransitionSceneTag(null)
            waitForTag(homeScene)
            composeRule.onNode(
                androidx.compose.ui.test.hasScrollToIndexAction() and
                    hasAnyAncestor(hasTestTag(homeScene)),
                useUnmergedTree = true
            ).performScrollToNode(hasTestTag(languagePage))
            composeRule.onNodeWithTag(languagePage).performClick()
            val detailScene = settingsPageTransitionSceneTag(SettingsPage.Language)
            waitForTag(detailScene)

            LanguageRoundTrips.forEach { language ->
                val previous = LanguageManager.getCurrentLanguage(context)
                clickText(CoreCommonR.string.language_setting_title, previous)
                waitForText(label(CoreCommonR.string.language_select_title, previous))
                val option = if (language == Language.CHINESE) {
                    CoreCommonR.string.language_display_chinese
                } else {
                    CoreCommonR.string.language_display_english
                }
                composeRule.onNodeWithText(label(option, previous)).performClick()
                awaitUi { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
                waitForText(label(CoreCommonR.string.language_setting_title, language))
                waitForTag(detailScene)
                assertSameActivity(scenario, originalActivity)
                capture("settings-${language.code}")
            }

            clickText(CoreCommonR.string.language_setting_title, Language.CHINESE)
            waitForText(label(CoreCommonR.string.language_select_title, Language.CHINESE))
            assertDialogText(CoreCommonR.string.language_select_title)
            assertDialogText(CoreCommonR.string.action_close)
            clickText(CoreCommonR.string.action_close, Language.CHINESE)
            waitForTag(detailScene)
            assertSameActivity(scenario, originalActivity)
        }

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.takeIf(String::isNotBlank) ?: return
        val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(60)
        val directory = checkNotNull(context.getExternalFilesDir(null))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$safePrefix-$stage.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withPreparedActivity(
        onboarding: Boolean,
        verify: (ActivityScenario<MainActivity>, MainActivity) -> Unit
    ) {
        assertTrue("真实宿主回归只能在模拟器执行", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val settings = SettingsRepository(context)
        val preferences = context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)
        val savedLanguageCode = preferences.getString("selected_language", null)
        val savedLanguage = LanguageManager.getCurrentLanguage(context)
        val savedLocale = Locale.getDefault()
        val localeManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
        } else null
        val savedPlatformLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager?.applicationLocales
        } else null
        val savedDisclaimer = runBlocking { settings.disclaimerAcceptedFlow.filterNotNull().first() }
        val savedOnboarding = runBlocking { settings.startupOnboardingCompletedFlow.filterNotNull().first() }
        val savedDestination = runBlocking { settings.defaultStartDestinationFlow.first() }
        val savedAutoSource = runBlocking { settings.neteaseAutoSourceSwitchFlow.first() }
        val savedLocalFallback = runBlocking { settings.neteaseLocalSourceFallbackFlow.first() }
        val previewKey = booleanPreferencesKey("preview_theme_prompt_answered")
        val warningKey = booleanPreferencesKey("acknowledged")
        val savedPreview = runBlocking { context.dataStore.data.first()[previewKey] }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first()[warningKey] }
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(!onboarding)
                if (onboarding) settings.setNeteasePlaybackSourceFallback(false)
                else settings.setDefaultStartDestination(Destinations.Settings.route)
            }
            instrumentation.runOnMainSync { LanguageManager.setLanguage(context, Language.CHINESE) }
            instrumentation.waitForIdleSync()
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            lateinit var originalActivity: MainActivity
            activeScenario.onActivity { originalActivity = it }
            verify(activeScenario, originalActivity)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { scenario?.close() }
            cleanup {
                runBlocking {
                    settings.setDisclaimerAccepted(savedDisclaimer)
                    settings.setStartupOnboardingCompleted(savedOnboarding)
                    if (onboarding) {
                        settings.setNeteaseAutoSourceSwitch(savedAutoSource)
                        settings.setNeteaseLocalSourceFallback(savedLocalFallback)
                    } else settings.setDefaultStartDestination(savedDestination)
                    context.dataStore.edit {
                        if (savedPreview == null) it.remove(previewKey) else it[previewKey] = savedPreview
                    }
                    context.debugBuildWarningDataStore.edit {
                        if (savedWarning == null) it.remove(warningKey) else it[warningKey] = savedWarning
                    }
                }
            }
            cleanup { instrumentation.runOnMainSync { LanguageManager.setLanguage(context, savedLanguage) } }
            cleanup {
                val editor = preferences.edit()
                if (savedLanguageCode == null) editor.remove("selected_language")
                else editor.putString("selected_language", savedLanguageCode)
                assertTrue("应恢复语言设置", editor.commit())
            }
            cleanup {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedPlatformLocales != null) {
                    localeManager?.applicationLocales = savedPlatformLocales
                    instrumentation.waitForIdleSync()
                    assertEquals("应恢复系统应用语言", savedPlatformLocales, localeManager?.applicationLocales)
                }
            }
            Locale.setDefault(savedLocale)
            if (failure != null) cleanupFailures.forEach(failure::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun assertSameActivity(scenario: ActivityScenario<MainActivity>, expected: MainActivity) {
        scenario.onActivity { assertSame("切换语言应保留当前 Activity", expected, it) }
    }

    private fun assertStep(language: Language, step: Int) {
        val localized = localizedContext(language)
        composeRule.onNodeWithText(localized.getString(AppR.string.onboarding_step_counter, step, 9))
            .assertIsDisplayed()
    }

    private fun assertDialogText(resource: Int) {
        composeRule.onNode(
            androidx.compose.ui.test.hasText(label(resource, Language.CHINESE)) and hasAnyAncestor(isDialog())
        ).assertIsDisplayed()
    }

    private fun clickText(resource: Int, language: Language) {
        val text = label(resource, language)
        waitForText(text)
        awaitUi { composeRule.onNodeWithText(text).assertIsEnabled(); true }
        composeRule.onNodeWithText(text).performClick()
    }

    private fun localizedContext(language: Language): Context = context.createConfigurationContext(
        Configuration().apply { setLocale(Locale.forLanguageTag(language.code)) }
    )

    private fun label(resource: Int, language: Language): String = localizedContext(language).getString(resource)

    private fun textIsDisplayed(resource: Int, language: Language): Boolean =
        composeRule.onNodeWithText(label(resource, language)).isDisplayed()

    private fun waitForText(text: String) = awaitUi { composeRule.onNodeWithText(text).isDisplayed() }

    private fun waitForTag(tag: String) = awaitUi {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).isDisplayed()
    }

    private fun awaitUi(condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 30_000L) { runCatching(condition).getOrDefault(false) }
    }

    private companion object {
        val LanguageRoundTrips = listOf(Language.ENGLISH, Language.CHINESE, Language.ENGLISH, Language.CHINESE)
    }
}
