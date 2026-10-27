package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class NowPlayingLyricsCoverRegistrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(false)
    private var ownerMounted by mutableStateOf(true)
    private var coverMounted by mutableStateOf(true)
    private val parent = mutableStateOf<SharedCoverFlightState?>(null)
    private val scale = mutableFloatStateOf(1f)
    private val offset = mutableFloatStateOf(0f)
    private val rootToken = Any()
    private val coverToken = Any()
    private val rowToken = Any()
    private val headerToken = Any()
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var host: LayoutCoordinates
    private lateinit var owner: LayoutCoordinates
    private lateinit var cover: LayoutCoordinates
    private lateinit var row: LayoutCoordinates
    private lateinit var header: LayoutCoordinates

    private fun setHost(parentFlight: SharedCoverFlightState? = null, registerRoot: Boolean = true) {
        parent.value = parentFlight
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                transition = updateTransition(selected, label = "registration_host")
                val hostFade = transition.animateFloat(transitionSpec = { tween(400) }, label = "host_motion") {
                    if (it) 1f else 0f
                }
                flight = remember(transition) { NowPlayingLyricsCoverFlight(transition, parent) }
                Box(Modifier.requiredSize(400.dp, 800.dp)
                    .graphicsLayer { alpha = 1f - 0.1f * hostFade.value }
                    .onGloballyPositioned { host = it }) {
                    if (ownerMounted) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { owner = it }) {
                            if (coverMounted) {
                                Box(Modifier.offset(24.dp, 200.dp).size(320.dp)
                                    .onGloballyPositioned { cover = it })
                            }
                            Box(Modifier.offset(16.dp, 32.dp).size(368.dp, 64.dp)
                                .onGloballyPositioned { row = it })
                            Box(Modifier.offset(80.dp, 32.dp).size(64.dp)
                                .onGloballyPositioned { header = it })
                        }
                    }
                }
            }
        }
        frame()
        composeRule.runOnIdle {
            if (registerRoot) flight.reportRoot(rootToken, owner, 24f, 10f, true)
            flight.reportCover(coverToken, cover)
            flight.reportRow(rowToken, row)
            flight.reportHeader(headerToken, header, scale, offset, 64f)
        }
    }

    @Test
    fun onlyCurrentRootMeasurementsCanHideEndpointsOrExportTheActiveParentFrame() {
        val parentFlight = SharedCoverFlightState()
        setHost(parentFlight)
        startFlight()
        composeRule.runOnIdle {
            assertEquals(CoverBounds, flight.bounds)
            assertFalse(flight.shouldDraw)
            assertFalse(flight.hidesEndpoints)
            assertNull(parentFlight.targetFrameIn(host))
            flight.bindAnimation(mutableStateOf(MiddleBounds), navigating = false)
            assertEquals(CoverBounds, flight.bounds)
            flight.bindAnimation(mutableStateOf(Rect.Zero), navigating = true)
            assertEquals(CoverBounds, flight.bounds)
            flight.bindAnimation(mutableStateOf(MiddleBounds), navigating = true)
            assertEquals(MiddleBounds, flight.bounds)

            flight.reportMeasurement(Any(), MiddleBounds)
            assertFalse("旧图层的测量不能隐藏当前端点", flight.shouldDraw)
            assertNull(parentFlight.targetFrameIn(host))
            flight.reportMeasurement(rootToken, null)
            assertFalse(flight.shouldDraw)
            flight.reportMeasurement(rootToken, MiddleBounds)
            assertTrue(flight.shouldDraw)
            assertTrue(flight.hidesEndpoints)
            assertEquals(MiddleBounds, parentFlight.targetFrameIn(host)?.bounds)

            val replacement = Any()
            flight.reportRoot(replacement, owner, 24f, 10f, false)
            assertFalse("替换根后必须等待它自己的测量", flight.shouldDraw)
            assertNull(parentFlight.targetFrameIn(host))
            flight.detachRoot(rootToken)
            flight.reportMeasurement(replacement, MiddleBounds)
            assertTrue("旧根清理不能删除新注册", flight.shouldDraw)
            assertEquals(MiddleBounds, parentFlight.targetFrameIn(host)?.bounds)
            flight.detachRoot(replacement)
            assertFalse(flight.shouldDraw)
            assertNull(parentFlight.targetFrameIn(host))
        }
    }

    @Test
    fun parentOwnershipAndParentReplacementLeaveExactlyOneRegisteredDrawingOwner() {
        val first = SharedCoverFlightState()
        val replacement = SharedCoverFlightState()
        setHost(first)
        composeRule.runOnIdle {
            val resting = requireNotNull(first.targetFrameIn(host))
            assertEquals(CoverBounds, resting.bounds)
            assertTrue(resting.continuousCorners)
        }
        startFlight()
        composeRule.runOnIdle {
            flight.reportMeasurement(rootToken, MiddleBounds)
            assertTrue(flight.shouldDraw)
            val artwork = ImageBitmap(2, 2)
            first.reportSource(Rect(20f, 700f, 60f, 740f), 10f, artwork)
            first.beginLinked(artwork)
            assertTrue("父层必须满足真实接管条件", first.hidesCovers)
            assertFalse(flight.shouldDraw)
            assertTrue(flight.hidesEndpoints)
            first.endLinked()
            assertTrue(flight.shouldDraw)

            parent.value = replacement
            flight.reportRoot(rootToken, owner, 24f, 10f, true)
            assertNull("替换父层必须释放旧 provider", first.targetFrameIn(host))
            assertEquals(MiddleBounds, replacement.targetFrameIn(host)?.bounds)
            parent.value = null
            flight.reportRoot(rootToken, owner, 24f, 10f, true)
            assertNull(replacement.targetFrameIn(host))
            assertTrue("没有父层时子飞行层继续负责绘制", flight.shouldDraw)
        }
    }

    @Test
    fun foreignAndDetachedCoordinatesCannotReplaceTheLastUsableEndpointGeometry() {
        val parentFlight = SharedCoverFlightState()
        setHost(parentFlight)
        startFlight()
        composeRule.runOnIdle {
            flight.reportMeasurement(rootToken, MiddleBounds)
            val detached = mock(LayoutCoordinates::class.java)
            flight.reportRoot(Any(), detached, 24f, 10f, true)
            flight.reportCover(Any(), detached)
            assertTrue("忽略失效注册后当前根仍可绘制", flight.shouldDraw)
            val foreign = mock(LayoutCoordinates::class.java)
            `when`(foreign.isAttached).thenReturn(true)
            flight.reportCover(Any(), foreign)
            assertEquals("跨根坐标不能污染端点缓存", CoverBounds, flight.targetBounds(false))
            flight.reportCover(coverToken, cover)
            coverMounted = false
        }
        awaitFrameCommit { !cover.isAttached }
        composeRule.runOnIdle {
            assertFalse(cover.isAttached)
            flight.reportRoot(rootToken, owner, 24f, 10f, true)
            assertEquals(CoverBounds, flight.targetBounds(false))
            ownerMounted = false
        }
        awaitFrameCommit { !owner.isAttached }
        composeRule.runOnIdle {
            assertFalse("物理卸载检查仍须处于转场中", transition.currentState)
            assertTrue(transition.targetState)
            assertFalse(owner.isAttached)
            assertFalse("物理卸载根坐标后不能继续隐藏端点", flight.shouldDraw)
            assertNull(parentFlight.targetFrameIn(host))
        }
    }

    @Test
    fun headerUsesRegisteredFallbackForInvalidAnimationAndRetainsGeometryAcrossIndependentRowDisposal() {
        setHost()
        composeRule.runOnIdle {
            assertEquals(HeaderBounds, flight.targetBounds(true))
            scale.floatValue = Float.NaN
            assertEquals(HeaderBounds, flight.targetBounds(true))
            scale.floatValue = 0f
            assertEquals(HeaderBounds, flight.targetBounds(true))
            scale.floatValue = 0.6f
            offset.floatValue = Float.NaN
            assertEquals(HeaderBounds, flight.targetBounds(true))
            offset.floatValue = -8f
            val visible = flight.targetBounds(true)
            assertEquals(38.4f, visible.width, 0.01f)
            flight.detachRow(Any())
            offset.floatValue = -4f
            assertEquals(60f, flight.targetBounds(true).center.y, 0.01f)
            offset.floatValue = -8f
            flight.detachRow(rowToken)
            scale.floatValue = 1f
            offset.floatValue = 0f
            assertEquals(visible, flight.targetBounds(true))
            flight.reportRow(rowToken, row)
            assertEquals("row 单独重建不能恢复已解除的 header 动画绑定", visible, flight.targetBounds(true))

            val replacement = Any()
            flight.reportHeader(replacement, header, scale, offset, 64f)
            flight.detachHeader(headerToken)
            scale.floatValue = 0.75f
            val current = flight.targetBounds(true)
            assertEquals(48f, current.width, 0.01f)
            flight.detachHeader(replacement)
            scale.floatValue = 0f
            assertEquals(current, flight.targetBounds(true))
        }
    }

    @Test
    fun attachedEndpointsWaitForAnAttachedRootBeforeBecomingFlightGeometry() {
        val parentFlight = SharedCoverFlightState()
        setHost(parentFlight, registerRoot = false)
        composeRule.runOnIdle {
            assertFalse(flight.hasGeometry)
            assertEquals(Rect.Zero, flight.targetBounds(false))
            assertNull(flight.bounds)
        }
        startFlight()
        composeRule.runOnIdle {
            flight.reportMeasurement(rootToken, MiddleBounds)
            assertFalse(flight.shouldDraw)
            flight.reportRoot(rootToken, owner, 24f, 10f, false)
            assertTrue(flight.hasGeometry)
            assertTrue(flight.shouldDraw)
            assertEquals(MiddleBounds, parentFlight.targetFrameIn(host)?.bounds)
        }
    }

    private fun startFlight() {
        composeRule.runOnIdle { selected = true }
        awaitFrameCommit { transition.targetState }
        composeRule.runOnIdle {
            assertFalse(transition.currentState)
            assertTrue("夹具必须处于真实 Host 转场中", transition.targetState)
        }
    }

    private fun awaitFrameCommit(committed: () -> Boolean) {
        val deadline = composeRule.mainClock.currentTime + 160L
        do {
            frame()
        } while (!committed() && composeRule.mainClock.currentTime < deadline)
    }

    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private companion object {
        val CoverBounds = Rect(24f, 200f, 344f, 520f)
        val HeaderBounds = Rect(80f, 32f, 144f, 96f)
        val MiddleBounds = Rect(100f, 110f, 260f, 270f)
    }
}
