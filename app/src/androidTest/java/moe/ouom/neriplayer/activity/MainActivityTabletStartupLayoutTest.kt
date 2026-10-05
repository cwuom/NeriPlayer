package moe.ouom.neriplayer.activity

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.locale.getDisplayName
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityTabletStartupLayoutTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun firstStartup_disclaimerLeadsToTabletOnboardingWithFixedActions() {
        assumeComposeHostAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = context.resources.configuration
        assumeTrue(
            "需要至少 600dp 可用宽度的平板宿主",
            configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp >= 600
        )
        val settings = SettingsRepository(context)
        val originalDisclaimer = runBlocking {
            settings.disclaimerAcceptedFlow.filterNotNull().first()
        }
        val originalOnboarding = runBlocking {
            settings.startupOnboardingCompletedFlow.filterNotNull().first()
        }
        val originalUiScale = runBlocking { settings.uiDensityScaleFlow.first() }
        try {
            runBlocking {
                settings.setUiDensityScale(1f)
                settings.setDisclaimerAccepted(false)
                settings.setStartupOnboardingCompleted(false)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                UiFailureDiagnostics.onFailure("tablet-first-startup") {
                    var disclaimerTitle = ""
                    var agreeLabel = ""
                    var onboardingTitle = ""
                    var firstLanguageLabel = ""
                    var lastLanguageLabel = ""
                    var nextLabel = ""
                    scenario.onActivity { activity ->
                        assertTrue(
                            "真实 Activity 必须保留平板可用宽度",
                            activity.resources.configuration.screenWidthDp >= 600
                        )
                        val localized = LanguageManager.localizedContext(
                            activity,
                            LanguageManager.getCurrentLanguage(activity)
                        )
                        disclaimerTitle = localized.getString(CoreCommonR.string.disclaimer_title)
                        agreeLabel = localized.getString(CoreCommonR.string.disclaimer_agree_countdown)
                        onboardingTitle = localized.getString(CoreCommonR.string.onboarding_title)
                        firstLanguageLabel = LanguageManager.Language.entries.first().getDisplayName(localized)
                        lastLanguageLabel = LanguageManager.Language.entries.last().getDisplayName(localized)
                        nextLabel = localized.getString(CoreCommonR.string.onboarding_action_next)
                    }
                    waitForText(disclaimerTitle)
                    composeRule.onNodeWithText(disclaimerTitle).assertIsDisplayed()
                    waitForText(agreeLabel)
                    composeRule.onNodeWithText(agreeLabel).assertIsEnabled()
                    capture(context, "disclaimer")
                    composeRule.onNodeWithText(agreeLabel).performClick()

                    waitForText(onboardingTitle)
                    waitForText(firstLanguageLabel)
                    composeRule.waitForIdle()
                    val title = composeRule.onNodeWithText(onboardingTitle)
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val firstLanguage = composeRule.onNodeWithText(firstLanguageLabel)
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val initialActions = composeRule.onNodeWithText(nextLabel)
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("首次引导的语言设置应位于品牌说明右侧", firstLanguage.left >= title.right)
                    assertTrue("下一步应位于左侧操作栏", initialActions.right <= firstLanguage.left)
                    assertTrue("下一步应位于品牌说明下方", initialActions.top > title.bottom)
                    capture(context, "onboarding-first")

                    composeRule.onNodeWithText(lastLanguageLabel).performScrollTo().assertIsDisplayed()
                    assertEquals(
                        "滚动真实语言设置时下一步应固定在原位",
                        initialActions,
                        composeRule.onNodeWithText(nextLabel)
                            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    )
                    assertTrue(
                        "实际免责声明流程应持久化接受状态",
                        runBlocking { settings.disclaimerAcceptedFlow.filterNotNull().first() }
                    )
                }
            }
        } finally {
            runBlocking {
                settings.setUiDensityScale(originalUiScale)
                settings.setDisclaimerAccepted(originalDisclaimer)
                settings.setStartupOnboardingCompleted(originalOnboarding)
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MS) {
            runCatching { composeRule.onNodeWithText(text).isDisplayed() }.getOrDefault(false)
        }
    }

    private fun capture(context: Context, stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.takeIf(String::isNotBlank) ?: return
        val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(60)
        val directory = checkNotNull(context.getExternalFilesDir(null))
        val file = File(directory, "$safePrefix-$stage.png")
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("TabletStartupLayoutTest", "screenshot=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 30_000L
    }
}
