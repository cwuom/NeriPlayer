package moe.ouom.neriplayer.ui.animation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedCoverVisualTargetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = SharedCoverFlightState()
    private val sourceBounds = Rect(10f, 650f, 50f, 690f)
    private val bitmap = ImageBitmap(2, 2)
    private lateinit var host: LayoutCoordinates
    private lateinit var owner: LayoutCoordinates
    private lateinit var legacy: LayoutCoordinates
    private var frame by mutableStateOf(
        CoverFlightFrame(Rect(8f, 12f, 48f, 52f), 3f, continuousCorners = false)
    )

    private fun setGeometry() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(400.dp, 800.dp)
                    .onGloballyPositioned { host = it }) {
                    Box(Modifier.offset(40.dp, 100.dp).size(200.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = 1.5f
                            scaleY = 1.5f
                            translationX = 10f
                            translationY = 20f
                        }
                        .onGloballyPositioned { owner = it })
                    Box(Modifier.offset(240.dp, 300.dp).size(120.dp)
                        .onGloballyPositioned { legacy = it })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            state.reportSource(sourceBounds, 10f, bitmap)
            state.targetCoordinates = legacy
            state.reportTarget(legacy.boundsInWindow(), 24f)
        }
    }

    @Test
    fun `material frames prefer current visual geometry and map scale and corners into window space`() {
        setGeometry()
        composeRule.runOnIdle { state.reportVisualTarget(Any(), owner) { frame } }
        val targetInHost = Rect(62f, 138f, 122f, 198f)
        val targetInWindow = targetInHost.translate(host.positionInWindow())
        for (fraction in listOf(0f, 0.35f, 1f)) {
            composeRule.runOnIdle { runBlocking { state.progress.snapTo(fraction) } }
            val actual = requireNotNull(state.currentFrame())
            assertRect(lerp(sourceBounds, targetInWindow, fraction), actual.bounds)
            assertEquals(10f + (4.5f - 10f) * fraction, actual.cornerPx, 0.01f)
            assertFalse(actual.continuousCorners)
        }
        val linkedTarget = requireNotNull(state.targetFrameIn(host))
        assertRect(targetInHost, linkedTarget.bounds)
        assertEquals(4.5f, linkedTarget.cornerPx, 0.01f)

        composeRule.runOnIdle {
            frame = CoverFlightFrame(Rect(20f, 30f, 60f, 70f), 10f, continuousCorners = false)
        }
        assertRect(Rect(80f, 165f, 140f, 225f), requireNotNull(state.targetFrameIn(host)).bounds)
        assertRect(Rect(80f, 165f, 140f, 225f).translate(host.positionInWindow()),
            requireNotNull(state.currentFrame()).bounds)
        assertEquals(15f, requireNotNull(state.currentFrame()).cornerPx, 0.01f)
    }

    @Test
    fun `stale visual registration cannot clear the replacement and explicit detach restores legacy defaults`() {
        setGeometry()
        val stale = Any()
        val current = Any()
        composeRule.runOnIdle {
            state.reportVisualTarget(stale, owner) { CoverFlightFrame(Rect(0f, 0f, 20f, 20f), 1f) }
            state.reportVisualTarget(current, owner) { frame }
            state.detachVisualTarget(stale)
            runBlocking { state.progress.snapTo(1f) }
        }
        assertRect(Rect(62f, 138f, 122f, 198f), requireNotNull(state.targetFrameIn(host)).bounds)
        composeRule.runOnIdle { state.detachVisualTarget(current) }

        val fallback = requireNotNull(state.targetFrameIn(host))
        assertRect(Rect(240f, 300f, 360f, 420f), fallback.bounds)
        assertEquals(24f, fallback.cornerPx, 0f)
        assertTrue("旧端点的默认连续圆角保持原行为", fallback.continuousCorners)
        assertRect(legacy.boundsInWindow(), requireNotNull(state.currentFrame()).bounds)
        assertSame(legacy, state.targetCoordinates)
    }

    @Test
    fun `linked ownership supports a visible lyric header without an attached legacy cover`() {
        setGeometry()
        val token = Any()
        composeRule.runOnIdle {
            frame = CoverFlightFrame(Rect(8f, 12f, 48f, 52f), 10f, continuousCorners = false)
            state.reportVisualTarget(token, owner) { frame }
            state.detachTarget()
            state.beginLinked(bitmap)
        }
        assertNull(state.targetCoordinates)
        assertTrue(state.linked)
        assertTrue(state.hidesCovers)
        val header = requireNotNull(state.targetFrameIn(host))
        assertRect(Rect(62f, 138f, 122f, 198f), header.bounds)
        assertEquals(15f, header.cornerPx, 0.01f)
        assertFalse("歌词头部保留普通圆角", header.continuousCorners)
        assertSame(bitmap, state.linkedBitmap)

        composeRule.runOnIdle { state.detachVisualTarget(token) }
        assertFalse("失去全部有效目标后不能继续隐藏真实封面", state.hidesCovers)
        assertNull(state.targetFrameIn(host))
        composeRule.runOnIdle {
            frame = frame.copy(cornerPx = 24f, continuousCorners = true)
            state.reportVisualTarget(Any(), owner) { frame }
        }
        assertTrue(state.hidesCovers)
        val cover = requireNotNull(state.targetFrameIn(host))
        assertEquals(36f, cover.cornerPx, 0.01f)
        assertTrue(cover.continuousCorners)
        composeRule.runOnIdle { state.endLinked() }
        assertFalse(state.hidesCovers)
    }

    @Test
    fun `detached nonfinite and foreign coordinates cannot replace a usable fallback`() {
        setGeometry()
        val detached = mock(LayoutCoordinates::class.java)
        `when`(detached.isAttached).thenReturn(false)
        composeRule.runOnIdle {
            state.reportVisualTarget(Any(), detached) { frame }
            runBlocking { state.progress.snapTo(1f) }
        }
        assertRect(legacy.boundsInWindow(), requireNotNull(state.currentFrame()).bounds)
        composeRule.runOnIdle {
            state.reportVisualTarget(Any(), owner) {
                CoverFlightFrame(Rect(Float.NaN, 0f, 40f, 40f), 10f)
            }
        }
        assertRect(Rect(240f, 300f, 360f, 420f), requireNotNull(state.targetFrameIn(host)).bounds)
        assertRect(legacy.boundsInWindow(), requireNotNull(state.currentFrame()).bounds)
        assertNull(state.targetFrameIn(detached))

        val foreignRoot = mock(LayoutCoordinates::class.java)
        `when`(foreignRoot.isAttached).thenReturn(true)
        composeRule.runOnIdle { state.reportVisualTarget(Any(), owner) { frame } }
        assertNull("不能在没有公共根的布局坐标之间做变换", state.targetFrameIn(foreignRoot))
        assertRect(Rect(62f, 138f, 122f, 198f), requireNotNull(state.targetFrameIn(host)).bounds)
    }

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }
}
