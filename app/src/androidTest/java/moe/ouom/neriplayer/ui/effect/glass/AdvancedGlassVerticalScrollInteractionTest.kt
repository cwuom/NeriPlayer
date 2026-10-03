package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class AdvancedGlassVerticalScrollInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var effect: OverscrollEffect
    private lateinit var scrollState: ScrollState
    private lateinit var backdropOffset: MutableState<Float>

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun restoreAnimationClock() {
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun rapidOppositeSwipesInTheMiddleKeepVerticalScrollResponsive() {
        showColumn(startInMiddle = true)
        composeRule.mainClock.autoAdvance = false
        repeat(3) { round ->
            val beforeUp = currentScrollPosition()
            fastSwipe(upward = true)
            advanceFrames(2)
            val afterUp = currentScrollPosition()
            assertTrue(
                "upward swipe $round did not advance the column: $beforeUp -> $afterUp",
                afterUp > beforeUp
            )
            assertNoOverscroll()

            val beforeDown = currentScrollPosition()
            fastSwipe(upward = false)
            advanceFrames(2)
            val afterDown = currentScrollPosition()
            assertTrue(
                "opposite swipe $round did not reverse the column: $beforeDown -> $afterDown",
                afterDown < beforeDown
            )
            assertNoOverscroll()
        }
        repeat(20) {
            advanceFrames(1)
            assertNoOverscroll()
        }
        finishAnimations()
        assertNoOverscroll()
        composeRule.runOnIdle {
            assertTrue(scrollState.canScrollBackward)
            assertTrue(scrollState.canScrollForward)
            assertFalse(scrollState.isScrollInProgress)
        }
    }

    @Test
    fun reversingATopPullRestoresVerticalScrollAndReleaseInertia() {
        showColumn(startInMiddle = false)
        composeRule.mainClock.autoAdvance = false
        val column = composeRule.onNodeWithTag(ColumnTag)
        val bounds = column.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width / 2f, bounds.height * 0.2f)
        val pulled = start.copy(y = bounds.height * 0.75f)
        var pointerDown = false
        try {
            column.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(pulled, delayMillis = 64)
            }
            advanceFrames(2)
            var visibleDistance = 0f
            composeRule.runOnIdle {
                visibleDistance = abs(backdropOffset.value)
                assertTrue("real top pull had no visible offset: $visibleDistance", visibleDistance > 1f)
                assertTrue(effect.isInProgress)
                assertFalse(scrollState.canScrollBackward)
            }

            val beforeReverse = currentScrollPosition()
            val reverse = pulled.copy(y = pulled.y - visibleDistance - 80f)
            column.performTouchInput { moveTo(reverse, delayMillis = 16) }
            advanceFrames(2)
            val afterReverse = currentScrollPosition()
            assertTrue(
                "reverse drag remained trapped at the top: $beforeReverse -> $afterReverse",
                afterReverse > beforeReverse
            )
            assertNoOverscroll()

            column.performTouchInput {
                // 末段连续采样让松手速度反映反向移动
                repeat(6) { sample ->
                    moveTo(reverse.copy(y = reverse.y - 18f * (sample + 1)), delayMillis = 10)
                }
            }
            val beforeRelease = currentScrollPosition()
            column.performTouchInput {
                up()
                pointerDown = false
            }
            advanceFrames(8)
            val afterFling = currentScrollPosition()
            assertTrue(
                "reverse release did not keep scrolling away from the top: " +
                    "$beforeRelease -> $afterFling",
                afterFling > beforeRelease
            )
            assertNoOverscroll()
            finishAnimations()
            assertNoOverscroll()
            composeRule.runOnIdle { assertFalse(scrollState.isScrollInProgress) }
        } finally {
            if (pointerDown) column.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun showColumn(startInMiddle: Boolean) {
        composeRule.setContent {
            effect = remember { AdvancedGlassOverscrollFactory.createOverscrollEffect() }
            val initialPixels = with(LocalDensity.current) {
                if (startInMiddle) (ItemCount / 2 * RowHeightDp).dp.toPx().roundToInt() else 0
            }
            scrollState = rememberScrollState(initial = initialPixels)
            backdropOffset = remember { mutableStateOf(0f) }
            CompositionLocalProvider(
                LocalAdvancedGlassOverscrollBackdrop provides AdvancedGlassOverscrollBackdrop(
                    color = Color.Blue,
                    offsetY = backdropOffset
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .testTag(ColumnTag)
                        .verticalScroll(state = scrollState, overscrollEffect = effect)
                ) {
                    repeat(ItemCount) { index ->
                        Box(Modifier.fillMaxWidth().height(RowHeightDp.dp)) {
                            Text("item $index")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun fastSwipe(upward: Boolean) {
        composeRule.onNodeWithTag(ColumnTag).performTouchInput {
            swipe(
                start = Offset(center.x, height * if (upward) 0.75f else 0.25f),
                end = Offset(center.x, height * if (upward) 0.25f else 0.75f),
                durationMillis = 80
            )
        }
    }

    private fun currentScrollPosition(): Int {
        var position = 0
        composeRule.runOnIdle { position = scrollState.value }
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
        composeRule.runOnIdle {
            assertEquals("column retained a visual offset", 0f, backdropOffset.value, 1f)
            assertFalse("overscroll remains in progress at scroll=${scrollState.value}", effect.isInProgress)
        }
    }

    private companion object {
        const val ItemCount = 100
        const val RowHeightDp = 48
        const val ColumnTag = "advanced-glass-vertical-scroll-column"
    }
}
