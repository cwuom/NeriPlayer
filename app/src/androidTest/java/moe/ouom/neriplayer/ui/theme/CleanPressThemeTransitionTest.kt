package moe.ouom.neriplayer.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanPressThemeTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun changingTheThemeKeepsAnOngoingPressAnimation() {
        val dark = mutableStateOf(true)
        val interactions = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark.value,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = UiStyle.Clean,
                neutralSurfaces = false
            ) {
                Box(Modifier.size(100.dp).background(Color.Black)) {
                    Box(
                        Modifier.size(100.dp).testTag("pressedSurface")
                            .clickable(interactionSource = interactions, indication = LocalIndication.current) {}
                            .background(Color.Green)
                    )
                }
            }
        }
        composeRule.runOnIdle { assertTrue(interactions.tryEmit(press)) }
        composeRule.mainClock.advanceTimeBy(350)
        assertPressedInset()

        composeRule.runOnIdle { dark.value = false }
        composeRule.mainClock.advanceTimeBy(100)
        assertPressedInset()

        composeRule.runOnIdle { assertTrue(interactions.tryEmit(PressInteraction.Release(press))) }
        composeRule.mainClock.advanceTimeBy(1_000)
        val pixels = composeRule.onNodeWithTag("pressedSurface").captureToImage().toPixelMap()
        assertTrue("release must still restore the full surface", pixels[1, pixels.height / 2].green > 0.8f)
    }

    private fun assertPressedInset() {
        val pixels = composeRule.onNodeWithTag("pressedSurface").captureToImage().toPixelMap()
        assertTrue("the pressed surface must retain its inset while colors animate", pixels[1, pixels.height / 2].green < 0.2f)
        assertTrue("the pressed content must remain visible", pixels[pixels.width / 2, pixels.height / 2].green > 0.8f)
    }
}
