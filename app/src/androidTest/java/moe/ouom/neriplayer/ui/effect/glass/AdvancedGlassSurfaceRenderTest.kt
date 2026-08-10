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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.collect
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.ExploreGlassPillSurface
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHomeScaffold
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
    fun lowQualityUsesLocalNativeBlurWithoutLosingTheGlassEffect() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.Low
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(LocalBlurRootTag)
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
        composeRule.runOnIdle {
            assertNotNull(
                "low quality did not install a region-local blur plan",
                capturedBackdrop.localBlurPlan
            )
            assertNull(
                "low quality unexpectedly kept the full-screen render effect",
                capturedBackdrop.renderEffect
            )
        }
        val image = composeRule.onNodeWithTag(LocalBlurRootTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - 6, image.height / 2]

        assertTrue(
            "low quality backdrop boundary stayed sharp instead of being blurred: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f &&
                mixedPixel.green in 0.15f..0.85f &&
                mixedPixel.blue in 0.15f..0.85f
        )
    }

    @Test
    fun localBlurKeepsItsPlanAcrossNavigationMaskGaps() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var capturedContentBackdrop: AdvancedGlassBackdrop
        lateinit var activeOwners: MutableState<Set<Any>>
        lateinit var handoffActive: MutableState<Boolean>
        lateinit var quality: MutableState<AdvancedBlurQuality>
        val planAvailability = mutableListOf<Boolean>()
        val firstOwner = Any()
        val secondOwner = Any()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            capturedContentBackdrop = contentBackdrop
            activeOwners = remember { mutableStateOf(setOf(firstOwner)) }
            handoffActive = remember { mutableStateOf(false) }
            quality = remember { mutableStateOf(AdvancedBlurQuality.Low) }
            LaunchedEffect(backgroundBackdrop) {
                snapshotFlow { backgroundBackdrop.localBlurPlan != null }
                    .collect(planAvailability::add)
            }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = quality.value
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwners.value
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 100.dp)
                            .testTag(LocalBlurHandoffRootTag)
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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(contentBackdrop)
                        )
                        AdvancedGlassNavigationHandoff(enabled = handoffActive.value) {
                            CompositionLocalProvider(
                                LocalAdvancedGlassNavigationOwner provides firstOwner
                            ) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .align(Alignment.CenterStart),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                            CompositionLocalProvider(
                                LocalAdvancedGlassNavigationOwner provides secondOwner
                            ) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .align(Alignment.CenterEnd),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.BottomNavigation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .align(Alignment.BottomCenter),
                                tintColor = Color.Transparent
                            ) {}
                        }
                    }
                }
            }
        }

        fun assertHandoff(blurQuality: AdvancedBlurQuality) {
            composeRule.runOnIdle {
                quality.value = blurQuality
                activeOwners.value = setOf(firstOwner)
                handoffActive.value = false
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertNotNull("$blurQuality did not install a local blur plan", capturedBackdrop.localBlurPlan)
                assertNotNull(
                    "$blurQuality did not install a content local blur plan",
                    capturedContentBackdrop.localBlurPlan
                )
                planAvailability.clear()
                handoffActive.value = true
                activeOwners.value = emptySet()
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertNotNull(
                    "$blurQuality removed its cached plan during the navigation handoff",
                    capturedBackdrop.localBlurPlan
                )
                assertTrue(
                    "$blurQuality exposed a blank local blur frame: $planAvailability",
                    planAvailability.none { available -> !available }
                )
                assertTrue(
                    "$blurQuality did not freeze its last local blur frame during the mask gap",
                    capturedBackdrop.freezeLocalBlurFrame
                )
                assertTrue(
                    "$blurQuality froze its stable content blur frame during the handoff",
                    !capturedContentBackdrop.freezeLocalBlurFrame
                )
                activeOwners.value = setOf(secondOwner)
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertTrue(
                    "$blurQuality kept replaying a stale local blur frame after the next mask arrived",
                    !capturedBackdrop.freezeLocalBlurFrame
                )
                assertTrue(
                    "$blurQuality did not keep its handoff guard while the next mask was active",
                    capturedBackdrop.hasLocalBlurHandoffGuard
                )
                assertTrue(
                    "$blurQuality froze its stable content blur frame after the next mask arrived",
                    !capturedContentBackdrop.freezeLocalBlurFrame
                )
                handoffActive.value = false
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertTrue(
                    "$blurQuality did not resume local blur after navigation handoff",
                    !capturedBackdrop.hasLocalBlurHandoffGuard
                )
                assertTrue(
                    "$blurQuality did not refresh its content blur after navigation handoff",
                    !capturedContentBackdrop.freezeLocalBlurFrame
                )
            }
            assertSceneMaskState(
                composeRule.onNodeWithTag(LocalBlurHandoffRootTag).captureToImage(),
                leftBlurred = false
            )
        }

        assertHandoff(AdvancedBlurQuality.Low)
        assertHandoff(AdvancedBlurQuality.UltraLow)
    }

    @Test
    fun prewarmedNavigationOwnerSwitchesWithoutReplayingThePreviousMask() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var activeOwners: MutableState<Set<Any>>
        lateinit var incomingSceneLaidOut: MutableState<Boolean>
        val firstOwner = Any()
        val secondOwner = Any()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            capturedBackdrop = backgroundBackdrop
            activeOwners = remember { mutableStateOf(setOf(firstOwner)) }
            incomingSceneLaidOut = remember { mutableStateOf(false) }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.Low
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwners.value,
                    prewarmedNavigationOwners = setOf(firstOwner, secondOwner)
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 100.dp)
                            .testTag(PrewarmedOwnerHandoffRootTag)
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
                        AdvancedGlassNavigationHandoff(enabled = true) {
                            CompositionLocalProvider(
                                LocalAdvancedGlassNavigationOwner provides firstOwner
                            ) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .align(Alignment.CenterStart),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                            AdvancedGlassScene(active = true) {
                                CompositionLocalProvider(
                                    LocalAdvancedGlassNavigationOwner provides secondOwner
                                ) {
                                    AdvancedGlassSurface(
                                        role = AdvancedGlassRole.SettingsSection,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .align(Alignment.CenterEnd)
                                            .onGloballyPositioned {
                                                incomingSceneLaidOut.value = true
                                            },
                                        tintColor = Color.Transparent
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(
                "prewarmed inactive owner did not finish layout before the handoff",
                incomingSceneLaidOut.value
            )
            assertNotNull("prewarmed first owner did not install a blur plan", capturedBackdrop.localBlurPlan)
            assertTrue(
                "prewarmed first owner unexpectedly froze the local blur frame",
                !capturedBackdrop.freezeLocalBlurFrame
            )
        }
        assertSceneMaskState(
            composeRule.onNodeWithTag(PrewarmedOwnerHandoffRootTag).captureToImage(),
            leftBlurred = true
        )

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnIdle {
                activeOwners.value = setOf(secondOwner)
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                assertNotNull(
                    "prewarmed next owner cleared the blur plan in its first frame",
                    capturedBackdrop.localBlurPlan
                )
                assertTrue(
                    "prewarmed owner switch replayed the previous local blur frame",
                    !capturedBackdrop.freezeLocalBlurFrame
                )
            }
            assertSceneMaskState(
                composeRule.onNodeWithTag(PrewarmedOwnerHandoffRootTag).captureToImage(),
                leftBlurred = false
            )
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun prewarmedInactiveNavigationOwnerPreservesTintDuringHandoff() {
        val activeOwner = Any()
        val inactiveOwner = Any()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = setOf(activeOwner),
                    prewarmedNavigationOwners = setOf(inactiveOwner)
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(PrewarmedInactiveTintRootTag)
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
                        AdvancedGlassNavigationHandoff(enabled = true) {
                            CompositionLocalProvider(
                                LocalAdvancedGlassNavigationOwner provides activeOwner
                            ) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SemanticCard,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .align(Alignment.CenterStart),
                                    fallbackColor = Color.Transparent,
                                    tintColor = Color.Black,
                                    preserveTintForInactiveNavigationOwner = true
                                ) {}
                            }
                            CompositionLocalProvider(
                                LocalAdvancedGlassNavigationOwner provides inactiveOwner
                            ) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SemanticCard,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .align(Alignment.CenterEnd)
                                        .testTag(PrewarmedInactiveTintTag),
                                    fallbackColor = Color.Transparent,
                                    tintColor = Color.Black,
                                    preserveTintForInactiveNavigationOwner = true
                                ) {}
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(PrewarmedInactiveTintTag).captureToImage()
        val pixel = image.toPixelMap()[image.width / 2, image.height / 2]

        assertTrue(
            "prewarmed inactive owner fell back to a blank or opaque frame: $pixel",
            pixel.red in 0.15f..0.85f &&
                pixel.green in 0.15f..0.85f &&
                pixel.blue in 0.15f..0.85f
        )
    }

    @Test
    fun localBlurRefreshesStableMaskWhenBackdropChanges() {
        lateinit var backdropColor: MutableState<Color>
        composeRule.setContent {
            backdropColor = remember { mutableStateOf(Color.Black) }
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.Low
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 120.dp)
                            .testTag(LocalBlurRefreshRootTag)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(backdropColor.value)
                        )
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
        val initialImage = composeRule
            .onNodeWithTag(LocalBlurRefreshRootTag)
            .captureToImage()
        val sampleX = initialImage.width / 2
        val sampleY = initialImage.height / 2
        val initialPixel = initialImage.toPixelMap()[sampleX, sampleY]

        composeRule.runOnIdle { backdropColor.value = Color.White }
        composeRule.waitForIdle()
        val refreshedPixel = composeRule
            .onNodeWithTag(LocalBlurRefreshRootTag)
            .captureToImage()
            .toPixelMap()[sampleX, sampleY]

        assertTrue(
            "local blur did not render the initial backdrop: $initialPixel",
            initialPixel.red < 0.1f
        )
        assertTrue(
            "local blur replayed a stale backdrop frame after content changed: $refreshedPixel",
            refreshedPixel.red > 0.9f
        )
    }

    @Test
    fun localBlurRebuildsItsRenderCacheAfterLifecycleAndSettingChanges() {
        lateinit var capturedBackdrop: AdvancedGlassBackdrop
        lateinit var quality: MutableState<AdvancedBlurQuality>
        lateinit var blurEnabled: MutableState<Boolean>
        val lifecycleOwner = TestLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                val contentBackdrop = rememberAdvancedGlassBackdrop()
                capturedBackdrop = backgroundBackdrop
                quality = remember { mutableStateOf(AdvancedBlurQuality.Low) }
                blurEnabled = remember { mutableStateOf(true) }
                MaterialTheme {
                    AdvancedGlassHost(
                        controller = enabledController().copy(
                            advancedBlurEnabled = blurEnabled.value,
                            advancedBlurQuality = quality.value
                        ),
                        backgroundBackdrop = backgroundBackdrop,
                        contentBackdrop = contentBackdrop
                    ) {
                        Box(
                            modifier = Modifier
                                .size(200.dp, 120.dp)
                                .testTag(LocalBlurRootTag)
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
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            lifecycleOwner.dispatch(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.dispatch(Lifecycle.Event.ON_START)
            lifecycleOwner.dispatch(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertLocalBlurredBackdropBoundary()
        lateinit var lowRenderer: AdvancedGlassLocalBlurRenderer
        composeRule.runOnIdle {
            val plan = checkNotNull(capturedBackdrop.localBlurPlan)
            lowRenderer = capturedBackdrop.localBlurRenderer(plan.rendererCacheKey)
            quality.value = AdvancedBlurQuality.UltraLow
        }
        composeRule.waitForIdle()

        assertLocalBlurredBackdropBoundary()
        lateinit var ultraLowRenderer: AdvancedGlassLocalBlurRenderer
        composeRule.runOnIdle {
            val plan = checkNotNull(capturedBackdrop.localBlurPlan)
            ultraLowRenderer = capturedBackdrop.localBlurRenderer(plan.rendererCacheKey)
            assertNotSame(
                "quality change reused a stale local blur renderer",
                lowRenderer,
                ultraLowRenderer
            )
            lifecycleOwner.dispatch(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.dispatch(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertLocalBlurredBackdropBoundary()
        lateinit var resumedRenderer: AdvancedGlassLocalBlurRenderer
        composeRule.runOnIdle {
            val plan = checkNotNull(capturedBackdrop.localBlurPlan)
            resumedRenderer = capturedBackdrop.localBlurRenderer(plan.rendererCacheKey)
            assertNotSame(
                "foreground resume reused an invalid local blur renderer",
                ultraLowRenderer,
                resumedRenderer
            )
            blurEnabled.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNull("disabled local blur kept a render plan", capturedBackdrop.localBlurPlan)
            blurEnabled.value = true
        }
        composeRule.waitForIdle()

        assertLocalBlurredBackdropBoundary()
        composeRule.runOnIdle {
            val plan = checkNotNull(capturedBackdrop.localBlurPlan)
            val restoredRenderer = capturedBackdrop.localBlurRenderer(plan.rendererCacheKey)
            assertNotSame(
                "re-enabled local blur reused a stale renderer",
                resumedRenderer,
                restoredRenderer
            )
        }
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
                    renderProfile = AdvancedGlassRenderProfile.Native,
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
    fun pillSurfaceKeepsItsFlatTopEdgeInsideTheBlurMask() {
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.High
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp, 64.dp)
                            .testTag(PillMaskRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(16) { stripeIndex ->
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
                        AdvancedGlassSurface(
                            role = AdvancedGlassRole.ExploreTag,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(50),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(PillMaskRootTag).captureToImage()
        val pixels = image.toPixelMap()
        val sampleY = image.height / 16
        val pillShoulder = pixels[image.width * 15 / 80, sampleY]
        val outsidePill = pixels[image.width * 7 / 80, sampleY]

        assertTrue(
            "pill shoulder was not blurred: $pillShoulder",
            pillShoulder.red in 0.15f..0.85f
        )
        assertTrue(
            "pill mask extended outside its rounded end: $outsidePill",
            outsidePill.red > 0.95f
        )
    }

    @Test
    fun explorePillMaskExcludesMinimumTouchTargetPadding() {
        lateinit var regionRegistry: AdvancedGlassRegionRegistry
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.High
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp, 80.dp)
                            .testTag(ExplorePillTouchTargetRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) {
                            repeat(16) { stripeIndex ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (stripeIndex % 2 == 0) {
                                                Color.Black
                                            } else {
                                                Color.White
                                            }
                                        )
                                )
                            }
                        }
                        regionRegistry = requireNotNull(LocalAdvancedGlassBackdrops.current)
                            .regionRegistry
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            ExploreGlassPillSurface(
                                fallbackColor = Color.Transparent,
                                tintColor = Color.Transparent,
                                contentColor = Color.Transparent,
                                onClick = {}
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val visualPillRegion = regionRegistry.regions.single { region ->
                region.role == AdvancedGlassRole.ExploreTag
            }
            val expectedVisualHeight = with(composeRule.density) { 32.dp.toPx() }
            assertEquals(
                "explore blur mask registered the 48dp touch target instead of the visible pill",
                expectedVisualHeight,
                visualPillRegion.boundsInWindow.height,
                1f
            )
            val expectedCornerRadius = expectedVisualHeight / 2f
            listOf(
                visualPillRegion.cornerRadiiPx.topLeft,
                visualPillRegion.cornerRadiiPx.topRight,
                visualPillRegion.cornerRadiiPx.bottomRight,
                visualPillRegion.cornerRadiiPx.bottomLeft
            ).forEach { cornerRadius ->
                assertEquals(
                    "explore blur mask did not keep the visual pill corner radius",
                    expectedCornerRadius,
                    cornerRadius,
                    1f
                )
            }
        }

        val image = composeRule.onNodeWithTag(ExplorePillTouchTargetRootTag).captureToImage()
        val pixels = image.toPixelMap()
        val centerX = image.width / 2 - 2
        val centerY = image.height / 2
        val touchPaddingY = centerY - with(composeRule.density) { 20.dp.roundToPx() }
        val blurredPillPixel = pixels[centerX, centerY]
        val sharpTouchPaddingPixel = pixels[centerX, touchPaddingY]

        assertTrue(
            "explore pill center was not blurred: $blurredPillPixel",
            blurredPillPixel.red in 0.15f..0.85f
        )
        assertTrue(
            "explore touch-target padding was incorrectly blurred: $sharpTouchPaddingPixel",
            sharpTouchPaddingPixel.red < 0.05f || sharpTouchPaddingPixel.red > 0.95f
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun settingsTopAppBarRegistersOnlyOneThemeToggleMask() {
        lateinit var regionRegistry: AdvancedGlassRegionRegistry
        lateinit var topAppBarState: TopAppBarState
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.High
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(modifier = Modifier.size(360.dp, 280.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.White)
                        )
                        regionRegistry = requireNotNull(LocalAdvancedGlassBackdrops.current)
                            .regionRegistry
                        topAppBarState = rememberTopAppBarState()
                        MiuixSettingsHomeScaffold(
                            listState = rememberLazyListState(),
                            topAppBarState = topAppBarState,
                            title = {
                                ThemeModeActionButton(
                                    isDarkTheme = false,
                                    onToggleRequest = { _, _ -> }
                                )
                            }
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(
                "settings top app bar did not expose a collapsed state",
                topAppBarState.heightOffsetLimit < 0f
            )
        }
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { collapsedFraction ->
            composeRule.runOnIdle {
                topAppBarState.heightOffset =
                    topAppBarState.heightOffsetLimit * collapsedFraction
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    "settings top app bar registered duplicate theme toggle masks at " +
                        "collapsed fraction $collapsedFraction",
                    1,
                    regionRegistry.regions.count { region ->
                        region.role == AdvancedGlassRole.ThemeModeToggle
                    }
                )
            }
        }
    }

    @Test
    fun themeToggleClickabilityIsIndependentFromBackdropRegistration() {
        val darkThemeDescription = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.settings_theme_toggle_dark)
        var toggleRequests = 0
        lateinit var titleSlotVisible: MutableState<Boolean>

        composeRule.setContent {
            titleSlotVisible = remember { mutableStateOf(false) }
            CompositionLocalProvider(
                LocalAdvancedGlassBackdropRegistrationEnabled provides titleSlotVisible.value
            ) {
                MaterialTheme {
                    ThemeModeActionButton(
                        isDarkTheme = false,
                        onToggleRequest = { _, _ -> toggleRequests += 1 }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(darkThemeDescription)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            titleSlotVisible.value = true
        }

        composeRule.onNodeWithContentDescription(darkThemeDescription)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                "backdrop registration incorrectly affected theme toggle clickability",
                2,
                toggleRequests
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun settingsTopAppBarThemeToggleHandlesConsecutiveClicks() {
        val darkThemeDescription = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.settings_theme_toggle_dark)
        val lightThemeDescription = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.settings_theme_toggle_light)
        var toggleRequests = 0
        lateinit var isDarkTheme: MutableState<Boolean>

        composeRule.setContent {
            isDarkTheme = remember { mutableStateOf(false) }
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.High
                    ),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop
                ) {
                    Box(modifier = Modifier.size(360.dp, 280.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .background(Color.White)
                        )
                        MiuixSettingsHomeScaffold(
                            listState = rememberLazyListState(),
                            topAppBarState = rememberTopAppBarState(),
                            title = {
                                ThemeModeActionButton(
                                    isDarkTheme = isDarkTheme.value,
                                    onToggleRequest = { _, _ ->
                                        toggleRequests += 1
                                        isDarkTheme.value = !isDarkTheme.value
                                    }
                                )
                            }
                        ) {}
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription(darkThemeDescription)
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(lightThemeDescription)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                "settings title theme toggle did not accept a second click",
                2,
                toggleRequests
            )
        }
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
    fun navigationOwnerIsolatesParallelSceneMasks() {
        lateinit var activeOwners: MutableState<Set<Any>>
        val firstOwner = Any()
        val secondOwner = Any()
        composeRule.setContent {
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            activeOwners = remember { mutableStateOf(setOf(firstOwner)) }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = enabledController(),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwners.value
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp, 100.dp)
                            .testTag(OwnedSceneMaskRootTag)
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
                                            if (stripeIndex % 2 == 0) {
                                                Color.Black
                                            } else {
                                                Color.White
                                            }
                                        )
                                )
                            }
                        }
                        CompositionLocalProvider(
                            LocalAdvancedGlassNavigationOwner provides firstOwner
                        ) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.SettingsSection,
                                modifier = Modifier
                                    .size(80.dp)
                                    .align(Alignment.CenterStart),
                                tintColor = Color.Transparent
                            ) {}
                        }
                        CompositionLocalProvider(
                            LocalAdvancedGlassNavigationOwner provides secondOwner
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

        composeRule.waitForIdle()
        assertSceneMaskState(
            composeRule.onNodeWithTag(OwnedSceneMaskRootTag).captureToImage(),
            leftBlurred = true
        )

        composeRule.runOnIdle {
            activeOwners.value = setOf(secondOwner)
        }
        composeRule.waitForIdle()
        assertSceneMaskState(
            composeRule.onNodeWithTag(OwnedSceneMaskRootTag).captureToImage(),
            leftBlurred = false
        )
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
                                        animationSpec = advancedGlassMainTabTransitionSpec()
                                    ) { fullWidth ->
                                        -fullWidth
                                    }
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
                                        animationSpec = advancedGlassMainTabTransitionSpec()
                                    ) { fullWidth ->
                                        fullWidth
                                    }
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
                    controller = enabledController().copy(
                        advancedBlurQuality = AdvancedBlurQuality.High
                    ),
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
                            role = AdvancedGlassRole.ThemeModeToggle,
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
    fun backdropOffsetKeepsTheMaskAtTheSurfaceWindowPosition() {
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
                            .size(200.dp, 180.dp)
                            .testTag(OffsetBackdropRootTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .offset(y = 40.dp)
                                .size(200.dp, 120.dp)
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
                                .offset(y = 60.dp)
                                .size(160.dp, 60.dp)
                                .align(Alignment.TopCenter),
                            tintColor = Color.Transparent
                        ) {}
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull("offset backdrop never received a blur effect", capturedBackdrop.renderEffect)
        }
        val image = composeRule.onNodeWithTag(OffsetBackdropRootTag).captureToImage()
        val boundaryX = image.width / 2 - 6
        val targetY = image.height / 2
        val staleY = image.height / 2 - with(composeRule.density) { 40.dp.roundToPx() }
        val pixels = image.toPixelMap()
        val targetPixel = pixels[boundaryX, targetY]
        val stalePixel = pixels[boundaryX, staleY]

        assertTrue(
            "offset backdrop did not blur the visible surface: $targetPixel",
            targetPixel.red in 0.15f..0.85f
        )
        assertTrue(
            "offset backdrop left blur above the visible surface: $stalePixel",
            stalePixel.red < 0.1f
        )
    }

    @Test
    fun enhancedGlassUsesDampedOverscrollInsideHost() {
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
            assertEquals(
                "进阶模糊下未启用阻尼回弹 Overscroll",
                AdvancedGlassOverscrollFactory,
                observedFactory
            )
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

    private fun assertLocalBlurredBackdropBoundary() {
        val image = composeRule.onNodeWithTag(LocalBlurRootTag).captureToImage()
        val mixedPixel = image.toPixelMap()[image.width / 2 - 6, image.height / 2]

        assertTrue(
            "local blur returned a blank or sharp backdrop: $mixedPixel",
            mixedPixel.red in 0.15f..0.85f &&
                mixedPixel.green in 0.15f..0.85f &&
                mixedPixel.blue in 0.15f..0.85f
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

        fun dispatch(event: Lifecycle.Event) {
            lifecycle.handleLifecycleEvent(event)
        }
    }

    private companion object {
        const val SettingsGlassTag = "settings_glass"
        const val BottomGlassTag = "bottom_glass"
        const val ContentBlurRootTag = "content_blur_root"
        const val BlurMixTag = "blur_mix"
        const val BlurRootTag = "blur_root"
        const val LocalBlurRootTag = "local_blur_root"
        const val LocalBlurHandoffRootTag = "local_blur_handoff_root"
        const val PrewarmedOwnerHandoffRootTag = "prewarmed_owner_handoff_root"
        const val PrewarmedInactiveTintRootTag = "prewarmed_inactive_tint_root"
        const val PrewarmedInactiveTintTag = "prewarmed_inactive_tint"
        const val LocalBlurRefreshRootTag = "local_blur_refresh_root"
        const val DirectSelectiveBlurTag = "direct_selective_blur"
        const val TopOnlyCornersTag = "top_only_corners"
        const val PillMaskRootTag = "pill_mask_root"
        const val ExplorePillTouchTargetRootTag = "explore_pill_touch_target_root"
        const val AdjustableBlurRootTag = "adjustable_blur_root"
        const val SceneHandoffRootTag = "scene_handoff_root"
        const val NestedSceneHandoffRootTag = "nested_scene_handoff_root"
        const val InterruptedSceneHandoffRootTag = "interrupted_scene_handoff_root"
        const val MovingMaskRootTag = "moving_mask_root"
        const val OffsetBackdropRootTag = "offset_backdrop_root"
        const val OwnedSceneMaskRootTag = "owned_scene_mask_root"
        const val NavHostSceneHandoffRootTag = "nav_host_scene_handoff_root"
        const val FirstTabRoute = "first_tab"
        const val SecondTabRoute = "second_tab"
        const val FirstTabContentTag = "first_tab_content"
        const val SecondTabContentTag = "second_tab_content"
    }
}
