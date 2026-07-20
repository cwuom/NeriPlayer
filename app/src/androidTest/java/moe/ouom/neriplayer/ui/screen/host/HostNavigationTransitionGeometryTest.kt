package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_BACKGROUND_SINK_FRACTION
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.isolatedAdvancedGlassVerticalTransition
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostNavigationTransitionGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun pairedHostScenesNeverOverlapDuringForwardAndBackTransitions() {
        lateinit var navigationDepth: MutableIntState
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navigationDepth = remember { mutableIntStateOf(0) }
            MaterialTheme {
                AnimatedContent(
                    targetState = navigationDepth.intValue,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    transitionSpec = {
                        isolatedAdvancedGlassVerticalTransition(
                            forward = targetState > initialState
                        ).using(SizeTransform(clip = true))
                    },
                    label = "host_navigation_geometry"
                ) { depth ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(if (depth == 0) ListSceneTag else DetailSceneTag)
                    )
                }
            }
        }

        composeRule.runOnIdle { navigationDepth.intValue = 1 }
        assertNoSceneOverlapAcrossFrames()
        finishCurrentTransition()

        composeRule.runOnIdle { navigationDepth.intValue = 0 }
        assertNoSceneOverlapAcrossFrames()
        finishCurrentTransition()
    }

    @Test
    fun drawerHostTransitionRecedesContentWithoutMovingItsBackground() {
        lateinit var navigationDepth: MutableIntState
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navigationDepth = remember { mutableIntStateOf(0) }
            MaterialTheme {
                val navigationTransition = updateTransition(
                    targetState = navigationDepth.intValue,
                    label = "host_drawer_geometry"
                )
                navigationTransition.AnimatedContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    transitionSpec = {
                        advancedGlassHostNavigationTransition(
                            forward = targetState > initialState,
                            coherentFeedbackEnabled = false
                        ).using(SizeTransform(clip = true))
                    }
                ) { depth ->
                    val motion = navigationTransition.animateAdvancedGlassSceneMotion(
                        sceneState = depth,
                        coherentFeedbackEnabled = false,
                        navigationDepth = { it },
                        label = "host_drawer_scene"
                    )
                    val sceneHeightPx = remember { mutableIntStateOf(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(if (depth == 0) ListBackgroundTag else DetailBackgroundTag)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { sceneHeightPx.intValue = it.height }
                                .graphicsLayer {
                                    translationY = sceneHeightPx.intValue *
                                        motion.contentTranslationYFraction
                                    scaleX = motion.contentScale
                                    scaleY = motion.contentScale
                                    transformOrigin = TransformOrigin(0.5f, 0f)
                                }
                                .testTag(if (depth == 0) ListSceneTag else DetailSceneTag)
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val listRestingBounds = composeRule
            .onAllNodesWithTag(ListSceneTag)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot
        composeRule.runOnIdle { navigationDepth.intValue = 1 }
        assertDrawerFramesKeepTheOldPageFixed(
            backgroundTag = ListBackgroundTag,
            contentTag = ListSceneTag,
            movingTag = DetailSceneTag,
            backgroundBounds = listRestingBounds,
            contentBounds = listRestingBounds
        )
        finishCurrentTransition()

        val listTargetBounds = listRestingBounds
        composeRule.runOnIdle { navigationDepth.intValue = 0 }
        assertDrawerFramesKeepTheOldPageFixed(
            backgroundTag = ListBackgroundTag,
            contentTag = ListSceneTag,
            movingTag = DetailSceneTag,
            backgroundBounds = listTargetBounds,
            contentBounds = listTargetBounds
        )
        finishCurrentTransition()
    }

    @Test
    fun drawerHostKeepsIntermediateSceneMovingDuringRapidNestedBack() {
        lateinit var navigationDepth: MutableIntState
        lateinit var motionByDepth: Map<Int, AdvancedGlassSceneMotion>
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navigationDepth = remember { mutableIntStateOf(0) }
            val observedMotions = remember {
                mutableStateMapOf<Int, AdvancedGlassSceneMotion>()
            }
            motionByDepth = observedMotions
            MaterialTheme {
                val navigationTransition = updateTransition(
                    targetState = navigationDepth.intValue,
                    label = "host_nested_drawer_sequence"
                )
                navigationTransition.AnimatedContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    transitionSpec = {
                        advancedGlassHostNavigationTransition(
                            forward = targetState > initialState,
                            coherentFeedbackEnabled = false
                        ).using(SizeTransform(clip = true))
                    }
                ) { depth ->
                    val motion = navigationTransition.animateAdvancedGlassSceneMotion(
                        sceneState = depth,
                        coherentFeedbackEnabled = false,
                        navigationDepth = { it },
                        label = "host_nested_drawer_scene"
                    )
                    SideEffect { observedMotions[depth] = motion }
                    DisposableEffect(depth) {
                        onDispose { observedMotions.remove(depth) }
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        composeRule.runOnIdle { navigationDepth.intValue = 1 }
        finishCurrentTransition()
        composeRule.runOnIdle { navigationDepth.intValue = 2 }
        finishCurrentTransition()

        composeRule.runOnIdle { navigationDepth.intValue = 1 }
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { navigationDepth.intValue = 0 }

        var observedIntermediateScene = false
        var maximumIntermediateTranslation = 0f
        var minimumRootTranslation = 1f
        repeat(MaxSampledFrames) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                motionByDepth[1]?.let { motion ->
                    observedIntermediateScene = true
                    maximumIntermediateTranslation = maxOf(
                        maximumIntermediateTranslation,
                        motion.contentTranslationYFraction
                    )
                }
                motionByDepth[0]?.let { motion ->
                    minimumRootTranslation = minOf(
                        minimumRootTranslation,
                        motion.contentTranslationYFraction
                    )
                }
            }
        }

        assertTrue(
            "Interrupted download manager scene disappeared instead of continuing downward",
            observedIntermediateScene
        )
        assertTrue(
            "Interrupted download manager scene did not continue its drawer exit: " +
                maximumIntermediateTranslation,
            maximumIntermediateTranslation > 0.2f
        )
        assertTrue(
            "Settings scene translated while restoring from its recessed position: " +
                minimumRootTranslation,
            minimumRootTranslation <= DRAWER_BACKGROUND_SINK_FRACTION + 0.0001f
        )
        finishCurrentTransition()
    }

    private fun assertNoSceneOverlapAcrossFrames() {
        var framesWithBothScenes = 0
        repeat(MaxSampledFrames) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val listNodes = composeRule
                .onAllNodesWithTag(ListSceneTag)
                .fetchSemanticsNodes()
            val detailNodes = composeRule
                .onAllNodesWithTag(DetailSceneTag)
                .fetchSemanticsNodes()
            assertTrue(
                "Multiple list scene nodes at frame $frame: ${listNodes.size}",
                listNodes.size <= 1
            )
            assertTrue(
                "Multiple detail scene nodes at frame $frame: ${detailNodes.size}",
                detailNodes.size <= 1
            )
            val listBounds = listNodes.singleOrNull()?.boundsInRoot
            val detailBounds = detailNodes.singleOrNull()?.boundsInRoot
            if (
                listBounds != null &&
                detailBounds != null &&
                listBounds.width > 0f &&
                listBounds.height > 0f &&
                detailBounds.width > 0f &&
                detailBounds.height > 0f
            ) {
                framesWithBothScenes++
                assertTrue(
                    "Host scenes overlap at frame $frame: " +
                        "list=$listBounds detail=$detailBounds",
                    listBounds.bottom <= detailBounds.top + PositionTolerancePx
                )
            }
        }
        assertTrue(
            "No frame contained both host scenes for comparison",
            framesWithBothScenes > 0
        )
    }

    private fun finishCurrentTransition() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun assertDrawerFramesKeepTheOldPageFixed(
        backgroundTag: String,
        contentTag: String,
        movingTag: String,
        backgroundBounds: androidx.compose.ui.geometry.Rect,
        contentBounds: androidx.compose.ui.geometry.Rect
    ) {
        var framesWithBothScenes = 0
        var observedRecededContent = false
        var minimumForegroundTop = Float.POSITIVE_INFINITY
        var maximumForegroundTop = Float.NEGATIVE_INFINITY
        repeat(MaxSampledFrames) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val background = composeRule
                .onAllNodesWithTag(backgroundTag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.boundsInRoot
            val content = composeRule
                .onAllNodesWithTag(contentTag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.boundsInRoot
            val moving = composeRule
                .onAllNodesWithTag(movingTag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.boundsInRoot
            if (
                background != null &&
                content != null &&
                moving != null &&
                background.width > 0f &&
                background.height > 0f &&
                content.width > 0f &&
                content.height > 0f &&
                moving.width > 0f &&
                moving.height > 0f
            ) {
                framesWithBothScenes++
                minimumForegroundTop = minOf(minimumForegroundTop, moving.top)
                maximumForegroundTop = maxOf(maximumForegroundTop, moving.top)
                assertTrue(
                    "Drawer moved the background layer at frame $frame: " +
                        "resting=$backgroundBounds actual=$background",
                        kotlin.math.abs(background.left - backgroundBounds.left) <=
                        PositionTolerancePx &&
                        kotlin.math.abs(background.top - backgroundBounds.top) <=
                        PositionTolerancePx &&
                        kotlin.math.abs(background.right - backgroundBounds.right) <=
                        PositionTolerancePx &&
                        kotlin.math.abs(background.bottom - backgroundBounds.bottom) <=
                        PositionTolerancePx
                )
                assertTrue(
                    "Drawer content moved vertically at frame $frame: " +
                        "resting=$contentBounds actual=$content",
                    content.top >= contentBounds.top - PositionTolerancePx &&
                        content.top <= contentBounds.top + PositionTolerancePx &&
                        content.width <= contentBounds.width + PositionTolerancePx &&
                        content.width >= contentBounds.width * 0.94f - PositionTolerancePx
                )
                observedRecededContent = observedRecededContent ||
                    content.width < contentBounds.width - PositionTolerancePx
            }
        }
        assertTrue(
            "No frame contained both drawer scenes for comparison",
            framesWithBothScenes > 0
        )
        assertTrue(
            "Drawer content never scaled behind the foreground",
            observedRecededContent
        )
        assertTrue(
            "Foreground drawer did not complete a vertical enter or exit path: " +
                "min=$minimumForegroundTop max=$maximumForegroundTop",
            maximumForegroundTop - minimumForegroundTop > PositionTolerancePx
        )
    }

    private companion object {
        const val ListSceneTag = "host_list_scene"
        const val DetailSceneTag = "host_detail_scene"
        const val ListBackgroundTag = "host_list_background"
        const val DetailBackgroundTag = "host_detail_background"
        const val MaxSampledFrames = 60
        const val PositionTolerancePx = 1f
    }
}
