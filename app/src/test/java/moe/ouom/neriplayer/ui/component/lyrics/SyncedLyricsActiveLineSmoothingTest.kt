package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeRuntimeApi::class)
class SyncedLyricsActiveLineSmoothingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabledProgressAnimationDoesNotRecomposeForUnusedSmoothingFrames() {
        assertSmoothingCompositions(animateProgress = false)
    }

    @Test
    fun enabledProgressAnimationStillRunsItsSmoothingFrames() {
        assertSmoothingCompositions(animateProgress = true)
    }

    private fun assertSmoothingCompositions(animateProgress: Boolean) {
        val previousRecomposers = Recomposer.runningRecomposers.value
        var positionMs by mutableLongStateOf(1_000L)
        val committedPositionMs = AtomicLong(Long.MIN_VALUE)
        val line = LyricEntry("A lyric line", startTimeMs = 0L, endTimeMs = 2_000L)
        composeRule.setContent {
            val targetPositionMs = positionMs
            SyncedLyricsActiveLine(
                line = line,
                currentTimeMs = targetPositionMs,
                activeColor = Color.White,
                inactiveColor = Color.Gray,
                fontSize = 20.sp,
                isPlaying = false,
                interpolatePlaybackPosition = false,
                animateProgress = animateProgress
            )
            SideEffect { committedPositionMs.set(targetPositionMs) }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        val counter = CompositionCounter()
        val compositionHandles = mutableMapOf<ObservableComposition, CompositionObserverHandle>()
        val registration = composeRule.runOnIdle {
            val running = Recomposer.runningRecomposers.value
            val recomposer = (running - previousRecomposers).singleOrNull() ?: running.single()
            checkNotNull(recomposer.observe(object : CompositionRegistrationObserver {
                override fun onCompositionRegistered(composition: ObservableComposition) {
                    compositionHandles[composition] = composition.setObserver(counter)
                }

                override fun onCompositionUnregistered(composition: ObservableComposition) {
                    compositionHandles.remove(composition)?.dispose()
                }
            })) { "测试必须观察真实 Compose 组合过程" }
        }

        try {
            composeRule.runOnIdle { positionMs = 1_080L }
            // 等真实目标提交后再计数，快照通知不保证在固定的下一帧内送达
            val targetCommitDeadlineMs = composeRule.mainClock.currentTime + 1_000L
            while (committedPositionMs.get() != 1_080L &&
                composeRule.mainClock.currentTime < targetCommitDeadlineMs
            ) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            assertEquals("目标参数必须先提交", 1_080L, committedPositionMs.get())
            val afterTargetUpdate = counter.count.get()

            composeRule.mainClock.advanceTimeBy(160L)
            composeRule.waitForIdle()
            val extraCompositions = counter.count.get() - afterTargetUpdate
            if (animateProgress) {
                assertTrue("启用的歌词进度动画必须继续过渡", extraCompositions > 0)
            } else {
                assertEquals("禁用进度动画不应组合未消费的平滑帧", 0, extraCompositions)
            }
            composeRule.onAllNodesWithText(line.text).assertCountEquals(2)
        } finally {
            composeRule.runOnIdle {
                registration.dispose()
                compositionHandles.values.forEach(CompositionObserverHandle::dispose)
                compositionHandles.clear()
            }
        }
    }

    private class CompositionCounter : CompositionObserver {
        val count = AtomicInteger()

        override fun onBeginComposition(composition: ObservableComposition) {
            count.incrementAndGet()
        }

        override fun onScopeEnter(scope: RecomposeScope) = Unit

        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit

        override fun onScopeExit(scope: RecomposeScope) = Unit

        override fun onEndComposition(composition: ObservableComposition) = Unit

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit

        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }
}
