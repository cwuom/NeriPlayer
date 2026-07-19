package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.collect
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AdvancedGlassSurfaceRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun regionMaskShaderAssetMatchesBackendContract() {
        val assetManager = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .assets
        val source = AdvancedGlassShaderSource(assetManager).load()

        assertTrue(source.contains("uniform shader child;"))
        assertTrue(
            source.contains(
                "uniform float4 regionBounds[$ADVANCED_GLASS_MAX_REGIONS];"
            )
        )
        assertTrue(
            source.contains(
                "uniform float4 cornerRadii[$ADVANCED_GLASS_MAX_REGIONS];"
            )
        )
        assertTrue(
            source.contains(
                "for (int index = 0; index < $ADVANCED_GLASS_MAX_REGIONS; index++)"
            )
        )
        assertTrue(source.contains("child.eval(position)"))
        assertNotNull(RuntimeShader(source))
    }

    @Test
    fun settingsGlassKeepsForegroundPixelsSharp() {
        composeRule.setContent {
            GlassTestHost {
                Box(modifier = Modifier.size(160.dp, 120.dp)) {
                    AdvancedGlassSurface(
                        role = AdvancedGlassRole.SettingsSection,
                        modifier = Modifier
                            .size(120.dp, 88.dp)
                            .align(Alignment.Center)
                            .testTag(SettingsGlassTag),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center)
                                .background(Color.Black)
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(SettingsGlassTag).captureToImage()
        val pixels = image.toPixelMap()
        val center = pixels[image.width / 2, image.height / 2]

        assertTrue(
            "foreground was blurred or tinted: $center",
            center.red < 0.05f && center.green < 0.05f && center.blue < 0.05f
        )
    }

    @Test
    fun bottomNavigationSamplesContentAtItsActualWindowPosition() {
        composeRule.setContent {
            MaterialTheme {
                val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                val contentBackdrop = rememberAdvancedGlassBackdrop()
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(modifier = Modifier.size(160.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.White)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .captureAdvancedGlassBackdrop(contentBackdrop)
                                .background(Color.Red)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Blue)
                            )
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.BottomNavigation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .align(Alignment.BottomCenter)
                                .testTag(BottomGlassTag),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(BottomGlassTag).captureToImage()
        val pixel = image.toPixelMap()[image.width / 2, image.height / 2]

        assertTrue(
            "bottom glass did not transmit the blue content behind it: $pixel",
            pixel.blue > pixel.red + 0.15f
        )
    }

    @Test
    fun bottomNavigationBlursContentAcrossBackdropBoundary() {
        composeRule.setContent {
            MaterialTheme {
                val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                val contentBackdrop = rememberAdvancedGlassBackdrop()
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(ContentBlurRootTag)
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
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.BottomNavigation,
                            modifier = Modifier
                                .size(160.dp, 80.dp)
                                .align(Alignment.Center),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(ContentBlurRootTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - 6, image.height / 2]

        assertTrue(
            "content behind bottom navigation stayed sharp: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f &&
                mixedPixel.green in 0.15f..0.85f &&
                mixedPixel.blue in 0.15f..0.85f
        )
    }

    @Test
    fun glassBlurMixesPixelsAcrossBackdropBoundary() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(BlurRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.SettingsSection,
                            modifier = Modifier
                                .size(160.dp, 80.dp)
                                .align(Alignment.Center)
                                .testTag(BlurMixTag),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull(
                "background producer never received a selective blur effect",
                capturedBackdrop.renderEffect
            )
        }
        val image = composeRule.onNodeWithTag(BlurRootTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - 6, image.height / 2]

        assertTrue(
            "backdrop boundary stayed sharp instead of being blurred: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f &&
                mixedPixel.green in 0.15f..0.85f &&
                mixedPixel.blue in 0.15f..0.85f
        )
    }

    @Test
    fun selectiveRenderEffectMixesDirectContent() {
        composeRule.setContent {
            val assetManager = LocalContext.current.applicationContext.assets
            val shaderSource = remember(assetManager) {
                AdvancedGlassShaderSource(assetManager)
            }
            val density = LocalDensity.current
            val widthPx = with(density) { 160.dp.toPx() }
            val heightPx = with(density) { 80.dp.toPx() }
            val radiusPx = with(density) { 36.dp.toPx() }
            val effect = remember(shaderSource, widthPx, heightPx, radiusPx) {
                createAdvancedGlassRenderEffect(
                    shaderSource = shaderSource,
                    sdkInt = Build.VERSION.SDK_INT,
                    radiusPx = radiusPx,
                    regions = listOf(
                        AdvancedGlassRenderRegion(
                            left = 0f,
                            top = 0f,
                            right = widthPx,
                            bottom = heightPx,
                            cornerRadiiPx = AdvancedGlassCornerRadii.Zero
                        )
                    )
                )
            }
            Row(
                modifier = Modifier
                    .size(160.dp, 80.dp)
                    .graphicsLayer { renderEffect = effect }
                    .testTag(DirectSelectiveBlurTag)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(DirectSelectiveBlurTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - 6, image.height / 2]

        assertTrue(
            "selective RenderEffect did not blur direct content: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f
        )
    }

    @Test
    fun topOnlyCornerRadiiKeepTopCornerOutsideBlurMask() {
        composeRule.setContent {
            val assetManager = LocalContext.current.applicationContext.assets
            val shaderSource = remember(assetManager) {
                AdvancedGlassShaderSource(assetManager)
            }
            val density = LocalDensity.current
            val widthPx = with(density) { 160.dp.toPx() }
            val heightPx = with(density) { 80.dp.toPx() }
            val blurRadiusPx = with(density) { 20.dp.toPx() }
            val cornerRadiusPx = with(density) { 40.dp.toPx() }
            val effect = remember(
                shaderSource,
                widthPx,
                heightPx,
                blurRadiusPx,
                cornerRadiusPx
            ) {
                createAdvancedGlassRenderEffect(
                    shaderSource = shaderSource,
                    sdkInt = Build.VERSION.SDK_INT,
                    radiusPx = blurRadiusPx,
                    regions = listOf(
                        AdvancedGlassRenderRegion(
                            left = 0f,
                            top = 0f,
                            right = widthPx,
                            bottom = heightPx,
                            cornerRadiiPx = AdvancedGlassCornerRadii(
                                topLeft = cornerRadiusPx,
                                topRight = cornerRadiusPx,
                                bottomRight = 0f,
                                bottomLeft = 0f
                            )
                        )
                    )
                )
            }
            Row(
                modifier = Modifier
                    .size(160.dp, 80.dp)
                    .graphicsLayer { renderEffect = effect }
                    .testTag(TopOnlyCornersTag)
            ) {
                repeat(16) { stripeIndex ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (stripeIndex % 2 == 0) Color.Black else Color.White)
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(TopOnlyCornersTag).captureToImage()
        val pixels = image.toPixelMap()
        val sampleX = image.width / 10
        val cornerOffsetY = image.height / 20
        val topCorner = pixels[sampleX, cornerOffsetY]
        val bottomCorner = pixels[sampleX, image.height - cornerOffsetY - 1]

        assertTrue(
            "rounded top corner leaked blur outside its mask: $topCorner",
            topCorner.red > 0.9f
        )
        assertTrue(
            "square bottom corner was incorrectly rounded or left unblurred: $bottomCorner",
            bottomCorner.red in 0.15f..0.85f
        )
    }

    @Test
    fun leavingScreenRemovesRegisteredBlurMaskImmediately() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var activeOwnerState: MutableState<LifecycleOwner?>
        val inactiveOwner = TestLifecycleOwner()
        composeRule.setContent {
            val currentOwner = LocalLifecycleOwner.current
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            activeOwnerState = remember { mutableStateOf(currentOwner) }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwnerState.value?.let { setOf(it) }
                ) {
                    Box(modifier = Modifier.size(160.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.Blue)
                        )
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.ScreenTopTab,
                            modifier = Modifier
                                .size(120.dp, 64.dp)
                                .align(Alignment.Center)
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull("active screen never registered its blur mask", capturedBackdrop.renderEffect)
            activeOwnerState.value = inactiveOwner
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNull("leaving screen kept its old blur mask", capturedBackdrop.renderEffect)
        }
    }

    @Test
    fun switchingActiveScreenKeepsBackdropEffectDuringMaskHandoff() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var activeOwnerState: MutableState<LifecycleOwner?>
        val firstOwner = TestLifecycleOwner()
        val secondOwner = TestLifecycleOwner()
        val effectAvailability = mutableListOf<Boolean>()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            activeOwnerState = remember { mutableStateOf(firstOwner) }
            LaunchedEffect(backgroundBackdrop) {
                snapshotFlow { backgroundBackdrop.renderEffect != null }
                    .collect(effectAvailability::add)
            }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwnerState.value?.let { setOf(it) }
                ) {
                    Box(modifier = Modifier.size(160.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.Blue)
                        )
                        CompositionLocalProvider(LocalLifecycleOwner provides firstOwner) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.ScreenTopTab,
                                modifier = Modifier
                                    .size(120.dp, 64.dp)
                                    .align(Alignment.Center)
                            ) {}
                        }
                        CompositionLocalProvider(LocalLifecycleOwner provides secondOwner) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.ScreenTopTab,
                                modifier = Modifier
                                    .size(120.dp, 64.dp)
                                    .align(Alignment.Center)
                            ) {}
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull("first screen never installed its blur mask", capturedBackdrop.renderEffect)
            effectAvailability.clear()
            activeOwnerState.value = secondOwner
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull("new screen did not install its blur mask", capturedBackdrop.renderEffect)
            assertTrue(
                "backdrop effect disappeared during navigation handoff: $effectAvailability",
                effectAvailability.none { available -> !available }
            )
        }
    }

    @Test
    fun navHostTabSwitchKeepsGlassEffectForEveryVisibleEntryFrame() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var navigateToSecondTab: () -> Unit
        val effectAvailability = mutableListOf<Boolean>()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            val navController = rememberNavController()
            val visibleEntries = navController.visibleEntries.collectAsState().value
            capturedBackdrop = backgroundBackdrop
            navigateToSecondTab = { navController.navigate(SecondTabRoute) }
            LaunchedEffect(backgroundBackdrop) {
                snapshotFlow { backgroundBackdrop.renderEffect != null }
                    .collect(effectAvailability::add)
            }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = visibleEntries.toSet()
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 100.dp)
                            .testTag(NavHostSceneHandoffRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(20) { stripeIndex ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (stripeIndex % 2 == 0) Color.Black else Color.White
                                        )
                                )
                            }
                        }
                        AdvancedGlassNavigationHandoff(enabled = visibleEntries.size > 1) {
                            NavHost(
                                navController = navController,
                                startDestination = FirstTabRoute,
                                modifier = Modifier.fillMaxSize()
                            ) {
                            composable(
                                route = FirstTabRoute,
                                exitTransition = {
                                    slideOutHorizontally(
                                        animationSpec = advancedGlassMainTabNavigationSpringSpec()
                                    ) { fullWidth -> -fullWidth }
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag(FirstTabContentTag)
                                ) {
                                    AdvancedGlassSurface(
                                        role = AdvancedGlassRole.SettingsSection,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .align(Alignment.CenterStart),
                                        tintColor = Color.Transparent
                                    ) {}
                                }
                            }
                            composable(
                                route = SecondTabRoute,
                                enterTransition = {
                                    slideInHorizontally(
                                        animationSpec = advancedGlassMainTabNavigationSpringSpec()
                                    ) { fullWidth -> fullWidth }
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag(SecondTabContentTag)
                                ) {
                                    AdvancedGlassSurface(
                                        role = AdvancedGlassRole.SettingsSection,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .align(Alignment.CenterEnd),
                                        tintColor = Color.Transparent
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        composeRule.waitForIdle()
        assertSceneMaskState(
            composeRule.onNodeWithTag(NavHostSceneHandoffRootTag).captureToImage(),
            leftBlurred = true
        )
        composeRule.runOnIdle {
            assertNotNull("首个 Tab 没有安装模糊蒙版", capturedBackdrop.renderEffect)
            effectAvailability.clear()
            composeRule.mainClock.autoAdvance = false
            navigateToSecondTab()
        }

        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                assertNotNull("Tab 切换第 $it 帧丢失模糊效果", capturedBackdrop.renderEffect)
            }
        }
        val firstBounds = composeRule.onNodeWithTag(FirstTabContentTag)
            .fetchSemanticsNode().boundsInRoot
        val secondBounds = composeRule.onNodeWithTag(SecondTabContentTag)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Tab 页面在滑动过程中发生重叠: first=$firstBounds second=$secondBounds",
            firstBounds.right <= secondBounds.left + 1f
        )
        repeat(6) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                assertNotNull(
                    "Tab 切换第 ${frame + 6} 帧丢失模糊效果",
                    capturedBackdrop.renderEffect
                )
            }
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertSceneMaskState(
            composeRule.onNodeWithTag(NavHostSceneHandoffRootTag).captureToImage(),
            leftBlurred = false
        )
        composeRule.runOnIdle {
            assertTrue(
                "Tab 切换期间出现空白模糊帧: $effectAvailability",
                effectAvailability.none { available -> !available }
            )
        }
    }

    @Test
    fun switchingAnimatedSceneDropsOldMaskWithoutBlankFrame() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var activeSceneState: MutableState<Int>
        val effectAvailability = mutableListOf<Boolean>()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            activeSceneState = remember { mutableStateOf(0) }
            LaunchedEffect(backgroundBackdrop) {
                snapshotFlow { backgroundBackdrop.renderEffect != null }
                    .collect(effectAvailability::add)
            }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 100.dp)
                            .testTag(SceneHandoffRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(20) { stripeIndex ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (stripeIndex % 2 == 0) Color.Black else Color.White
                                        )
                                )
                            }
                        }
                        AdvancedGlassScene(active = activeSceneState.value == 0) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.SettingsSection,
                                modifier = Modifier
                                    .size(80.dp)
                                    .align(Alignment.CenterStart),
                                tintColor = Color.Transparent
                            ) {}
                        }
                        AdvancedGlassScene(active = activeSceneState.value == 1) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.SettingsSection,
                                modifier = Modifier
                                    .size(80.dp)
                                    .align(Alignment.CenterEnd),
                                tintColor = Color.Transparent
                            ) {}
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val firstImage = composeRule.onNodeWithTag(SceneHandoffRootTag).captureToImage()
        assertSceneMaskState(firstImage, leftBlurred = true)

        composeRule.runOnIdle {
            effectAvailability.clear()
            activeSceneState.value = 1
        }
        composeRule.waitForIdle()
        val secondImage = composeRule.onNodeWithTag(SceneHandoffRootTag).captureToImage()
        assertSceneMaskState(secondImage, leftBlurred = false)
        composeRule.runOnIdle {
            assertNotNull("scene handoff removed the backdrop effect", capturedBackdrop.renderEffect)
            assertTrue(
                "scene handoff exposed an empty effect frame: $effectAvailability",
                effectAvailability.none { available -> !available }
            )
        }
    }

    @Test
    fun nestedSceneSwitchesDropParentAndChildMasksWithoutBlankFrame() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var navigationDepth: MutableState<Int>
        val effectAvailability = mutableListOf<Boolean>()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            navigationDepth = remember { mutableStateOf(0) }
            LaunchedEffect(backgroundBackdrop) {
                snapshotFlow { backgroundBackdrop.renderEffect != null }
                    .collect(effectAvailability::add)
            }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp, 140.dp)
                            .testTag(NestedSceneHandoffRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(24) { stripeIndex ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (stripeIndex % 2 == 0) Color.Black else Color.White
                                        )
                                )
                            }
                        }

                        AdvancedGlassScene(active = navigationDepth.value == 0) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.SettingsSection,
                                modifier = Modifier
                                    .size(72.dp, 80.dp)
                                    .align(Alignment.CenterStart),
                                tintColor = Color.Transparent
                            ) {}
                        }
                        AdvancedGlassScene(active = navigationDepth.value >= 1) {
                            AdvancedGlassScene(active = navigationDepth.value == 1) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(72.dp, 80.dp)
                                        .align(Alignment.Center),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                            AdvancedGlassScene(active = navigationDepth.value == 2) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(72.dp, 80.dp)
                                        .align(Alignment.CenterEnd),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        assertNestedSceneMaskState(
            composeRule.onNodeWithTag(NestedSceneHandoffRootTag).captureToImage(),
            activeThird = 0
        )

        composeRule.runOnIdle {
            effectAvailability.clear()
            navigationDepth.value = 1
        }
        composeRule.waitForIdle()
        assertNestedSceneMaskState(
            composeRule.onNodeWithTag(NestedSceneHandoffRootTag).captureToImage(),
            activeThird = 1
        )
        composeRule.runOnIdle {
            assertNotNull("二级场景没有安装模糊蒙版", capturedBackdrop.renderEffect)
            assertTrue(
                "首页切换二级页时出现空白模糊帧: $effectAvailability",
                effectAvailability.none { available -> !available }
            )
            effectAvailability.clear()
            navigationDepth.value = 2
        }
        composeRule.waitForIdle()
        assertNestedSceneMaskState(
            composeRule.onNodeWithTag(NestedSceneHandoffRootTag).captureToImage(),
            activeThird = 2
        )
        composeRule.runOnIdle {
            assertNotNull("三级场景没有安装模糊蒙版", capturedBackdrop.renderEffect)
            assertTrue(
                "二级切换三级页时出现空白模糊帧: $effectAvailability",
                effectAvailability.none { available -> !available }
            )
        }
    }

    @Test
    fun interruptedAnimatedNavigationKeepsSceneMasksAttachedForEveryFrame() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var navigationDepth: MutableState<Int>
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            navigationDepth = remember { mutableStateOf(0) }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp, 100.dp)
                            .testTag(InterruptedSceneHandoffRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(24) { stripeIndex ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (stripeIndex % 2 == 0) Color.Black else Color.White
                                        )
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(contentBackdrop)
                        )
                        AnimatedContent(
                            targetState = navigationDepth.value,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                isolatedAdvancedGlassHorizontalTransition(
                                    forward = targetState > initialState
                                ).using(SizeTransform(clip = true))
                            },
                            label = "interrupted_glass_navigation"
                        ) { depth ->
                            AdvancedGlassNavigationHandoff(enabled = transition.isRunning) {
                                AdvancedGlassScene(active = true) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        if (depth <= 2) {
                                            AdvancedGlassSurface(
                                                role = AdvancedGlassRole.SettingsHeader,
                                                modifier = Modifier
                                                    .size(80.dp, 32.dp)
                                                    .align(Alignment.TopCenter),
                                                tintColor = Color.Transparent
                                            ) {}
                                            AdvancedGlassSurface(
                                                role = AdvancedGlassRole.SettingsSection,
                                                modifier = Modifier
                                                    .size(72.dp, 56.dp)
                                                    .align(
                                                        when (depth) {
                                                            0 -> Alignment.CenterStart
                                                            1 -> Alignment.Center
                                                            else -> Alignment.CenterEnd
                                                        }
                                                    ),
                                                tintColor = Color.Transparent
                                            ) {}
                                        }
                                    }
                                }
                            }
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.BottomNavigation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .align(Alignment.BottomCenter),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull("首页场景没有安装模糊蒙版", capturedBackdrop.renderEffect)
            composeRule.mainClock.autoAdvance = false
            navigationDepth.value = 1
        }

        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                assertNotNull("首页进入二级页时出现空白模糊帧", capturedBackdrop.renderEffect)
            }
            assertSceneBlurPresent(
                composeRule.onNodeWithTag(InterruptedSceneHandoffRootTag).captureToImage(),
                "首页进入二级页时页面遮罩消失"
            )
        }
        composeRule.runOnIdle { navigationDepth.value = 2 }
        repeat(24) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                assertNotNull("快速进入三级页时出现空白模糊帧", capturedBackdrop.renderEffect)
            }
            assertSceneBlurPresent(
                composeRule.onNodeWithTag(InterruptedSceneHandoffRootTag).captureToImage(),
                "快速进入三级页时页面遮罩消失"
            )
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()

        assertNestedSceneMaskState(
            image = composeRule
                .onNodeWithTag(InterruptedSceneHandoffRootTag)
                .captureToImage(),
            activeThird = 2
        )

        composeRule.runOnIdle { navigationDepth.value = 3 }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertSceneBlurAbsent(
            composeRule.onNodeWithTag(InterruptedSceneHandoffRootTag).captureToImage()
        )
    }

    @Test
    fun changingRadiusChangesRenderedBlurStrength() {
        lateinit var radiusState: MutableFloatState
        composeRule.setContent {
            radiusState = remember { mutableFloatStateOf(12f) }
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        enhancedAdvancedBlurRadiusDp = radiusState.floatValue
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(AdjustableBlurRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.SettingsSection,
                            modifier = Modifier
                                .size(160.dp, 80.dp)
                                .align(Alignment.Center),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val lowRadiusImage = composeRule.onNodeWithTag(AdjustableBlurRootTag).captureToImage()
        val sampleX = lowRadiusImage.width / 2 - lowRadiusImage.width / 10
        val sampleY = lowRadiusImage.height / 2
        val lowRadiusPixel = lowRadiusImage.toPixelMap()[sampleX, sampleY]

        composeRule.runOnIdle { radiusState.floatValue = 64f }
        composeRule.waitForIdle()
        val highRadiusPixel = composeRule
            .onNodeWithTag(AdjustableBlurRootTag)
            .captureToImage()
            .toPixelMap()[sampleX, sampleY]

        assertTrue(
            "radius change did not alter blur strength: low=$lowRadiusPixel high=$highRadiusPixel",
            highRadiusPixel.red > lowRadiusPixel.red + 0.1f
        )
    }

    @Test
    fun movingRegionWithStableRadiusReinstallsCurrentMask() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var horizontalOffset: MutableState<Int>
        var initialEffect: androidx.compose.ui.graphics.RenderEffect? = null
        composeRule.setContent {
            horizontalOffset = remember { mutableStateOf(0) }
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(MovingMaskRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.SettingsSection,
                            modifier = Modifier
                                .offset {
                                    IntOffset(horizontalOffset.value.dp.roundToPx(), 0)
                                }
                                .size(80.dp)
                                .align(Alignment.Center),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val initialImage = composeRule.onNodeWithTag(MovingMaskRootTag).captureToImage()
        val sampleX = initialImage.width / 2 - 6
        val sampleY = initialImage.height / 2
        val initialPixel = initialImage.toPixelMap()[sampleX, sampleY]
        composeRule.runOnIdle {
            initialEffect = capturedBackdrop.renderEffect
            assertNotNull("initial region never installed a blur effect", initialEffect)
            horizontalOffset.value = 60
        }
        composeRule.waitForIdle()
        val movedPixel = composeRule
            .onNodeWithTag(MovingMaskRootTag)
            .captureToImage()
            .toPixelMap()[sampleX, sampleY]
        composeRule.runOnIdle {
            assertNotSame(
                "moving a region did not reinstall the updated runtime mask",
                initialEffect,
                capturedBackdrop.renderEffect
            )
        }
        assertTrue(
            "initial runtime mask did not blur its covered boundary: $initialPixel",
            initialPixel.red in 0.15f..0.85f
        )
        assertTrue(
            "moved runtime mask left stale blur at its old position: $movedPixel",
            movedPixel.red < 0.1f
        )
    }

    @Test
    fun enhancedGlassDisablesStretchOverscrollInsideHost() {
        var observedFactory: androidx.compose.foundation.OverscrollFactory? = null
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            AdvancedGlassHost(
                controller = enabledController(),
                backgroundBackdrop = backgroundBackdrop,
                contentBackdrop = contentBackdrop,
                disableStretchOverscroll = true
            ) {
                observedFactory = LocalOverscrollFactory.current
            }
        }

        composeRule.runOnIdle {
            assertNull("进阶模糊下仍启用了拉伸 Overscroll", observedFactory)
        }
    }

    @Test
    fun enhancedGlassPreservesOverscrollWithoutCustomBackground() {
        var parentFactory: androidx.compose.foundation.OverscrollFactory? = null
        var observedFactory: androidx.compose.foundation.OverscrollFactory? = null
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            parentFactory = LocalOverscrollFactory.current
            AdvancedGlassHost(
                controller = enabledController(),
                backgroundBackdrop = backgroundBackdrop,
                contentBackdrop = contentBackdrop
            ) {
                observedFactory = LocalOverscrollFactory.current
            }
        }

        composeRule.runOnIdle {
            assertEquals("无背景图时不应改变 Overscroll", parentFactory, observedFactory)
        }
    }

    @Composable
    private fun GlassTestHost(content: @Composable () -> Unit) {
        val backgroundBackdrop = rememberAdvancedGlassBackdrop()
        val contentBackdrop = rememberAdvancedGlassBackdrop()
        MaterialTheme {
            AdvancedGlassHost(
                controller = enabledController(),
                backgroundBackdrop = backgroundBackdrop,
                contentBackdrop = contentBackdrop
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(160.dp, 120.dp)
                            .captureAdvancedGlassBackdrop(backgroundBackdrop)
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .width(80.dp)
                                    .fillMaxHeight()
                                    .background(Color.Red)
                            )
                            Box(
                                Modifier
                                    .width(80.dp)
                                    .fillMaxHeight()
                                    .background(Color.Blue)
                            )
                        }
                    }
                    content()
                }
            }
        }
    }

    private fun enabledController() = AdvancedGlassController(
        sdkInt = ADVANCED_GLASS_MIN_SDK,
        advancedBlurEnabled = true,
        enhancedAdvancedBlurEnabled = true,
        backendReady = true
    )

    private fun assertSceneMaskState(
        image: androidx.compose.ui.graphics.ImageBitmap,
        leftBlurred: Boolean
    ) {
        val pixels = image.toPixelMap()
        val boundaryOffset = (image.width / 100).coerceAtLeast(2)
        val leftPixel = pixels[image.width / 4 - boundaryOffset, image.height / 2]
        val rightPixel = pixels[image.width * 3 / 4 - boundaryOffset, image.height / 2]
        val blurredPixel = if (leftBlurred) leftPixel else rightPixel
        val sharpPixel = if (leftBlurred) rightPixel else leftPixel

        assertTrue(
            "active scene did not blur its mask: $blurredPixel",
            blurredPixel.red in 0.15f..0.85f
        )
        assertTrue(
            "inactive scene kept its old mask: $sharpPixel",
            sharpPixel.red < 0.05f || sharpPixel.red > 0.95f
        )
    }

    private fun assertNestedSceneMaskState(
        image: androidx.compose.ui.graphics.ImageBitmap,
        activeThird: Int
    ) {
        val pixels = image.toPixelMap()
        val boundaryOffset = (image.width / 120).coerceAtLeast(2)
        val sampleX = listOf(
            image.width / 6 - boundaryOffset,
            image.width / 2 - boundaryOffset,
            image.width * 5 / 6 - boundaryOffset
        )
        sampleX.forEachIndexed { index, x ->
            val pixel = pixels[x, image.height / 2]
            if (index == activeThird) {
                assertTrue("当前第 $index 层场景没有模糊: $pixel", pixel.red in 0.15f..0.85f)
            } else {
                assertTrue(
                    "已离开的第 $index 层场景仍保留模糊: $pixel",
                    pixel.red < 0.05f || pixel.red > 0.95f
                )
            }
        }
    }

    private fun assertSceneBlurPresent(
        image: androidx.compose.ui.graphics.ImageBitmap,
        message: String
    ) {
        val mixedPixels = mixedPixelCount(image, y = image.height / 8)
        assertTrue(
            "$message: mixedPixels=$mixedPixels width=${image.width}",
            mixedPixels > image.width / 20
        )
    }

    private fun assertSceneBlurAbsent(image: androidx.compose.ui.graphics.ImageBitmap) {
        val sceneMixedPixels = mixedPixelCount(image, y = image.height / 8)
        val bottomMixedPixels = mixedPixelCount(image, y = image.height - 4)
        assertTrue(
            "空页面仍保留上一场景遮罩: mixedPixels=$sceneMixedPixels",
            sceneMixedPixels < image.width / 40
        )
        assertTrue(
            "场景锁释放时误清除了固定底栏遮罩: mixedPixels=$bottomMixedPixels",
            bottomMixedPixels > image.width / 20
        )
    }

    private fun mixedPixelCount(
        image: androidx.compose.ui.graphics.ImageBitmap,
        y: Int
    ): Int {
        val pixels = image.toPixelMap()
        return (0 until image.width).count { x ->
            pixels[x, y].red in 0.15f..0.85f
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private companion object {
        const val SettingsGlassTag = "settings_glass"
        const val BottomGlassTag = "bottom_glass"
        const val ContentBlurRootTag = "content_blur_root"
        const val BlurMixTag = "blur_mix"
        const val BlurRootTag = "blur_root"
        const val DirectSelectiveBlurTag = "direct_selective_blur"
        const val TopOnlyCornersTag = "top_only_corners"
        const val AdjustableBlurRootTag = "adjustable_blur_root"
        const val SceneHandoffRootTag = "scene_handoff_root"
        const val NestedSceneHandoffRootTag = "nested_scene_handoff_root"
        const val InterruptedSceneHandoffRootTag = "interrupted_scene_handoff_root"
        const val MovingMaskRootTag = "moving_mask_root"
        const val NavHostSceneHandoffRootTag = "nav_host_scene_handoff_root"
        const val FirstTabRoute = "first_tab"
        const val SecondTabRoute = "second_tab"
        const val FirstTabContentTag = "first_tab_content"
        const val SecondTabContentTag = "second_tab_content"
    }
}
