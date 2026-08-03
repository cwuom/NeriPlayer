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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    @Test
    fun recreatedSceneUsesLiveHeightForItsFirstExitFrame() {
        lateinit var sceneGeneration: MutableIntState
        var sceneTopPx = 0f
        var sceneHeightPx = 0
        val contentTopPositions = mutableListOf<Float>()

        composeRule.setContent {
            sceneGeneration = remember { mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .size(200.dp, 100.dp)
                    .onGloballyPositioned { coordinates ->
                        sceneTopPx = coordinates.positionInRoot().y
                        sceneHeightPx = coordinates.size.height
                    }
                    .testTag(SceneRootTag)
            ) {
                key(sceneGeneration.intValue) {
                    AdvancedGlassSceneLayer(
                        controller = AdvancedGlassController(
                            sdkInt = Build.VERSION.SDK_INT,
                            advancedBlurEnabled = true,
                            enhancedAdvancedBlurEnabled = true,
                            backendReady = true
                        ),
                        motion = AdvancedGlassSceneMotion(
                            revealTopFraction = 1f,
                            contentTranslationYFraction = 1f,
                            contentScale = 1f
                        ),
                        background = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Gray)
                            )
                        },
                        content = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { coordinates ->
                                        contentTopPositions += coordinates
                                            .positionInRoot()
                                            .y
                                    }
                                    .testTag(RecreatedContentTag)
                                    .background(Color.Red)
                            )
                        }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            contentTopPositions.clear()
            sceneGeneration.intValue++
        }
        composeRule.waitForIdle()

        assertTrue(
            "重建场景没有记录内容位置",
            contentTopPositions.isNotEmpty()
        )
        assertTrue(
            "重建场景退出首帧仍覆盖根列表: " +
                "top=${contentTopPositions.first()} sceneTop=$sceneTopPx " +
                "sceneHeight=$sceneHeightPx",
            contentTopPositions.first() >=
                sceneTopPx + sceneHeightPx - PositionTolerancePx
        )
        composeRule.onNodeWithTag(RecreatedContentTag).assertExists()
    }

    @Test
    fun exitingSceneClipsContentToItsRevealBoundary() {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(200.dp, 100.dp)
                        .background(Color.Blue)
                        .testTag(SceneRootTag)
                ) {
                    AdvancedGlassSceneLayer(
                        controller = AdvancedGlassController(
                            sdkInt = Build.VERSION.SDK_INT,
                            advancedBlurEnabled = true,
                            enhancedAdvancedBlurEnabled = true,
                            backendReady = true
                        ),
                        motion = AdvancedGlassSceneMotion(
                            revealTopFraction = 0.5f,
                            contentTranslationYFraction = 0.5f,
                            contentScale = 1f
                        ),
                        background = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Gray)
                            )
                        },
                        content = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = -size.height * 0.5f
                                    }
                                    .background(Color.Red)
                            )
                        }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(SceneRootTag).captureToImage().toPixelMap()
        val topPixel = image[image.width / 2, image.height / 4]
        val bottomPixel = image[image.width / 2, image.height * 3 / 4]

        assertTrue("退出场景内容越过揭示边界: $topPixel", topPixel.blue > topPixel.red)
        assertTrue("退出场景内容没有保留在揭示边界下方: $bottomPixel", bottomPixel.red > bottomPixel.blue)
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
        const val SceneRootTag = "advanced_glass_scene_root"
        const val RecreatedContentTag = "advanced_glass_scene_recreated_content"
        const val PositionTolerancePx = 1f
    }
}
