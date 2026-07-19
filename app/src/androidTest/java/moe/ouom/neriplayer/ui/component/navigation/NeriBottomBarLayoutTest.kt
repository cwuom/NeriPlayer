package moe.ouom.neriplayer.ui.component.navigation

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NeriBottomBarLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun bottomBarKeepsRectangularTopCornersForMiniPlayerConnection() {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(background = Color.Blue)
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp, 96.dp)
                        .background(Color.Red)
                ) {
                    NeriBottomBar(
                        items = listOf(Destinations.Home to Icons.Outlined.Home),
                        currentDestination = null,
                        onItemSelected = {},
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(BottomBarTag)
                    )
                }
            }
        }

        val image = composeRule.onNodeWithTag(BottomBarTag).captureToImage()
        val edgeOffset = (image.height / 48).coerceAtLeast(2)
        val topCorner = image.toPixelMap()[edgeOffset, edgeOffset]

        assertTrue(
            "Bottom bar top corner no longer connects to the MiniPlayer: $topCorner",
            topCorner.blue > 0.8f && topCorner.red < 0.2f
        )
    }

    @Test
    fun customBackgroundStaysVisibleWhenBlurIsUnavailable() {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(240.dp, 96.dp)
                        .background(Color.Red)
                        .testTag(CustomBackgroundRootTag)
                ) {
                    NeriBottomBar(
                        items = emptyList(),
                        currentDestination = null,
                        onItemSelected = {},
                        modifier = Modifier.fillMaxSize(),
                        selectAlpha = 0f
                    )
                }
            }
        }

        val image = composeRule.onNodeWithTag(CustomBackgroundRootTag).captureToImage()
        val center = image.toPixelMap()[image.width / 2, image.height / 2]

        assertTrue(
            "Bottom bar obscured the custom background without blur: $center",
            center.red > 0.9f && center.green < 0.1f && center.blue < 0.1f
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun bottomBarBlursPageContentBehindIt() {
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = AdvancedGlassController(
                        sdkInt = Build.VERSION.SDK_INT,
                        advancedBlurEnabled = true,
                        enhancedAdvancedBlurEnabled = false,
                        backendReady = true
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp, 96.dp)
                            .testTag(BlurRootTag)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.White)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(contentBackdrop)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                        NeriBottomBar(
                            items = emptyList(),
                            currentDestination = null,
                            onItemSelected = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(BlurRootTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - image.width / 40, image.height / 2]

        assertTrue(
            "Bottom bar did not blur the page content behind it: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f &&
                mixedPixel.green in 0.15f..0.85f &&
                mixedPixel.blue in 0.15f..0.85f
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun bottomBarBlursLazyContentInsideNavHost() {
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            val navController = rememberNavController()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = AdvancedGlassController(
                        sdkInt = Build.VERSION.SDK_INT,
                        advancedBlurEnabled = true,
                        enhancedAdvancedBlurEnabled = true,
                        backendReady = true,
                        enhancedAdvancedBlurRadiusDp = 48f
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp, 220.dp)
                            .testTag(NavHostBlurRootTag)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.White)
                        )
                        Scaffold(
                            containerColor = Color.Transparent,
                            bottomBar = {
                                NeriBottomBar(
                                    items = emptyList(),
                                    currentDestination = null,
                                    onItemSelected = {},
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(NavHostBottomBarTag)
                                )
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = innerPadding.calculateTopPadding())
                                    .captureAdvancedGlassBackdrop(contentBackdrop)
                            ) {
                                NavHost(
                                    navController = navController,
                                    startDestination = TestRoute,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    composable(TestRoute) {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(List(8) { it }) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(48.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(8.dp)
                                                            .fillMaxHeight()
                                                            .align(Alignment.Center)
                                                            .background(Color.Black)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val bottomBarBounds = composeRule
            .onNodeWithTag(NavHostBottomBarTag)
            .fetchSemanticsNode()
            .boundsInRoot
        val image = composeRule.onNodeWithTag(NavHostBlurRootTag).captureToImage()
        val sampleX = image.width / 2
        val sampleY = (bottomBarBounds.top + bottomBarBounds.height / 3f)
            .roundToInt()
            .coerceIn(0, image.height - 1)
        val mixedPixel = image.toPixelMap()[sampleX, sampleY]

        assertTrue(
            "Bottom bar did not blur NavHost content behind it: $mixedPixel",
            mixedPixel.red in 0.35f..0.97f &&
                mixedPixel.green in 0.35f..0.97f &&
                mixedPixel.blue in 0.35f..0.97f
        )
    }

    private companion object {
        const val BottomBarTag = "bottom_bar"
        const val CustomBackgroundRootTag = "custom_background_root"
        const val BlurRootTag = "bottom_bar_blur_root"
        const val NavHostBlurRootTag = "nav_host_blur_root"
        const val NavHostBottomBarTag = "nav_host_bottom_bar"
        const val TestRoute = "test"
    }
}
