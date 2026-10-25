package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsAccessMode
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorOsConfigurationContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun materialRequiresExplicitActionAndDispatchesBothSelectedBackends() {
        assertExplicitActions(UiStyle.Material)
    }

    @Test
    fun cleanRequiresExplicitActionAndDispatchesBothSelectedBackends() {
        assertExplicitActions(UiStyle.Clean)
    }

    @Test
    fun materialDiscardRequiresConfirmationAndCancellationDoesNothing() {
        assertConfirmedDiscard(UiStyle.Material)
    }

    @Test
    fun cleanDiscardRequiresConfirmationAndCancellationDoesNothing() {
        assertConfirmedDiscard(UiStyle.Clean)
    }

    @Test
    fun discardWithoutARecoveryRecordIsDisabled() {
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false, seedColorHex = "#6750A4", uiStyle = UiStyle.Material) {
                ColorOsConfigurationContent(
                    mode = ColorOsAccessMode.ROOT,
                    busy = false,
                    result = null,
                    restoreAvailable = false,
                    onModeChanged = {},
                    onExecute = { error("operation without a recovery record") },
                )
            }
        }
        composeRule.onNodeWithText("丢弃恢复记录").assertIsNotEnabled()
    }

    @Test
    fun inFlightOperationDisablesWritesAndBackendChanges() {
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false, seedColorHex = "#6750A4", uiStyle = UiStyle.Clean) {
                ColorOsConfigurationContent(
                    mode = ColorOsAccessMode.SHIZUKU,
                    busy = true,
                    result = null,
                    restoreAvailable = true,
                    onModeChanged = { error("backend change while busy") },
                    onExecute = { error("operation while busy") },
                )
            }
        }
        listOf("Root", "Shizuku", "应用配置", "恢复原策略", "检查配置", "丢弃恢复记录").forEach {
            composeRule.onNodeWithText(it).assertIsNotEnabled()
        }
    }

    private fun assertConfirmedDiscard(style: UiStyle) {
        val requests = mutableListOf<ColorOsConfigurationAction>()
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false, seedColorHex = "#6750A4", uiStyle = style) {
                ColorOsConfigurationContent(
                    mode = ColorOsAccessMode.SHIZUKU,
                    busy = false,
                    result = null,
                    restoreAvailable = true,
                    onModeChanged = {},
                    onExecute = { requests += it },
                )
            }
        }
        composeRule.onNodeWithText("丢弃恢复记录").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(requests.isEmpty()) }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertTrue(requests.isEmpty()) }
        composeRule.onNodeWithText("丢弃恢复记录").performScrollTo().performClick()
        composeRule.onNodeWithText("确认丢弃").performClick()
        composeRule.runOnIdle { assertEquals(listOf(ColorOsConfigurationAction.DISCARD), requests) }
    }

    private fun assertExplicitActions(style: UiStyle) {
        val requests = mutableListOf<Pair<ColorOsAccessMode, ColorOsConfigurationAction>>()
        composeRule.setContent {
            var mode by remember { mutableStateOf(ColorOsAccessMode.SHIZUKU) }
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false, seedColorHex = "#6750A4", uiStyle = style) {
                ColorOsConfigurationContent(
                    mode = mode,
                    busy = false,
                    result = null,
                    restoreAvailable = true,
                    onModeChanged = { mode = it },
                    onExecute = { requests += mode to it },
                )
            }
        }
        composeRule.runOnIdle { assertTrue(requests.isEmpty()) }
        composeRule.onNodeWithText("Root").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(requests.isEmpty()) }
        composeRule.onNodeWithText("应用配置").performScrollTo().performClick()
        composeRule.onNodeWithText("Shizuku").performScrollTo().performClick()
        composeRule.onNodeWithText("检查配置").performScrollTo().performClick()
        composeRule.onNodeWithText("恢复原策略").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    ColorOsAccessMode.ROOT to ColorOsConfigurationAction.APPLY,
                    ColorOsAccessMode.SHIZUKU to ColorOsConfigurationAction.CHECK,
                    ColorOsAccessMode.SHIZUKU to ColorOsConfigurationAction.RESTORE,
                ),
                requests,
            )
        }
    }
}
