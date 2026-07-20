package moe.ouom.neriplayer.ui.effect.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvancedGlassSceneLayerIsolationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun foregroundSceneDoesNotReceiveBackgroundSceneBlurMask() {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(200.dp, 100.dp)
                        .testTag(RootTag)
                ) {
                    TestScene(maskAlignment = Alignment.CenterStart)
                    TestScene(
                        maskAlignment = Alignment.CenterEnd,
                        modifier = Modifier.offset(y = 50.dp)
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()
        val stripeWidth = pixels.width / StripeCount
        val leftBlackStripe = stripeWidth * 2 + stripeWidth / 2
        val rightBlackStripe = stripeWidth * 14 + stripeWidth / 2
        val sourceSampleY = pixels.height / 4
        val foregroundSampleY = pixels.height * 3 / 4
        val sourceLeftPixel = pixels[leftBlackStripe, sourceSampleY]
        val foregroundLeftPixel = pixels[leftBlackStripe, foregroundSampleY]
        val foregroundRightPixel = pixels[rightBlackStripe, foregroundSampleY]

        assertTrue(
            "visible background scene lost its blur while the drawer was opening: " +
                sourceLeftPixel,
            sourceLeftPixel.red in 0.15f..0.85f
        )
        assertTrue(
            "background scene blur leaked into the foreground scene: $foregroundLeftPixel",
            foregroundLeftPixel.red < 0.1f
        )
        assertTrue(
            "foreground scene did not render its own blur mask: $foregroundRightPixel",
            foregroundRightPixel.red in 0.15f..0.85f
        )
    }

    @Composable
    private fun TestScene(
        maskAlignment: Alignment,
        modifier: Modifier = Modifier
    ) {
        AdvancedGlassSceneLayer(
            controller = AdvancedGlassController(
                sdkInt = Build.VERSION.SDK_INT,
                advancedBlurEnabled = true,
                enhancedAdvancedBlurEnabled = true,
                backendReady = true
            ),
            modifier = modifier,
            background = {
                Row(Modifier.fillMaxSize()) {
                    repeat(StripeCount) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (index % 2 == 0) Color.Black else Color.White)
                        )
                    }
                }
            },
            content = {
                AdvancedGlassSurface(
                    role = AdvancedGlassRole.SettingsSection,
                    modifier = Modifier
                        .size(80.dp)
                        .align(maskAlignment),
                    tintColor = Color.Transparent
                ) {}
            }
        )
    }

    private companion object {
        const val RootTag = "advanced_glass_scene_layer_isolation_root"
        const val StripeCount = 20
    }
}
