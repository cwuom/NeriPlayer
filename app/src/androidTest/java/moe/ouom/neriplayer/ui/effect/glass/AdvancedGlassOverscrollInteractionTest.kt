package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AdvancedGlassOverscrollInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var effect: OverscrollEffect
    private lateinit var listState: LazyListState
    private lateinit var backdropOffset: MutableState<Float>
    private lateinit var compositionScope: CoroutineScope
    private val flingSamples = mutableListOf<FlingSample>()
    private var rowHeightPx = 0

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun restoreAnimationClock() {
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun rapidOppositeSwipesInTheMiddleKeepScrollingWithoutOverscroll() {
        showList(initialIndex = ItemCount / 2)
        composeRule.mainClock.autoAdvance = false
        repeat(3) {
            val beforeUp = currentScrollPosition()
            fastSwipe(upward = true)
            advanceFrames(2)
            val afterUp = currentScrollPosition()
            assertTrue("upward swipe did not advance the list: $beforeUp -> $afterUp", afterUp > beforeUp)
            assertNoOverscroll()

            val beforeDown = currentScrollPosition()
            fastSwipe(upward = false)
            advanceFrames(2)
            val afterDown = currentScrollPosition()
            assertTrue("opposite swipe did not reverse scrolling: $beforeDown -> $afterDown", afterDown < beforeDown)
            assertNoOverscroll()
        }
        repeat(20) {
            advanceFrames(1)
            assertNoOverscroll()
        }
        finishAnimations()
        composeRule.runOnIdle {
            assertTrue(listState.canScrollBackward)
            assertTrue(listState.canScrollForward)
            assertFalse(listState.isScrollInProgress)
        }
    }

    @Test
    fun pullingTheTopThenImmediatelyReversingRestoresListMovement() {
        verifyBoundaryGesture(top = true)
    }

    @Test
    fun pullingTheBottomThenImmediatelyReversingRestoresListMovement() {
        verifyBoundaryGesture(top = false)
    }

    @Test
    fun saturatedTopPullUsesVisibleDistanceWhenReturningToTheList() {
        verifyVisiblePullRelease(top = true)
    }

    @Test
    fun saturatedBottomPullUsesVisibleDistanceWhenReturningToTheList() {
        verifyVisiblePullRelease(top = false)
    }

    @Test
    fun subpixelScrollRemaindersDoNotAccumulateIntoVisibleOverscroll() {
        showList(initialIndex = ItemCount / 2)
        composeRule.runOnIdle {
            for (direction in listOf(1f, -1f)) {
                repeat(128) {
                    val consumed = effect.applyToScroll(
                        delta = Offset(0f, direction * 8f),
                        source = NestedScrollSource.UserInput,
                        performScroll = { available ->
                            assertEquals(direction * 8f, available.y, 0.01f)
                            Offset(available.x, available.y - direction * 0.25f)
                        }
                    )
                    assertEquals(direction * 7.75f, consumed.y, 0.01f)
                }
                assertNoOverscrollOnUiThread()
            }
        }
    }

    @Test
    fun fastReverseFlingAtTheTopDoesNotCrossIntoTheOppositeEdge() {
        verifyReverseFlingStaysOnOriginalEdge(top = true)
    }

    @Test
    fun fastReverseFlingAtTheBottomDoesNotCrossIntoTheOppositeEdge() {
        verifyReverseFlingStaysOnOriginalEdge(top = false)
    }

    private fun verifyReverseFlingStaysOnOriginalEdge(top: Boolean) {
        showList(initialIndex = if (top) 0 else ItemCount - 1)
        composeRule.mainClock.autoAdvance = false
        val direction = if (top) 1f else -1f
        var forwardedVelocity: Velocity? = null
        composeRule.runOnIdle {
            effect.applyToScroll(
                delta = Offset(0f, direction * 10_000f),
                source = NestedScrollSource.UserInput,
                performScroll = { Offset.Zero }
            )
            assertTrue(effect.isInProgress)
            compositionScope.launch {
                effect.applyToFling(Velocity(0f, -direction * 10_000f)) { available ->
                    forwardedVelocity = available
                    available
                }
            }
        }
        repeat(7) {
            advanceFrames(1)
            composeRule.runOnIdle {
                assertTrue(
                    "return spring crossed into the other edge: ${backdropOffset.value}",
                    direction * backdropOffset.value >= -1f
                )
            }
        }
        composeRule.runOnIdle {
            assertTrue("reverse fling did not reach the list", direction * requireNotNull(forwardedVelocity).y < 0f)
            var forwardedScroll: Offset? = null
            effect.applyToScroll(
                delta = Offset(0f, -direction * (abs(backdropOffset.value) + 40f)),
                source = NestedScrollSource.UserInput,
                performScroll = { available ->
                    forwardedScroll = available
                    available
                }
            )
            assertEquals(-direction * 40f, requireNotNull(forwardedScroll).y, 1f)
            assertNoOverscrollOnUiThread()
        }
        finishAnimations()
        assertNoOverscroll()
    }

    private fun verifyVisiblePullRelease(top: Boolean) {
        showList(initialIndex = if (top) 0 else ItemCount - 1)
        val direction = if (top) 1f else -1f
        composeRule.runOnIdle {
            assertFalse(if (top) listState.canScrollBackward else listState.canScrollForward)
            effect.applyToScroll(
                delta = Offset(0f, direction * 10_000f),
                source = NestedScrollSource.UserInput,
                performScroll = { Offset.Zero }
            )
            val visibleDistance = abs(backdropOffset.value)
            assertTrue("boundary pull was not attached or visible", visibleDistance > 1f)
            assertTrue(effect.isInProgress)
            var forwarded: Offset? = null
            effect.applyToScroll(
                delta = Offset(0f, -direction * (visibleDistance + 40f)),
                source = NestedScrollSource.UserInput,
                performScroll = { available ->
                    forwarded = available
                    available
                }
            )

            assertEquals("invisible drag debt blocked the reverse scroll", -direction * 40f, requireNotNull(forwarded).y, 1f)
            assertNoOverscrollOnUiThread()
        }
    }

    private fun verifyBoundaryGesture(top: Boolean) {
        showList(initialIndex = if (top) 0 else ItemCount - 1)
        composeRule.mainClock.autoAdvance = false
        val list = composeRule.onNodeWithTag(ListTag)
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width / 2f, bounds.height * if (top) 0.2f else 0.8f)
        val pulled = start.copy(y = bounds.height * if (top) 0.75f else 0.25f)
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(pulled, delayMillis = 64)
            }
            advanceFrames(2)
            var visibleDistance = 0f
            composeRule.runOnIdle {
                visibleDistance = abs(backdropOffset.value)
                assertTrue("real boundary drag did not produce overscroll", visibleDistance > 1f)
                assertTrue(effect.isInProgress)
                assertFalse(if (top) listState.canScrollBackward else listState.canScrollForward)
            }
            val beforeReverse = currentScrollPosition()
            val reverse = pulled.copy(y = pulled.y + (if (top) -1f else 1f) * (visibleDistance + 80f))
            list.performTouchInput { moveTo(reverse, delayMillis = 16) }
            advanceFrames(2)
            val afterReverse = currentScrollPosition()
            assertTrue(
                "reverse drag remained trapped at the boundary: $beforeReverse -> $afterReverse",
                if (top) afterReverse > beforeReverse else afterReverse < beforeReverse
            )
            assertNoOverscroll()
            list.performTouchInput {
                // 连续采样反向末段，让松手速度来自反向手势而不是边界拉出的两个离散点
                repeat(6) { sample ->
                    moveTo(
                        reverse.copy(y = reverse.y + (if (top) -1f else 1f) * 18f * (sample + 1)),
                        delayMillis = 10
                    )
                }
            }
            val beforeRelease = currentScrollPosition()
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceFrames(8)
            val afterFling = currentScrollPosition()
            composeRule.runOnIdle {
                val release = requireNotNull(flingSamples.lastOrNull()) {
                    "gesture release never reached applyToFling"
                }
                val direction = if (top) 1f else -1f
                assertTrue(
                    "gesture velocity did not point away from the boundary: $release",
                    direction * release.incoming.y < -200f
                )
                assertTrue(
                    "factory did not forward reverse release velocity: $release",
                    direction * requireNotNull(release.forwarded).y < -200f
                )
            }
            assertTrue(
                "reverse release did not continue scrolling away from the boundary: " +
                    "before=$beforeRelease, after=$afterFling, flings=$flingSamples",
                if (top) afterFling > beforeRelease else afterFling < beforeRelease
            )
            finishAnimations()
            assertNoOverscroll()
            composeRule.runOnIdle { assertFalse(listState.isScrollInProgress) }
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun showList(initialIndex: Int) {
        composeRule.setContent {
            effect = remember {
                RecordingOverscrollEffect(
                    actual = AdvancedGlassOverscrollFactory.createOverscrollEffect(),
                    samples = flingSamples
                )
            }
            listState = remember { LazyListState(firstVisibleItemIndex = initialIndex) }
            backdropOffset = remember { mutableStateOf(0f) }
            compositionScope = rememberCoroutineScope()
            rowHeightPx = with(LocalDensity.current) { 48.dp.toPx().roundToInt() }
            CompositionLocalProvider(
                LocalAdvancedGlassOverscrollBackdrop provides AdvancedGlassOverscrollBackdrop(
                    color = Color.Blue,
                    offsetY = backdropOffset
                )
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp).testTag(ListTag),
                    state = listState,
                    overscrollEffect = effect
                ) {
                    items(count = ItemCount, key = { it }) { index ->
                        Box(Modifier.fillMaxWidth().height(48.dp)) {
                            Text("item $index")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun fastSwipe(upward: Boolean) {
        composeRule.onNodeWithTag(ListTag).performTouchInput {
            swipe(
                start = Offset(center.x, height * if (upward) 0.75f else 0.25f),
                end = Offset(center.x, height * if (upward) 0.25f else 0.75f),
                durationMillis = 80
            )
        }
    }

    private fun currentScrollPosition(): Int {
        var position = 0
        composeRule.runOnIdle {
            position = listState.firstVisibleItemIndex * rowHeightPx + listState.firstVisibleItemScrollOffset
        }
        return position
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun finishAnimations() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun assertNoOverscroll() {
        composeRule.runOnIdle { assertNoOverscrollOnUiThread() }
    }

    private fun assertNoOverscrollOnUiThread() {
        assertEquals("content has a visual offset without a boundary pull", 0f, backdropOffset.value, 1f)
        assertFalse("overscroll remains in progress", effect.isInProgress)
    }

    private data class FlingSample(
        val incoming: Velocity,
        var forwarded: Velocity? = null
    )

    private class RecordingOverscrollEffect(
        private val actual: OverscrollEffect,
        private val samples: MutableList<FlingSample>
    ) : OverscrollEffect by actual {
        override suspend fun applyToFling(
            velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity
        ) {
            val sample = FlingSample(velocity)
            samples += sample
            actual.applyToFling(velocity) { forwarded ->
                sample.forwarded = forwarded
                performFling(forwarded)
            }
        }
    }

    private companion object {
        const val ItemCount = 300
        const val ListTag = "advanced-glass-overscroll-list"
    }
}
