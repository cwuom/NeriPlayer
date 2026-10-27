package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingCoverSourceBadgeBehaviorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val parent = SharedCoverFlightState()
    private val artwork = ImageBitmap(2, 2)
    private var providedParent by mutableStateOf<SharedCoverFlightState?>(null)
    private var mounted by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private var animate by mutableStateOf(true)
    private var source by mutableStateOf<PlaybackSourceType?>(PlaybackSourceType.LOCAL)
    private var useChildFlight by mutableStateOf(false)
    private var lyrics by mutableStateOf(false)
    private lateinit var childFlight: NowPlayingLyricsCoverFlight
    private lateinit var scope: CoroutineScope
    private var inputRevision by mutableStateOf(0)
    private var appliedRevision = -1
    private var placedRevision = -1

    private fun setHost() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f), LocalSharedCoverFlight provides providedParent
            ) {
                MaterialTheme {
                    scope = rememberCoroutineScope()
                    val revision = inputRevision
                    Box(Modifier.requiredSize(320.dp, 160.dp).testTag(HostTag).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                            placedRevision = revision
                        }
                    }) {
                        Box(Modifier.size(1.dp).onGloballyPositioned {
                            parent.targetCoordinates = it
                            parent.reportTarget(it.boundsInWindow(), 0f)
                        })
                        val child = if (useChildFlight) {
                            val transition = updateTransition(lyrics, label = "badge_child")
                            val flight = rememberNowPlayingLyricsCoverFlight(transition)
                            childFlight = flight
                            Box(Modifier.size(80.dp).nowPlayingCoverFlightEndpoint(flight))
                            Row(Modifier.align(Alignment.TopEnd).height(64.dp)
                                .nowPlayingLyricsCoverFlightRow(flight)) {
                                Box(Modifier.size(64.dp).nowPlayingLyricsCoverFlightEndpoint(
                                    flight, remember { mutableStateOf(1f) }, remember { mutableStateOf(0f) }
                                ))
                            }
                            NowPlayingLyricsCoverFlightLayer(flight) { modifier -> Box(modifier) }
                            flight
                        } else null
                        if (mounted) {
                            NowPlayingCoverSourceBadge(
                                show = shown,
                                animate = animate,
                                sourceType = source,
                                modifier = Modifier.align(Alignment.Center).testTag(BadgeTag),
                                coverFlight = child
                            )
                        }
                        SideEffect { appliedRevision = revision }
                    }
                }
            }
        }
        frame()
        awaitInputApplication()
    }

    @Test
    fun firstMountWaitsForAFrameThenScalesNonlinearlyAroundTheLayoutCenter() {
        setHost()
        applyInputs { mounted = true }
        frame()
        val first = badgeBounds()
        advance(160L)
        val intermediate = badgeBounds()
        advance(600L)
        assertEntry(first, intermediate, badgeBounds())
    }

    @Test
    fun linkedParentWithoutBitmapHoldsEntryAndCancelledCollapseStartsANewEntry() {
        providedParent = parent
        setHost()
        applyInputs {
            parent.reportSource(Rect(0f, 0f, 16f, 16f), 0f, null)
            parent.beginLinked(null)
            assertTrue(parent.linked)
            assertFalse(parent.hidesCovers)
            mounted = true
        }
        advance(800L)
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { parent.endLinked() }
        assertFreshEntry()

        applyInputs {
            parent.beginLinked(artwork)
            assertTrue(parent.hidesCovers)
        }
        frame()
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { parent.endLinked() }
        assertFreshEntry()
    }

    @Test
    fun ordinaryParentFlightHidesTheBadgeAndCancellationRestartsItsEntry() {
        providedParent = parent
        mounted = true
        setHost()
        advance(800L)
        assertTrue(badgeBounds().width > 0f)
        applyInputs {
            parent.reportSource(Rect(0f, 0f, 16f, 16f), 0f, artwork)
            parent.open(scope, artwork)
            assertTrue(parent.flying)
            assertFalse(parent.linked)
        }
        advance(160L)
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { parent.cancel() }
        assertFreshEntry()
    }

    @Test
    fun staticBadgeIsImmediateAndShowOrUnknownSourceRemovesItsContent() {
        animate = false
        mounted = true
        setHost()
        val initial = badgeBounds()
        assertTrue(initial.width > 0f)
        advance(160L)
        assertEquals(initial, badgeBounds())
        applyInputs { shown = false }
        frame()
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs {
            shown = true
            source = null
        }
        frame()
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { source = PlaybackSourceType.LOCAL }
        frame()
        frame()
        assertEquals(initial, badgeBounds())
    }

    @Test
    fun actualChildCoverFlightHidesTheBadgeUntilItReturnsTheEndpoint() {
        animate = false
        mounted = true
        useChildFlight = true
        setHost()
        assertFalse(childFlight.hidesEndpoints)
        assertTrue(badgeBounds().width > 0f)
        applyInputs { lyrics = true }
        val deadline = composeRule.mainClock.currentTime + 300L
        while (!childFlight.hidesEndpoints && composeRule.mainClock.currentTime < deadline) frame()
        assertTrue("必须由真实子封面转场接管端点", childFlight.hidesEndpoints)
        frame()
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        advance(1_200L)
        assertFalse(childFlight.hidesEndpoints)
        assertTrue(badgeBounds().width > 0f)
    }

    @Test
    fun replacingTheParentOwnerStopsObservingItsPreviousLinkedFlight() {
        animate = false
        mounted = true
        providedParent = parent
        parent.beginLinked(null)
        setHost()
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()

        val replacement = SharedCoverFlightState()
        applyInputs { providedParent = replacement }
        frame()
        assertTrue("旧父层仍在转场，新父层必须独立决定标签可见性", parent.linked)
        assertTrue(badgeBounds().width > 0f)
        applyInputs { replacement.beginLinked(null) }
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { parent.endLinked() }
        assertTrue(replacement.linked)
        composeRule.onNodeWithTag(BadgeTag).assertDoesNotExist()
        applyInputs { replacement.endLinked() }
        frame()
        assertTrue(badgeBounds().width > 0f)
    }

    private fun assertFreshEntry() {
        frame()
        val first = badgeBounds()
        advance(160L)
        val intermediate = badgeBounds()
        advance(600L)
        assertEntry(first, intermediate, badgeBounds())
    }

    private fun assertEntry(first: Rect, intermediate: Rect, final: Rect) {
        assertEquals("真正可见的首帧必须从零缩放开始", 0f, first.width, 0.001f)
        assertTrue("入场必须完成", final.width > 0f)
        val ratio = intermediate.width / final.width
        assertTrue("160ms 应处于非线性入场中段: ratio=$ratio", ratio > 0.45f && ratio < 0.98f)
        assertEquals(final.center.x, intermediate.center.x, 0.01f)
        assertEquals(final.center.y, intermediate.center.y, 0.01f)
        val host = composeRule.onNodeWithTag(HostTag).fetchSemanticsNode().boundsInRoot
        assertEquals(host.center.x, final.center.x, 0.01f)
        assertEquals(host.center.y, final.center.y, 0.01f)
    }

    private fun badgeBounds(): Rect {
        val badge = composeRule.onNodeWithTag(BadgeTag, useUnmergedTree = true).fetchSemanticsNode()
        val icon = badge.children.single { it.config.contains(SemanticsProperties.ContentDescription) }
        val text = badge.children.single { it.config.contains(SemanticsProperties.Text) }
        // 外层标签位于 graphicsLayer 之前，内部图标和文字的坐标才包含真实缩放
        val iconBounds = icon.boundsInRoot
        val textBounds = text.boundsInRoot
        return Rect(
            minOf(iconBounds.left, textBounds.left), minOf(iconBounds.top, textBounds.top),
            maxOf(iconBounds.right, textBounds.right), maxOf(iconBounds.bottom, textBounds.bottom)
        )
    }

    private fun applyInputs(change: () -> Unit) {
        composeRule.runOnIdle {
            change()
            inputRevision++
        }
        awaitInputApplication()
    }

    private fun awaitInputApplication() {
        val expected = inputRevision
        val deadline = composeRule.mainClock.currentTime + 128L
        // 独立等待夹具输入提交和布局，不用标签的最终可见量决定何时开始断言
        while ((appliedRevision != expected || placedRevision != expected) &&
            composeRule.mainClock.currentTime < deadline
        ) frame()
        assertEquals("夹具输入必须已提交组合", expected, appliedRevision)
        assertEquals("夹具必须已完成这一轮布局", expected, placedRevision)
    }

    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) frame()
    }

    private companion object {
        const val HostTag = "badge-behavior-host"
        const val BadgeTag = "badge-behavior"
    }
}
