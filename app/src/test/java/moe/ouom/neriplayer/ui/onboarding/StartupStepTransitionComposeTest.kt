package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class StartupStepTransitionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun advance(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    @Test
    fun `step page stays pinned to the top while the content area grows`() {
        var target by mutableIntStateOf(0)
        var areaHeight by mutableStateOf(400.dp)
        val tops = mutableListOf<Float>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(Modifier.fillMaxWidth().height(areaHeight)) {
                updateTransition(target, label = "step").AnimatedContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { tops += it.positionInRoot().y },
                    transitionSpec = { startupStepContentTransform(forward = targetState > initialState, clean = true) }
                ) { step -> Box(Modifier.fillMaxSize().testTag("step_$step")) }
            }
        }
        advance(500L)
        tops.clear()

        // 离开播放预览步骤时迷你播放器收起, 内容区随之变高
        target = 1
        areaHeight = 480.dp
        repeat(30) { advance(16L) }

        assertTrue("切换过程中应持续测得位置", tops.isNotEmpty())
        assertTrue("内容区变高时步骤页不能被居中下移: $tops", tops.all { it == 0f })
    }

    @Test
    fun `interrupting a step switch never snaps the page alpha`() {
        var target by mutableIntStateOf(0)
        val firstPageAlpha = mutableListOf<Float>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            updateTransition(target, label = "step").AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { startupStepContentTransform(forward = targetState > initialState, clean = true) }
            ) { index ->
                val alpha = rememberStartupStepAlpha(clean = true)
                if (index == 0) firstPageAlpha += alpha.value
                Box(Modifier.fillMaxSize())
            }
        }
        advance(500L)
        assertEquals(1f, firstPageAlpha.last(), 0.001f)

        target = 1
        advance(48L)
        val interrupted = firstPageAlpha.last()
        assertTrue("旧页应已开始淡出: $interrupted", interrupted < 1f)

        // 新页还没淡入就点了返回, 旧页重新进场
        target = 0
        firstPageAlpha.clear()
        repeat(40) { advance(16L) }

        // 打断时会带着原来的速度再走一两帧, 小幅回落正常; 不能跳回 0 再重新淡入
        assertTrue(
            "打断后不能先跳到接近 0: interrupted=$interrupted $firstPageAlpha",
            firstPageAlpha.all { it >= interrupted * 0.6f }
        )
        firstPageAlpha.zipWithNext { previous, next ->
            assertTrue("单帧透明度不能突变: $firstPageAlpha", kotlin.math.abs(next - previous) <= 0.15f)
        }
        assertEquals(1f, firstPageAlpha.last(), 0.001f)
    }
}
