package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreGlassPillRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun optionalBorderAppearsAndDisappearsWithoutChangingThePillContent() {
        var border by mutableStateOf<BorderStroke?>(null)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(120.dp, 64.dp).background(Color.Black).testTag("pillFixture")) {
                    ExploreGlassPillSurface(
                        fallbackColor = Color.Black,
                        tintColor = Color.Black,
                        contentColor = Color.White,
                        onClick = {},
                        border = border
                    ) {
                        Box(Modifier.size(80.dp, 32.dp))
                    }
                }
            }
        }

        assertEquals(0, redBorderPixelCount())
        composeRule.runOnIdle { border = BorderStroke(4.dp, Color.Red) }
        assertTrue("the requested red border must be drawn", redBorderPixelCount() > 0)
        composeRule.runOnIdle { border = null }
        assertEquals(0, redBorderPixelCount())
    }

    private fun redBorderPixelCount(): Int {
        val pixels = composeRule.onNodeWithTag("pillFixture").captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.8f && color.green < 0.2f && color.blue < 0.2f) count++
            }
        }
        return count
    }
}
