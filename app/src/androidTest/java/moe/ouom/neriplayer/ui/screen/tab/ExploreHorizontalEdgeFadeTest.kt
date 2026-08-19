package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreHorizontalEdgeFadeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun endFadeRevealsBackgroundInsteadOfPaintingThemeColor() {
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(width = 240.dp, height = 48.dp)
                    .background(BackgroundColor)
                    .testTag(BackgroundTag)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .exploreHorizontalEdgeFade(
                            showStartFade = false,
                            showEndFade = true,
                            fadeWidth = 48.dp
                        )
                        .background(ContentColor)
                )
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(BackgroundTag).captureToImage().toPixelMap()
        val sampleY = image.height / 2

        assertColorClose(ContentColor, image[image.width / 2, sampleY])
        assertColorClose(BackgroundColor, image[image.width - 2, sampleY])
    }

    private fun assertColorClose(expected: Color, actual: Color) {
        assertTrue(
            "Expected $expected but was $actual",
            abs(expected.red - actual.red) <= ColorTolerance &&
                abs(expected.green - actual.green) <= ColorTolerance &&
                abs(expected.blue - actual.blue) <= ColorTolerance
        )
    }

    private companion object {
        const val BackgroundTag = "explore_edge_fade_background"
        const val ColorTolerance = 0.04f
        val BackgroundColor = Color(0xFF8E2842)
        val ContentColor = Color(0xFF185FA5)
    }
}
