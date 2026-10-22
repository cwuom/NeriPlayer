package moe.ouom.neriplayer.ui.component.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanSettingsSwitchContrastTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checkedThumbStaysDistinctFromTrackInDarkTheme() = checkCheckedThumbContrast(dark = true)

    @Test
    fun checkedThumbStaysDistinctFromTrackInLightTheme() = checkCheckedThumbContrast(dark = false)

    private fun checkCheckedThumbContrast(dark: Boolean) {
        val seeds = listOf("3366FF", "E8402C", "808080")
        composeRule.setContent {
            Column {
                seeds.forEach { seed ->
                    NeriTheme(
                        followSystemDark = false,
                        forceDark = dark,
                        dynamicColor = false,
                        seedColorHex = seed,
                        uiStyle = UiStyle.Clean,
                        animateColors = false
                    ) {
                        var checked by remember { mutableStateOf(true) }
                        Box(
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(16.dp)
                        ) {
                            MiuixSettingsSwitch(
                                checked = checked,
                                onCheckedChange = { checked = it },
                                modifier = Modifier.testTag(seed)
                            )
                        }
                    }
                }
            }
        }

        seeds.forEach { seed ->
            val toggle = composeRule.onNodeWithTag(seed)
            toggle.assertIsOn().performClick().assertIsOff().performClick().assertIsOn()
            composeRule.waitForIdle()
            val pixels = toggle.captureToImage().toPixelMap()
            val track = pixels[(pixels.width * 0.25f).toInt(), pixels.height / 2].luminance()
            val thumb = pixels[(pixels.width * 0.75f).toInt(), pixels.height / 2].luminance()
            val contrast = (maxOf(track, thumb) + 0.05f) / (minOf(track, thumb) + 0.05f)
            assertTrue(
                "checked thumb must have at least 3:1 contrast for seed=$seed dark=$dark, actual=$contrast",
                contrast >= 3f
            )
        }
    }
}
