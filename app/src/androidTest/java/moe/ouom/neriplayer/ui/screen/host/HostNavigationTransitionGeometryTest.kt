package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
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
            if (listBounds != null && detailBounds != null) {
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

    private companion object {
        const val ListSceneTag = "host_list_scene"
        const val DetailSceneTag = "host_detail_scene"
        const val MaxSampledFrames = 60
        const val PositionTolerancePx = 1f
    }
}
