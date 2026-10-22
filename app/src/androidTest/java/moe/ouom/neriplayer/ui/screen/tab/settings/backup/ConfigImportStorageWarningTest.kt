package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfigImportStorageWarningTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalMotionStyle = NeriMotionStyle.clean
    private val visible = mutableStateOf(true)
    private var confirmCalls = 0
    private var dismissCalls = 0
    private var contentInstalled = false

    @Before
    fun verifyHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun clearDialog() {
        try {
            if (contentInstalled) {
                composeRule.runOnIdle { visible.value = false }
                composeRule.waitForIdle()
            }
        } finally {
            NeriMotionStyle.clean = originalMotionStyle
        }
    }

    @Test
    fun cleanLightWaitsForConfirmationAndCanBeCancelled() = assertConfirmation(dark = false)

    @Test
    fun cleanDarkWaitsForConfirmationAndCanBeCancelled() = assertConfirmation(dark = true)

    @Test
    fun systemBackCancelsWithoutConfirming() {
        render(dark = false)

        onView(isRoot()).inRoot(isDialog()).perform(pressBack())

        composeRule.runOnIdle {
            assertEquals(0, confirmCalls)
            assertEquals(1, dismissCalls)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_title)).assertDoesNotExist()
    }

    private fun assertConfirmation(dark: Boolean) {
        render(dark)
        val stage = if (dark) "dark" else "light"
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_message)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmCalls) }
        capture("$stage-warning")
        action(CoreCommonR.string.action_cancel).performNativeClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmCalls)
            assertEquals(1, dismissCalls)
            visible.value = true
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        action(CoreCommonR.string.auth_storage_compatibility_continue).performNativeClick()
        composeRule.runOnIdle { assertEquals(1, confirmCalls) }
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_title)).assertDoesNotExist()
    }

    private fun render(dark: Boolean) {
        contentInstalled = true
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = UiStyle.Clean,
                animateColors = false
            ) {
                if (visible.value) {
                    SettingsConfigImportStorageWarning(
                        onConfirm = {
                            confirmCalls++
                            visible.value = false
                        },
                        onDismiss = {
                            dismissCalls++
                            visible.value = false
                        }
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun action(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun capture(stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank) ?: return
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        val holdMillis = arguments.getString("captureHoldMillis")?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
        if (holdMillis > 0L) {
            Thread.sleep(holdMillis)
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            val file = File(directory, "$prefix-config-import-$stage.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("ConfigImportWarning", "capture=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
