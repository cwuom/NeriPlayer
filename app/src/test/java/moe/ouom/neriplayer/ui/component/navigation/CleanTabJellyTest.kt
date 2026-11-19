package moe.ouom.neriplayer.ui.component.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTabJellyTest {
    private val tabWidth = 100f

    @Test
    fun stretchGrowsWithSpeedAndIsCapped() {
        assertEquals(0f, jellyTarget(0f, tabWidth), 0f)
        assertEquals(0.075f, jellyTarget(100f, tabWidth), 1e-6f)
        // 方向不影响拉伸量
        assertEquals(jellyTarget(400f, tabWidth), jellyTarget(-400f, tabWidth), 0f)
        assertEquals(JellyMaxStretch, jellyTarget(100_000f, tabWidth), 0f)
        assertEquals(0f, jellyTarget(500f, 0f), 0f)
    }

    @Test
    fun releasedStretchOvershootsIntoASquashThenSettles() {
        var spring = JellySpring(JellyMaxStretch, 0f)
        var minimum = spring.value
        repeat(120) {
            spring = stepJelly(spring, target = 0f, dtSeconds = 1f / 60f)
            minimum = minOf(minimum, spring.value)
        }
        // 欠阻尼：先越过 0 变成略高略窄，两秒内静止
        assertTrue("jelly should wobble past rest, min=$minimum", minimum < -0.02f)
        assertTrue(minimum >= JellyMaxSquash)
        assertTrue(spring.isAtRest())
    }

    @Test
    fun stepIsStableUnderLongFrames() {
        val spring = stepJelly(JellySpring(0.2f, 0f), target = 0f, dtSeconds = 0.05f)
        assertTrue(spring.value in JellyMaxSquash..JellyMaxStretch)
        assertEquals(JellySpring.Rest, stepJelly(JellySpring.Rest, target = 0f, dtSeconds = 0.016f))
    }

    @Test
    fun jellyScaleOnlyDeformsAlongTheTravel() {
        assertEquals(1f to 1f, jellyScale(0f))
        // 拖动中高度始终不变，手指停下时透镜不会往上弹，读起来不像底栏被抬起
        val (wideX, wideY) = jellyScale(0.2f)
        assertTrue(wideX > 1f)
        assertEquals(1f, wideY, 0f)
        val (narrowX, narrowY) = jellyScale(-0.1f)
        assertTrue(narrowX < 1f)
        assertEquals(1f, narrowY, 0f)
        assertEquals(jellyScale(JellyMaxStretch), jellyScale(5f))
    }
}
