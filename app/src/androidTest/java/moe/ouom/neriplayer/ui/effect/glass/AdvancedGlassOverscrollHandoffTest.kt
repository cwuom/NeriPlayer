package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AdvancedGlassOverscrollHandoffTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var effect: OverscrollEffect
    private lateinit var offset: MutableState<Float>
    private lateinit var scope: CoroutineScope
    private val mounted = mutableStateOf(true)

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @After
    fun restoreAnimationClock() {
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun strongTopReverseFlingHandsOffOnlyAfterTheVisiblePullIsGone() {
        verifyStrongReverse(top = true)
    }

    @Test
    fun strongBottomReverseFlingHandsOffOnlyAfterTheVisiblePullIsGone() {
        verifyStrongReverse(top = false)
    }

    @Test
    fun weakTopReverseFlingSpendsItsMomentumOnTheVisiblePull() {
        verifyWeakReverse(top = true)
    }

    @Test
    fun weakBottomReverseFlingSpendsItsMomentumOnTheVisiblePull() {
        verifyWeakReverse(top = false)
    }

    @Test
    fun newDragCancelsThePendingHandoffWithoutResettingTheVisiblePull() {
        showActivePull(top = true)
        val release = release(velocityFactor = 40f)
        advanceFrames(1)
        composeRule.runOnIdle {
            val before = offset.value
            assertTrue(before > 1f)
            effect.applyToScroll(Offset(0f, 8f), NestedScrollSource.UserInput) { Offset.Zero }
            assertTrue("interrupting the return must not reset the visible pull", offset.value >= before - 1f)
        }
        advanceFrames(12)
        composeRule.runOnIdle {
            assertEquals("the cancelled release must not start an old fling", 0, release.calls)
            assertTrue(requireNotNull(release.job).isCancelled)
            scope.launch { effect.applyToFling(Velocity.Zero) { it } }
        }
        finishAnimations()
        composeRule.runOnIdle { assertFalse(effect.isInProgress) }
    }

    @Test
    fun newDragAlsoCancelsAnAlreadyStartedHandoff() {
        showActivePull(top = true)
        val callbackGate = CompletableDeferred<Unit>()
        val release = release(velocityFactor = 40f) { callbackGate.await() }
        try {
            advanceFrames(12)
            composeRule.runOnIdle {
                assertEquals(1, release.calls)
                assertTrue(requireNotNull(release.job).isActive)
                effect.applyToScroll(Offset(0f, -8f), NestedScrollSource.UserInput) { it }
            }
            advanceFrames(2)
            composeRule.runOnIdle {
                assertTrue("the new drag must own the entire old release", requireNotNull(release.job).isCancelled)
                assertEquals(1, release.calls)
                assertEquals(0f, offset.value, 1f)
            }
        } finally {
            callbackGate.complete(Unit)
            release.job?.cancel()
        }
    }

    @Test
    fun detachingTheNodeCancelsThePendingHandoff() {
        showActivePull(top = true)
        val release = release(velocityFactor = 40f)
        advanceFrames(1)
        composeRule.runOnIdle { mounted.value = false }
        advanceFrames(12)
        composeRule.runOnIdle {
            assertEquals(0, release.calls)
            assertTrue(requireNotNull(release.job).isCancelled)
            assertEquals(0f, offset.value, 0f)
        }
    }

    @Test
    fun disablingAnimatorScaleDoesNotDiscardStrongFlingMomentum() {
        verifyStrongReverse(top = true, context = FixedMotionDurationScale(0f))
    }

    @Test
    fun aLongFrameNeverHandsVelocityBackTowardTheOriginalEdge() {
        showActivePull(top = true)
        val release = release(velocityFactor = 40f, context = JumpingFrameClock())
        finishAnimations()
        composeRule.runOnIdle {
            assertEquals(1, release.calls)
            assertEquals("a spring sampled after its turnaround has no outward handoff", 0f, requireNotNull(release.forwarded).y, 0f)
            assertEquals(0f, release.offsetAtCall, 1f)
            assertFalse(effect.isInProgress)
        }
    }

    private fun verifyStrongReverse(top: Boolean, context: CoroutineContext = EmptyCoroutineContext) {
        showActivePull(top)
        var previousDistance = 0f
        composeRule.runOnIdle { previousDistance = abs(offset.value) }
        val release = release(velocityFactor = 40f, context = context)
        composeRule.runOnIdle {
            assertEquals("release must not immediately fling underneath a visible pull", 0, release.calls)
            assertEquals(previousDistance, abs(offset.value), 0.01f)
        }
        repeat(12) {
            advanceFrames(1)
            composeRule.runOnIdle {
                val distance = if (top) offset.value else -offset.value
                assertTrue("return must not cross into the other edge", distance >= -1f)
                assertTrue("reverse return must continuously reduce its visible pull", distance <= previousDistance + 0.01f)
                previousDistance = distance
                if (release.calls > 0) assertEquals(0f, release.offsetAtCall, 1f)
            }
        }
        finishAnimations()
        composeRule.runOnIdle {
            val forwarded = requireNotNull(release.forwarded)
            assertEquals(1, release.calls)
            assertTrue("handoff must continue away from the original edge", forwarded.y * release.incoming.y > 0f)
            assertTrue("reclaiming the pull must consume some of the incoming momentum", abs(forwarded.y) < abs(release.incoming.y))
            assertFalse(effect.isInProgress)
        }
    }

    private fun verifyWeakReverse(top: Boolean) {
        showActivePull(top)
        val release = release(velocityFactor = 2f)
        composeRule.runOnIdle { assertEquals(0, release.calls) }
        finishAnimations()
        composeRule.runOnIdle {
            assertEquals("even an exhausted fling must dispatch its completion", 1, release.calls)
            assertEquals(0f, requireNotNull(release.forwarded).y, 0f)
            assertEquals(0f, release.offsetAtCall, 1f)
            assertFalse(effect.isInProgress)
        }
    }

    private fun release(
        velocityFactor: Float,
        context: CoroutineContext = EmptyCoroutineContext,
        onHandoff: suspend () -> Unit = {}
    ): Release {
        lateinit var release: Release
        composeRule.runOnIdle {
            release = Release(Velocity(0f, -offset.value * velocityFactor))
            release.job = scope.launch(context) {
                effect.applyToFling(release.incoming) { available ->
                    release.calls++
                    release.forwarded = available
                    release.offsetAtCall = offset.value
                    onHandoff()
                    available
                }
            }
        }
        return release
    }

    private fun showActivePull(top: Boolean) {
        composeRule.setContent {
            effect = remember { AdvancedGlassOverscrollFactory.createOverscrollEffect() }
            offset = remember { mutableStateOf(0f) }
            scope = rememberCoroutineScope()
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (top) 0 else 99)
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalAdvancedGlassOverscrollBackdrop provides AdvancedGlassOverscrollBackdrop(Color.Blue, offset)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(280.dp).height(320.dp),
                        overscrollEffect = effect
                    ) {
                        items(100) { Box(Modifier.width(280.dp).height(48.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            effect.applyToScroll(
                Offset(0f, if (top) 10_000f else -10_000f),
                NestedScrollSource.UserInput
            ) { Offset.Zero }
            assertTrue("the real overscroll node must be attached", abs(offset.value) > 1f)
        }
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

    private class Release(val incoming: Velocity) {
        var calls = 0
        var forwarded: Velocity? = null
        var offsetAtCall = Float.NaN
        var job: Job? = null
    }

    private class FixedMotionDurationScale(override val scaleFactor: Float) : MotionDurationScale

    private class JumpingFrameClock : MonotonicFrameClock {
        private var timeNanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val frame = timeNanos
            timeNanos += 250_000_000L
            return onFrame(frame)
        }
    }
}
