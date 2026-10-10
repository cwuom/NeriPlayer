package moe.ouom.neriplayer.ui.effect.glass

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.navigation.MainTabGlassOwner
import moe.ouom.neriplayer.ui.navigation.MainTabLayerHost
import moe.ouom.neriplayer.ui.navigation.MainTabLayerTransitionState
import moe.ouom.neriplayer.ui.navigation.rememberMainTabLayerTransitionState
import moe.ouom.neriplayer.ui.navigation.resolveMainTabLayerSceneTransform
import moe.ouom.neriplayer.ui.navigation.resolveMainTabTransitionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AdvancedGlassMainTabTransformRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun nativeBlurKeepsScaledSceneMaskAtItsVisibleBounds() {
        assertScaledSceneMask(AdvancedBlurQuality.High)
    }

    @Test
    fun localBlurKeepsScaledSceneMaskAtItsVisibleBounds() {
        assertScaledSceneMask(AdvancedBlurQuality.Low)
    }

    @Test
    fun nativeFixedBackgroundFadesMasksAndClearsRapidTabSwitches() {
        assertFixedBackgroundHandoff(AdvancedBlurQuality.High)
    }

    @Test
    fun localFixedBackgroundFadesMasksAndClearsRapidTabSwitches() {
        assertFixedBackgroundHandoff(AdvancedBlurQuality.Low)
    }

    @Test
    fun nativeSceneBackdropFadesOnceWithItsParent() {
        assertSceneBackdropFade(AdvancedBlurQuality.High)
    }

    @Test
    fun localSceneBackdropFadesOnceWithItsParent() {
        assertSceneBackdropFade(AdvancedBlurQuality.Low)
    }

    private fun assertSceneBackdropFade(quality: AdvancedBlurQuality) {
        lateinit var sceneAlpha: MutableFloatState
        lateinit var scale: MutableFloatState
        lateinit var backdrop: AdvancedGlassBackdrop
        lateinit var registry: AdvancedGlassRegionRegistry
        composeRule.setContent {
            sceneAlpha = remember { mutableFloatStateOf(1f) }
            scale = remember { mutableFloatStateOf(1f) }
            val sceneOpacity = remember(sceneAlpha) { { sceneAlpha.floatValue } }
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(400.dp, 240.dp)
                        .background(Color.White)
                        .testTag(RootTag)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = sceneAlpha.floatValue
                            scaleX = scale.floatValue
                            scaleY = scale.floatValue
                        }
                    ) {
                        CompositionLocalProvider(LocalAdvancedGlassSceneOpacity provides sceneOpacity) {
                            AdvancedGlassSceneLayer(
                                controller = controller(quality),
                                background = { StripedBackground() },
                                content = {
                                    val backdrops = requireNotNull(LocalAdvancedGlassBackdrops.current)
                                    SideEffect {
                                        backdrop = backdrops.background
                                        registry = backdrops.regionRegistry
                                    }
                                    AdvancedGlassSurface(
                                        role = AdvancedGlassRole.SettingsSection,
                                        modifier = Modifier.fillMaxSize(),
                                        tintColor = Color.Transparent
                                    ) {}
                                }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        fun sample(): Color {
            val image = composeRule.onNodeWithTag(RootTag).captureToImage()
            val x = with(composeRule.density) { 205.dp.roundToPx() }
            val y = with(composeRule.density) { 120.dp.roundToPx() }
            return image.toPixelMap()[x, y]
        }
        val opaque = sample()
        assertTrue("Opaque local scene did not blur its own backdrop", opaque.red in 0.15f..0.85f)
        composeRule.runOnIdle {
            sceneAlpha.floatValue = 0.5f
            scale.floatValue = 0.97f
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1f, registry.regions.single().opacity, 0.001f)
        }
        val faded = sample()
        val expectedRed = 0.5f + opaque.red * 0.5f
        assertTrue(
            "Local backdrop applied the parent fade twice: quality=$quality, " +
                "opaque=$opaque, faded=$faded, expectedRed=$expectedRed",
            abs(faded.red - expectedRed) < 0.06f
        )
        composeRule.runOnIdle {
            sceneAlpha.floatValue = 0f
            scale.floatValue = 0.94f
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(0f, registry.regions.single().opacity, 0f)
            assertTrue("Invisible local backdrop kept a blur mask", !backdrop.hasActiveBlur)
        }
        assertTrue("Invisible scene did not reveal the white root", sample().red > 0.98f)
        composeRule.runOnIdle {
            sceneAlpha.floatValue = 1f
            scale.floatValue = 1f
        }
        composeRule.waitForIdle()
        assertTrue("Settled local scene did not restore its blur", abs(sample().red - opaque.red) < 0.05f)
    }

    private fun assertScaledSceneMask(quality: AdvancedBlurQuality) {
        lateinit var scale: MutableFloatState
        lateinit var backdrop: AdvancedGlassBackdrop
        lateinit var registry: AdvancedGlassRegionRegistry
        lateinit var density: Density
        var rootPosition = Offset.Zero
        composeRule.setContent {
            density = LocalDensity.current
            scale = remember { mutableFloatStateOf(1f) }
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(400.dp, 300.dp)
                        .onGloballyPositioned { rootPosition = it.positionInWindow() }
                        .testTag(RootTag)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale.floatValue
                                scaleY = scale.floatValue
                            }
                    ) {
                        AdvancedGlassSceneLayer(
                            controller = controller(quality),
                            background = { StripedBackground() },
                            content = {
                                val backdrops = requireNotNull(LocalAdvancedGlassBackdrops.current)
                                SideEffect {
                                    backdrop = backdrops.background
                                    registry = backdrops.regionRegistry
                                }
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier
                                        .size(180.dp, 120.dp)
                                        .align(Alignment.BottomEnd),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                        )
                    }
                }
            }
        }

        listOf(0.94f, 0.97f, 1f).forEach { frameScale ->
            composeRule.runOnIdle { scale.floatValue = frameScale }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(frameScale, backdrop.scaleInWindow.x, 0.001f)
                assertEquals(frameScale, backdrop.scaleInWindow.y, 0.001f)
                val localRegion = resolveStableAdvancedGlassRenderRegions(
                    backdropPositionInWindow = backdrop.positionInWindow,
                    regions = registry.regions.toList(),
                    backdropScaleInWindow = backdrop.scaleInWindow
                ).single()
                assertEquals(with(density) { 220.dp.toPx() }, localRegion.left, 1f)
                assertEquals(with(density) { 180.dp.toPx() }, localRegion.top, 1f)
                assertEquals(with(density) { 400.dp.toPx() }, localRegion.right, 1f)
                assertEquals(with(density) { 300.dp.toPx() }, localRegion.bottom, 1f)
                assertTrue("Scaled scene lost its blur", backdrop.hasActiveBlur)
            }

            val image = composeRule.onNodeWithTag(RootTag).captureToImage()
            val pixels = image.toPixelMap()
            fun sample(localX: Float, localY: Float): Color {
                val x = (backdrop.positionInWindow.x - rootPosition.x +
                    with(density) { localX.dp.toPx() } * frameScale).roundToInt()
                val y = (backdrop.positionInWindow.y - rootPosition.y +
                    with(density) { localY.dp.toPx() } * frameScale).roundToInt()
                return pixels[x, y]
            }
            val inside = sample(385f, 240f)
            val outside = sample(215f, 240f)
            assertTrue(
                "Visible mask edge lost blur at scale=$frameScale, quality=$quality: $inside",
                inside.red in 0.15f..0.85f
            )
            assertTrue(
                "Mask spilled outside the scaled surface at scale=$frameScale: $outside",
                outside.red > 0.9f
            )
        }
    }

    private fun assertFixedBackgroundHandoff(quality: AdvancedBlurQuality) {
        assertNotNull(resolveMainTabTransitionDirection(FirstRoute, SecondRoute))
        assertNotNull(resolveMainTabTransitionDirection(SecondRoute, ThirdRoute))
        lateinit var selectedRoute: MutableState<String>
        lateinit var backdrop: AdvancedGlassBackdrop
        lateinit var registry: AdvancedGlassRegionRegistry
        lateinit var density: Density
        lateinit var transitionState: MainTabLayerTransitionState
        val sceneOpacityGetters = mutableMapOf<String, () -> Float>()
        val frameDiagnostics = mutableListOf<String>()
        composeRule.mainClock.autoAdvance = false
        setTabletMainTabContent {
            density = LocalDensity.current
            selectedRoute = remember { mutableStateOf(FirstRoute) }
            transitionState = rememberMainTabLayerTransitionState(FirstRoute)
            val owners = remember { mutableStateOf<Set<Any>>(setOf(MainTabGlassOwner(FirstRoute))) }
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            backdrop = backgroundBackdrop
            MaterialTheme {
                AdvancedGlassHost(
                    controller = controller(quality),
                    backgroundBackdrop = backgroundBackdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = owners.value
                ) {
                    val backdrops = requireNotNull(LocalAdvancedGlassBackdrops.current)
                    SideEffect { registry = backdrops.regionRegistry }
                    Box(modifier = Modifier.size(400.dp, 240.dp).testTag(RootTag)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(backgroundBackdrop)
                        ) { StripedBackground() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureAdvancedGlassBackdrop(contentBackdrop)
                        ) {
                            MainTabLayerHost(
                                selectedRoute = selectedRoute.value,
                                transitionState = transitionState,
                                modifier = Modifier.fillMaxSize(),
                                onVisibleGlassOwnersChanged = { owners.value = it }
                            ) { route ->
                                AdvancedGlassSceneLayer(
                                    controller = controller(quality),
                                    fixedBackground = true,
                                    background = {},
                                    content = {
                                        val sceneOpacity = LocalAdvancedGlassSceneOpacity.current
                                        SideEffect { sceneOpacityGetters[route] = sceneOpacity }
                                        val left = when (route) {
                                            FirstRoute -> 0.dp
                                            SecondRoute -> 180.dp
                                            else -> 300.dp
                                        }
                                        val width = when (route) {
                                            FirstRoute -> 220.dp
                                            SecondRoute -> 120.dp
                                            else -> 100.dp
                                        }
                                        AdvancedGlassSurface(
                                            role = AdvancedGlassRole.SettingsSection,
                                            modifier = Modifier
                                                .offset(x = left, y = 40.dp)
                                                .size(width, 160.dp),
                                            tintColor = Color.Transparent
                                        ) {}
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(48L)
        composeRule.waitForIdle()
        assertFixedBackgroundPixel(quality, 45f, blurred = true)
        assertFixedBackgroundPixel(quality, 345f, blurred = false)
        val initialPixels = composeRule.onNodeWithTag(RootTag).captureToImage().toPixelMap()
        val overlapX = with(density) { 205.dp.roundToPx() }
        val overlapY = with(density) { 120.dp.roundToPx() }
        val opaqueOverlapPixel = initialPixels[overlapX, overlapY]
        composeRule.runOnIdle { selectedRoute.value = SecondRoute }
        var observedFadedMask = false
        var observedInvisibleMask = false
        var observedOverlappingMasks = false
        // 首次切页还要经过入场组合和两帧准备，观察窗口必须包含完整动画预算
        val maximumFrameCount = ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS / FrameMillis + 8
        for (frame in 0 until maximumFrameCount) {
            composeRule.mainClock.advanceTimeBy(FrameMillis.toLong())
            composeRule.waitForIdle()
            val regions = composeRule.runOnIdle {
                registry.regions.toList().also { currentRegions ->
                    val scenes = transitionState.visibleScenes.joinToString { scene ->
                        val offset = transitionState.offsetFractionFor(scene)
                        "${scene.route}:${scene.phase}:token=${scene.transitionToken}:" +
                            "offset=$offset:expectedAlpha=${resolveMainTabLayerSceneTransform(offset).alpha}:" +
                            "glassGetter=${sceneOpacityGetters[scene.route]?.invoke()}"
                    }
                    val masks = currentRegions.joinToString { region ->
                        "${region.navigationOwner}:opacity=${region.opacity}:bounds=${region.boundsInWindow}"
                    }
                    val localPlan = backdrop.localBlurPlan
                    val localMasks = localPlan?.groups?.joinToString { group ->
                        "bounds=${group.bounds}:regions=${group.regions}"
                    }
                    val diagnostic = "quality=$quality, frame=$frame, time=${composeRule.mainClock.currentTime}, " +
                        "selected=${selectedRoute.value}, scenes=[$scenes], regions=[$masks], " +
                        "backdropScale=${backdrop.scaleInWindow}, blur=${backdrop.hasActiveBlur}, " +
                        "localRadius=${localPlan?.radiusPx}, downscale=${localPlan?.downscaleFactor}, " +
                        "localFreeze=${backdrop.freezeLocalBlurFrame}, localPlan=[$localMasks]"
                    frameDiagnostics += diagnostic
                }
            }
            val firstRegion = regions.singleOrNull { it.navigationOwner == MainTabGlassOwner(FirstRoute) }
            val secondRegion = regions.singleOrNull { it.navigationOwner == MainTabGlassOwner(SecondRoute) }
            if (secondRegion != null && secondRegion.opacity == 0f) {
                observedInvisibleMask = true
                assertFixedBackgroundPixel(quality, 285f, blurred = false)
            }
            if (firstRegion != null && firstRegion.opacity in 0.15f..0.65f) {
                observedFadedMask = true
                composeRule.runOnIdle {
                    transitionState.visibleScenes.forEach { scene ->
                        val region = regions.single { it.navigationOwner == scene.glassOwner }
                        val expectedAlpha = resolveMainTabLayerSceneTransform(
                            transitionState.offsetFractionFor(scene)
                        ).alpha
                        assertEquals(
                            "Registered mask did not follow the real scene alpha: ${frameDiagnostics.last()}",
                            expectedAlpha,
                            region.opacity,
                            0.001f
                        )
                    }
                    if (quality == AdvancedBlurQuality.Low) {
                        val expectedRegions = resolveStableAdvancedGlassRenderRegions(
                            backdropPositionInWindow = backdrop.positionInWindow,
                            regions = resolveCurrentAdvancedGlassRegions(registry.regions),
                            backdropScaleInWindow = backdrop.scaleInWindow
                        ).sortedBy { it.left }
                        val appliedRegions = requireNotNull(backdrop.localBlurPlan).groups
                            .flatMap { it.regions }.sortedBy { it.left }
                        assertEquals(
                            "Local render plan lagged behind the current frame: ${frameDiagnostics.last()}",
                            expectedRegions,
                            appliedRegions
                        )
                    }
                }
                val image = composeRule.onNodeWithTag(RootTag).captureToImage()
                val x = with(density) { 45.dp.roundToPx() }
                val y = with(density) { 120.dp.roundToPx() }
                val pixel = image.toPixelMap()[x, y]
                assertTrue(
                    "Static backdrop kept an opaque exiting mask: " +
                        "opacity=${firstRegion.opacity}, quality=$quality, pixel=$pixel, " +
                        frameDiagnostics.last(),
                    pixel.red < firstRegion.opacity * 0.65f + 0.08f
                )
                assertTrue(
                    "Exiting mask stopped blurring before it faded: $pixel, ${frameDiagnostics.last()}",
                    pixel.red > firstRegion.opacity * 0.25f - 0.04f
                )
                if (secondRegion != null && secondRegion.opacity > 0f) {
                    observedOverlappingMasks = true
                    val overlapPixel = image.toPixelMap()[overlapX, overlapY]
                    val maximumOpacity = maxOf(firstRegion.opacity, secondRegion.opacity)
                    val expectedOverlap = opaqueOverlapPixel.red * maximumOpacity
                    assertTrue(
                        "Overlapping masks compounded their opacity: quality=$quality, " +
                            "first=${firstRegion.opacity}, second=${secondRegion.opacity}, " +
                            "pixel=$overlapPixel, expectedRed=$expectedOverlap, ${frameDiagnostics.last()}",
                        abs(overlapPixel.red - expectedOverlap) < 0.055f
                    )
                }
            }
            if (observedFadedMask && observedInvisibleMask && observedOverlappingMasks) break
        }
        val diagnosticTrace = frameDiagnostics.joinToString("\n")
        assertTrue("No real MainTab frame exercised a fractional mask\n$diagnosticTrace", observedFadedMask)
        assertTrue("No prepared MainTab frame exercised an invisible mask\n$diagnosticTrace", observedInvisibleMask)
        assertTrue(
            "No MainTab frame exercised overlapping fractional masks\n$diagnosticTrace",
            observedOverlappingMasks
        )
        composeRule.runOnIdle {
            assertTrue(
                "Rapid retarget missed the running transition\n$diagnosticTrace",
                transitionState.visibleScenes.size == 2
            )
            assertTrue(
                "Rapid retarget ran after the exiting scene faded out\n$diagnosticTrace",
                registry.regions.any {
                    it.navigationOwner == MainTabGlassOwner(FirstRoute) && it.opacity in 0.15f..0.65f
                }
            )
        }
        composeRule.runOnIdle { selectedRoute.value = ThirdRoute }
        repeat(60) {
            composeRule.mainClock.advanceTimeBy(16L)
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            val finalRegion = registry.regions.single()
            assertEquals(MainTabGlassOwner(ThirdRoute), finalRegion.navigationOwner)
            assertEquals(1f, finalRegion.opacity, 0.001f)
            assertEquals(1f, backdrop.scaleInWindow.x, 0.001f)
            val finalBounds = resolveStableAdvancedGlassRenderRegions(
                backdropPositionInWindow = backdrop.positionInWindow,
                regions = listOf(finalRegion),
                backdropScaleInWindow = backdrop.scaleInWindow
            ).single()
            assertEquals(with(density) { 300.dp.toPx() }, finalBounds.left, 1f)
            assertEquals(with(density) { 400.dp.toPx() }, finalBounds.right, 1f)
            assertTrue("Settled tab lost its blur", backdrop.hasActiveBlur)
            if (quality == AdvancedBlurQuality.Low) {
                assertNotNull(backdrop.localBlurPlan)
                assertEquals(finalBounds, backdrop.localBlurPlan?.groups?.single()?.regions?.single())
            }
        }
        assertFixedBackgroundPixel(quality, 45f, blurred = false)
        assertFixedBackgroundPixel(quality, 205f, blurred = false)
        assertFixedBackgroundPixel(quality, 345f, blurred = true)
    }

    private fun assertFixedBackgroundPixel(
        quality: AdvancedBlurQuality,
        xDp: Float,
        blurred: Boolean
    ) {
        val image = composeRule.onNodeWithTag(RootTag).captureToImage()
        val x = with(composeRule.density) { xDp.dp.roundToPx() }
        val y = with(composeRule.density) { 120.dp.roundToPx() }
        val pixel = image.toPixelMap()[x, y]
        assertTrue(
            "Fixed backdrop mask mismatch at x=$xDp, blurred=$blurred, quality=$quality: $pixel",
            if (blurred) pixel.red in 0.15f..0.85f else pixel.red < 0.1f
        )
    }

    @Composable
    private fun StripedBackground() {
        Row(Modifier.fillMaxSize()) {
            repeat(40) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (index % 2 == 0) Color.Black else Color.White)
                )
            }
        }
    }

    private fun setTabletMainTabContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            val currentConfiguration = LocalConfiguration.current
            val tabletConfiguration = remember(currentConfiguration) {
                Configuration(currentConfiguration).apply { smallestScreenWidthDp = 600 }
            }
            CompositionLocalProvider(LocalConfiguration provides tabletConfiguration, content = content)
        }
    }

    private fun controller(quality: AdvancedBlurQuality) = AdvancedGlassController(
        sdkInt = Build.VERSION.SDK_INT,
        advancedBlurEnabled = true,
        enhancedAdvancedBlurEnabled = true,
        backendReady = true,
        enhancedAdvancedBlurRadiusDp = 24f,
        advancedBlurQuality = quality
    )

    private companion object {
        const val RootTag = "mainTabGlassTransformRoot"
        const val FrameMillis = 16
        val FirstRoute = Destinations.Home.route
        val SecondRoute = Destinations.Explore.route
        val ThirdRoute = Destinations.Library.route
    }
}
