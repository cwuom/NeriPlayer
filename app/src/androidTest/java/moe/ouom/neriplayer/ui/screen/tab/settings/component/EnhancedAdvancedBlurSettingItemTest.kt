package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MIN_SDK
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnhancedAdvancedBlurSettingItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun itemAppearsWithParentAndRestoresSavedChildChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.settings_enhanced_advanced_blur)
        val radiusTitle = context.getString(R.string.settings_enhanced_advanced_blur_radius)
        val parentEnabled = mutableStateOf(false)
        val childEnabled = mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                EnhancedAdvancedBlurSettingItem(
                    sdkInt = ADVANCED_GLASS_MIN_SDK,
                    advancedBlurEnabled = parentEnabled.value,
                    enhancedAdvancedBlurEnabled = childEnabled.value,
                    onEnhancedAdvancedBlurEnabledChange = { childEnabled.value = it }
                )
            }
        }

        composeRule.onAllNodesWithText(title).assertCountEquals(0)

        composeRule.runOnUiThread { parentEnabled.value = true }
        composeRule.onAllNodesWithText(title).assertCountEquals(1)
        composeRule.onAllNodesWithText(radiusTitle).assertCountEquals(1)
        composeRule.onNodeWithText(title).performClick()
        composeRule.onAllNodesWithText(radiusTitle).assertCountEquals(0)

        composeRule.runOnIdle {
            assertFalse("恢复显示后应沿用已保存值并允许切换", childEnabled.value)
        }
    }

    @Test
    fun itemStaysHiddenBelowAndroid13() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.settings_enhanced_advanced_blur)

        composeRule.setContent {
            MaterialTheme {
                EnhancedAdvancedBlurSettingItem(
                    sdkInt = ADVANCED_GLASS_MIN_SDK - 1,
                    advancedBlurEnabled = true,
                    enhancedAdvancedBlurEnabled = true,
                    onEnhancedAdvancedBlurEnabledChange = {}
                )
            }
        }

        composeRule.onAllNodesWithText(title).assertCountEquals(0)
    }

    @Test
    fun enablingItemShowsPersonalizationBackgroundHint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.settings_enhanced_advanced_blur)
        val hint = context.getString(R.string.settings_enhanced_advanced_blur_background_hint)
        val confirm = context.getString(R.string.action_ok)
        val childEnabled = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                EnhancedAdvancedBlurSettingItem(
                    sdkInt = ADVANCED_GLASS_MIN_SDK,
                    advancedBlurEnabled = true,
                    enhancedAdvancedBlurEnabled = childEnabled.value,
                    onEnhancedAdvancedBlurEnabledChange = { childEnabled.value = it }
                )
            }
        }

        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText(hint).assertExists()
        composeRule.runOnIdle {
            assertTrue("提示弹窗不应阻止开关启用", childEnabled.value)
        }

        composeRule.onNodeWithText(confirm).performClick()
        composeRule.onAllNodesWithText(hint).assertCountEquals(0)
    }
}
