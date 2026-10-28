package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.screen.nowplaying.LocalNowPlayingLyricsBoundsTransform
import moe.ouom.neriplayer.ui.screen.nowplaying.LocalNowPlayingLyricsEnterTransition
import moe.ouom.neriplayer.ui.screen.nowplaying.LocalNowPlayingLyricsExitTransition
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingLyricsSharedMotion
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingLyricsBackPreviewEasing
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingLyricsSizeTransform
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLyricsCoverFlight
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLyricsCoverFlightLayer
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingCoverFlightEndpoint
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingLyricsCoverFlightEndpoint
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingLyricsCoverFlightRow
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.rememberNowPlayingLyricsCoverFlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostPredictiveCoverGeometryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(true)
    private var previewAnimationActive by mutableStateOf(false)
    private var previewObserver by mutableStateOf("initial")
    private val headerScale = mutableFloatStateOf(1f)
    private val headerOffset = mutableFloatStateOf(0f)
    private val backs = mutableListOf<Boolean>()
    private val previewNotifications = mutableListOf<PreviewNotification>()
    private lateinit var transition: Transition<Boolean>
    private lateinit var appliedProgress: State<Float>
    private var consumedRawProgress = Float.NaN
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var root: LayoutCoordinates
    private lateinit var artwork: LayoutCoordinates

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun setHost(withSharedSibling: Boolean = true, initialLyrics: Boolean = true) {
        selected = initialLyrics
        composeRule.setContent {
            NowPlayingLyricsSharedMotion(previewAnimationActive) {
                CompositionLocalProvider(
                    LocalPredictiveBackPreviewEnabled provides true,
                    LocalDensity provides Density(1f)
                ) {
                    val observer = previewObserver
                    transition = rememberHostPredictiveBackTransition(
                        targetState = selected,
                        backEnabled = selected,
                        backTargetState = false,
                        onBack = { seeked -> backs += seeked; selected = false },
                        label = "predictive_cover_geometry",
                        previewEasing = Easing { raw ->
                            consumedRawProgress = raw
                            NowPlayingLyricsBackPreviewEasing.transform(raw)
                        },
                        cancelMinimumDurationMillis = MinimumCancelMillis,
                        previewSettleEasing = EaseOut,
                        onPreviewAnimationActiveChanged = { active ->
                            previewNotifications += PreviewNotification(observer, active)
                            previewAnimationActive = active
                        },
                        primePreviewAtZero = true
                    )
                    appliedProgress = transition.animateFloat(
                        transitionSpec = { tween(DurationMillis, easing = LinearEasing) },
                        label = "predictive_cover_host_fraction"
                    ) { if (it) 0f else 1f }
                    flight = rememberNowPlayingLyricsCoverFlight(
                        transition, predictiveBackPreviewActive = previewAnimationActive
                    )
                    Box(Modifier.requiredSize(400.dp, 800.dp).onGloballyPositioned { root = it }) {
                        if (withSharedSibling) {
                            SharedTransitionLayout(Modifier.fillMaxSize()) {
                                transition.AnimatedContent(
                                    transitionSpec = {
                                        (fadeIn(tween(DurationMillis, easing = LinearEasing)) togetherWith
                                            fadeOut(tween(DurationMillis, easing = LinearEasing)))
                                            .using(nowPlayingLyricsSizeTransform(previewAnimationActive))
                                    }
                                ) { lyrics ->
                                    Box(Modifier.requiredSize(
                                        if (lyrics) 400.dp else 360.dp,
                                        if (lyrics) 200.dp else 640.dp
                                    )) {
                                        Box(
                                            Modifier.offset(if (lyrics) 144.dp else 24.dp,
                                                if (lyrics) 48.dp else 560.dp)
                                                .requiredSize(160.dp, 24.dp)
                                                .sharedElement(
                                                    rememberSharedContentState("fixture_artist"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    boundsTransform = LocalNowPlayingLyricsBoundsTransform.current
                                                )
                                                .background(Color.Red)
                                        )
                                        Box(
                                            Modifier.offset(24.dp, if (lyrics) 96.dp else 600.dp)
                                                .requiredSize(if (lyrics) 100.dp else 160.dp, 28.dp)
                                                .sharedBounds(
                                                    rememberSharedContentState("fixture_progress"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    boundsTransform = LocalNowPlayingLyricsBoundsTransform.current,
                                                    enter = LocalNowPlayingLyricsEnterTransition.current,
                                                    exit = LocalNowPlayingLyricsExitTransition.current
                                                )
                                                .background(Color.Blue)
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.offset(24.dp, 200.dp).size(320.dp)
                            .nowPlayingCoverFlightEndpoint(flight))
                        Row(
                            Modifier.offset(16.dp, 32.dp).width(368.dp).height(64.dp)
                                .nowPlayingLyricsCoverFlightRow(flight),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width(64.dp))
                            Box(Modifier.size(64.dp).nowPlayingLyricsCoverFlightEndpoint(
                                flight, headerScale, headerOffset
                            ))
                        }
                        NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                            Box(modifier.onGloballyPositioned { artwork = it })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        assertRect(if (initialLyrics) Header else Cover, requireNotNull(flight.bounds))
    }

    @Test
    fun `edge preview visibly decelerates before the finger is released`() {
        setHost()
        startGesture()
        val frames = listOf(0.5f, 0.65f, 0.8f, 0.95f).map { raw ->
            seek(raw)
            assertMeasuredArtworkMatchesFlight()
            snapshot()
        }
        val middleTravel = rectDistance(frames[0].bounds, frames[1].bounds)
        val endingTravel = rectDistance(frames[2].bounds, frames[3].bounds)
        println("EdgePreviewGeometry middle=$middleTravel end=$endingTravel frames=$frames")
        assertTrue("边缘返回在松手前就应减速 middle=$middleTravel end=$endingTravel frames=$frames",
            endingTravel < middleTravel * 0.5f)
        assertTrue("整段预览仍由手势控制", selected && backs.isEmpty())
        seek(0.8f)
        assertRect(frames[2].bounds, requireNotNull(flight.bounds), 1f)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("非线性预览取消后恢复歌词页") { atRest(true) }
        assertTrue(backs.isEmpty())
    }

    @Test
    fun `releasing after a large edge preview completes without a jump`() =
        assertHighProgressCommit(0.8f)

    @Test
    fun `releasing near the end of an edge preview completes without a jump`() =
        assertHighProgressCommit(0.95f)

    private fun assertHighProgressCommit(raw: Float) {
        setHost()
        startGesture()
        seek(raw)
        val before = snapshot()
        assertMeasuredArtworkMatchesFlight()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("大进度松手后连续抵达封面 raw=$raw") { atRest(false) }
        assertSmooth(frames, growing = true, speedPerMillis = 2f * rectDistance(Header, Cover) / DurationMillis)
        assertMeasuredArtworkMatchesFlight()
        assertRect(Cover, requireNotNull(flight.bounds))
        assertEquals(listOf(true), backs)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `edge preview begins gradually and follows the nonlinear cover path`() {
        setHost()
        startGesture()
        seek(0.2f)
        assertRect(Rect(72.499f, 54.503f, 170.789f, 152.793f), requireNotNull(flight.bounds), 1f)
        assertMeasuredArtworkMatchesFlight()
        seek(0.35f)
        assertRect(Rect(52f, 116f, 244f, 308f), requireNotNull(flight.bounds), 1f)
        assertMeasuredArtworkMatchesFlight()
        assertTrue(backs.isEmpty())
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("取消后必须恢复真实歌词封面") { atRest(true) }
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `finger reversal retraces actual geometry and cancellation keeps the same path`() {
        setHost()
        startGesture()
        seek(0.65f)
        val outward = requireNotNull(flight.bounds)
        seek(0.35f)
        val returning = requireNotNull(flight.bounds)
        assertTrue("回拖必须减小可见封面: $outward -> $returning", returning.width < outward.width)
        assertRect(Rect(52f, 116f, 244f, 308f), returning, 1f)
        seek(0.2f)
        val before = snapshot()
        assertRect(Rect(72.499f, 54.503f, 170.789f, 152.793f), before.bounds, 1f)

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("取消必须完成当前空间片段") { atRest(true) }
        val travel = rectDistance(before.bounds, Header)
        assertSmooth(frames, growing = false, speedPerMillis = 3f * travel / MinimumCancelMillis)
        assertTrue("取消需要保留真实中间帧: $frames",
            frames.any { it.bounds.width > Header.width + 1f && it.bounds.width < before.bounds.width - 1f })
        assertRect(Header, requireNotNull(flight.bounds))
        assertTrue(backs.isEmpty())
        assertFalse(flight.hidesEndpoints)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `committing preview reaches the cover without changing geometry specs mid flight`() {
        setHost()
        startGesture()
        seek(0.35f)
        composeRule.runOnIdle { previewObserver = "settling" }
        advanceFrame()
        val before = snapshot()
        assertRect(Rect(52f, 116f, 244f, 308f), before.bounds, 1f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("提交必须沿当前空间片段抵达封面") {
            atRest(false) && backs == listOf(true)
        }
        assertSmooth(frames, growing = true, speedPerMillis = 2f * rectDistance(Header, Cover) / DurationMillis)
        assertTrue("提交必须保留真实中间帧: $frames",
            frames.any { it.bounds.width > before.bounds.width + 1f && it.bounds.width < Cover.width - 1f })
        assertRect(Cover, requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)
        assertEquals(listOf(PreviewNotification("initial", true), PreviewNotification("settling", false)),
            previewNotifications)
    }

    @Test
    fun `committed preview slows down as the cover reaches its destination`() {
        setHost()
        startGesture()
        seek(0.35f)
        val before = snapshot()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        val frames = listOf(before) + advanceUntil("提交必须减速抵达封面") { atRest(false) }
        val travel = Cover.width - before.bounds.width
        val steps = frames.zipWithNext().filter { (previous, current) ->
            current.bounds.width > previous.bounds.width + 0.01f
        }
        fun meanSpeed(from: Float, until: Float): Double = steps.filter { (previous, _) ->
            (previous.bounds.width - before.bounds.width) / travel in from..until
        }.map { (previous, current) ->
            (current.bounds.width - previous.bounds.width).toDouble() /
                (current.clockMillis - previous.clockMillis)
        }.also { assertTrue("区间必须包含实际运动帧: $frames", it.isNotEmpty()) }.average()

        val middleSpeed = meanSpeed(0.2f, 0.6f)
        val endingSpeed = meanSpeed(0.85f, 0.98f)
        assertTrue("封面落位前必须明显减速 middle=$middleSpeed end=$endingSpeed frames=$frames",
            endingSpeed < middleSpeed * 0.6)
        assertRect(Cover, requireNotNull(flight.bounds))
        assertEquals(listOf(true), backs)
    }

    @Test
    fun `ordinary reversal interrupts the eased completion without snapping the cover`() {
        setHost()
        startGesture()
        seek(0.2f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advanceUntil("先进入松手后的真实中间尺寸") {
            requireNotNull(flight.bounds).width in 180f..280f
        }
        val before = snapshot()
        composeRule.runOnIdle { selected = true }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("收尾中反向必须恢复歌词端点") { atRest(true) }
        assertTrue("反向必须及时更新目标: $frames", frames[2].target)
        assertContinuous(frames, speedPerMillis = 3f * rectDistance(Header, Cover) / DurationMillis)
        assertTrue("反向不能瞬切回歌词封面: $frames",
            frames.any { it.bounds.width > Header.width + 1f && it.bounds.width < before.bounds.width - 1f })
        assertEquals(listOf(true), backs)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `ordinary return during repeated preview reversals still decelerates before landing`() {
        setHost()
        startGesture()
        seek(0.2f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advanceUntil("先进入预测返回松手后的实际收尾") {
            requireNotNull(flight.bounds).width in 180f..240f
        }
        composeRule.runOnIdle { selected = true }
        advanceUntil("收尾中的普通反向必须开始返回歌词页") {
            transition.targetState && requireNotNull(flight.bounds).width in 120f..170f
        }
        assertTrue("反向尚未结束，应复现仍在使用预览规格的窗口", previewAnimationActive)
        val before = snapshot()
        composeRule.runOnIdle { selected = false }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("再度普通返回应立即接管当前封面") {
            !transition.targetState && flight.shouldDraw
        }
        dumpTransitionTree(transition, "repeated_preview_ordinary_return")
        val samples = listOf(snapshot()) + advanceUntil("连续反向后仍应完整抵达封面端点") {
            assertMeasuredArtworkMatchesFlight()
            atRest(false)
        }
        assertContinuous(frames + samples.drop(1),
            speedPerMillis = 3f * rectDistance(Header, Cover) / DurationMillis)
        val minimumWidth = samples.minOf { it.bounds.width }
        val travel = Cover.width - minimumWidth
        assertTrue("再度普通返回必须产生有效移动: $samples", travel > 16f)
        val steps = samples.zipWithNext().filter { (previous, current) ->
            current.bounds.width > previous.bounds.width + 0.01f
        }
        fun meanSpeed(from: Float, until: Float): Double = steps.filter { (previous, current) ->
            (previous.bounds.width - minimumWidth) / travel in from..until &&
                (current.bounds.width - minimumWidth) / travel in from..until &&
                current.bounds.width < Cover.width
        }.map { (previous, current) ->
            (current.bounds.width - previous.bounds.width).toDouble() /
                (current.clockMillis - previous.clockMillis)
        }.also { assertTrue("区间必须包含完整向外运动帧: $samples", it.isNotEmpty()) }.average()

        val middleSpeed = meanSpeed(0.2f, 0.6f)
        val endingSpeed = meanSpeed(0.85f, 0.98f)
        println("RepeatedPreviewOrdinaryReturn middleSpeed=$middleSpeed endingSpeed=$endingSpeed " +
            "frames=$samples")
        assertTrue("预览收尾中的普通返回也应明显减速，不能沿用匀速空间时间线 " +
            "middle=$middleSpeed end=$endingSpeed frames=$samples",
            endingSpeed < middleSpeed * 0.6)
        assertRect(Cover, requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)
        assertEquals(listOf(true), backs)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `preview started during ordinary cover to lyrics animation keeps its visible source through cancellation`() {
        setHost(initialLyrics = false)
        val ordinaryReferenceStart = beginOrdinaryLyricsAnimation()
        val ordinaryReference = listOf(ordinaryReferenceStart) + List(3) { advanceFrame() }
        advanceUntil("普通对照必须先抵达歌词页") { atRest(true) }
        composeRule.runOnIdle { selected = false }
        advanceUntil("下一轮从同一封面端点起步") { atRest(false) }

        val before = beginOrdinaryLyricsAnimation()
        assertRect(ordinaryReferenceStart.bounds, before.bounds, 1f)
        assertMeasuredArtworkMatchesFlight()

        startGesture()
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0f)) }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val handoff = listOf(before) + List(3) { advanceFrame() }
        dumpTransitionTree(transition, "ordinary_handoff")
        assertTrue(previewAnimationActive && !transition.targetState && consumedRawProgress == 0f)
        for ((reference, actual) in ordinaryReference.zip(handoff)) {
            assertEquals(reference.clockMillis - ordinaryReferenceStart.clockMillis,
                actual.clockMillis - before.clockMillis)
            assertRect(reference.bounds, actual.bounds, 1f)
        }
        assertMeasuredArtworkMatchesFlight()

        val cancelling = snapshot()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertRect(cancelling.bounds, requireNotNull(flight.bounds), 1f)
        val cancellation = listOf(cancelling) + advanceUntil("中途取消必须恢复原目标歌词页") {
            atRest(true)
        }
        assertContinuous(cancellation, 3f * rectDistance(Header, Cover) / MinimumCancelMillis)
        assertRect(Header, requireNotNull(flight.bounds))
        assertTrue(backs.isEmpty())
        assertFalse(flight.hidesEndpoints)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `newer finger progress is applied while the first preview is preparing its layout`() {
        setHost()
        startGesture()
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.2f)) }
        advanceFrame()
        seek(0.35f)
        assertRect(Rect(52f, 116f, 244f, 308f), requireNotNull(flight.bounds), 1f)
        assertMeasuredArtworkMatchesFlight()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("连续输入后取消必须恢复歌词页") { atRest(true) }
        assertTrue(backs.isEmpty())
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `cancelling before the first preview is ready leaves no pending navigation`() {
        setHost()
        startGesture()
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.2f)) }
        advanceFrame()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("预备阶段取消必须完整收尾") { atRest(true) }
        repeat(3) { advanceFrame() }
        assertRect(Header, requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)
        assertTrue(backs.isEmpty())
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `committing before the first preview is ready still animates to the cover`() {
        setHost()
        startGesture()
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.35f)) }
        advanceFrame()
        val before = snapshot()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertRect(before.bounds, requireNotNull(flight.bounds), 1f)
        val frames = listOf(before) + advanceUntil("预备阶段提交仍需连续抵达封面") { atRest(false) }
        assertSmooth(frames, growing = true, speedPerMillis = 2f * rectDistance(Header, Cover) / DurationMillis)
        assertTrue("快速松手不能直接瞬切到封面: $frames",
            frames.any { it.bounds.width > before.bounds.width + 1f && it.bounds.width < Cover.width - 1f })
        assertEquals(listOf(true), backs)
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    @Test
    fun `a new preview taking over cancellation keeps the same active animation lifecycle`() {
        setHost()
        startGesture()
        seek(0.35f)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceFrame()
        assertTrue("前一段取消尚未稳定", previewAnimationActive)
        startGesture()
        seek(0.2f)
        assertRect(Rect(72.499f, 54.503f, 170.789f, 152.793f), requireNotNull(flight.bounds), 1f)
        assertEquals("旧收尾不能关闭新手势的预览规格", listOf(true), previewNotifications.map { it.active })
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("重入取消必须由当前片段完成收尾") { atRest(true) }
        repeat(3) { advanceFrame() }
        assertRect(Header, requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)
        assertTrue(backs.isEmpty())
        assertEquals(listOf(true, false), previewNotifications.map { it.active })
    }

    private fun beginOrdinaryLyricsAnimation(): Frame {
        composeRule.runOnIdle { selected = true }
        advanceUntil("普通转场必须先显示真实中间尺寸") {
            transition.targetState && transition.isRunning && flight.shouldDraw &&
                requireNotNull(flight.bounds).width in 180f..280f
        }
        return snapshot()
    }

    private fun startGesture() {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
    }

    private fun seek(raw: Float) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(raw)) }
        advanceUntil("先确认真实 Host 已接收 raw=$raw") {
            transition.currentState && !transition.targetState &&
                consumedRawProgress == raw && flight.shouldDraw &&
                abs(appliedProgress.value - NowPlayingLyricsBackPreviewEasing.transform(raw)) < 0.0001f
        }
        repeat(2) { advanceFrame() }
        println("predictive cover raw=$raw consumed=$consumedRawProgress " +
            "totalDurationNanos=${transition.totalDurationNanos} bounds=${flight.bounds}")
        dumpTransitionTree(transition, "raw=$raw")
        assertTrue("首次真实 seek 必须启用预览空间规格", previewAnimationActive)
        assertTrue(flight.shouldDraw)
    }

    private fun assertMeasuredArtworkMatchesFlight() {
        assertRect(requireNotNull(flight.bounds), root.localBoundingBoxOf(artwork, clipBounds = false), 1f)
    }

    private fun atRest(lyrics: Boolean): Boolean = transition.currentState == lyrics &&
        transition.targetState == lyrics && !transition.isRunning && !previewAnimationActive &&
        rectDistance(requireNotNull(flight.bounds), if (lyrics) Header else Cover) < 0.1f

    private fun advanceUntil(message: String, predicate: () -> Boolean): List<Frame> {
        val frames = mutableListOf<Frame>()
        val deadline = composeRule.mainClock.currentTime + 1500L
        while (!predicate() && composeRule.mainClock.currentTime < deadline) {
            frames += advanceFrame()
        }
        assertTrue("$message frame=${snapshot()} trace=$frames", predicate())
        return frames
    }

    private fun advanceFrame(): Frame {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return snapshot()
    }

    private fun assertSmooth(frames: List<Frame>, growing: Boolean, speedPerMillis: Float) {
        assertContinuous(frames, speedPerMillis)
        for ((previous, current) in frames.zipWithNext()) {
            val delta = current.bounds.width - previous.bounds.width
            assertTrue("收尾不能反向移动 growing=$growing $previous -> $current trace=$frames",
                if (growing) delta >= -1f else delta <= 1f)
            if (current.current != current.target || current.running) {
                assertTrue("转场未稳定前不能切回普通空间规格: $current trace=$frames", current.previewActive)
            }
        }
    }

    private fun assertContinuous(frames: List<Frame>, speedPerMillis: Float) {
        for ((previous, current) in frames.zipWithNext()) {
            val elapsed = (current.clockMillis - previous.clockMillis).toFloat()
            val budget = speedPerMillis * elapsed + 1.5f
            assertTrue("实际封面出现超出时钟预算的跳帧 budget=$budget $previous -> $current trace=$frames",
                rectDistance(previous.bounds, current.bounds) <= budget)
        }
    }

    private fun dumpTransitionTree(node: Transition<*>, path: String) {
        println("animation tree path=$path label=${node.label} current=${node.currentState} " +
            "target=${node.targetState} totalDurationNanos=${node.totalDurationNanos}")
        node.animations.forEach { state ->
            val animation = state.animation
            println("animation path=$path label=${state.label} spec=${state.animationSpec} " +
                "durationNanos=${animation.durationNanos} initial=${animation.initialValue} " +
                "target=${animation.targetValue} value=${state.value}")
        }
        node.transitions.forEachIndexed { index, child ->
            dumpTransitionTree(child, "$path/$index")
        }
    }

    private fun snapshot() = Frame(composeRule.mainClock.currentTime, requireNotNull(flight.bounds),
        previewAnimationActive, transition.currentState, transition.targetState, transition.isRunning)
    private fun rectDistance(a: Rect, b: Rect): Float = maxOf(abs(a.left - b.left), abs(a.top - b.top),
        abs(a.right - b.right), abs(a.bottom - b.bottom))
    private fun assertRect(expected: Rect, actual: Rect, tolerance: Float = 0.1f) {
        assertTrue("expected=$expected actual=$actual tolerance=$tolerance",
            rectDistance(expected, actual) <= tolerance)
    }
    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher
    private fun event(raw: Float) = BackEventCompat(0f, 0f, raw, BackEventCompat.EDGE_LEFT)
    private data class Frame(
        val clockMillis: Long,
        val bounds: Rect,
        val previewActive: Boolean,
        val current: Boolean,
        val target: Boolean,
        val running: Boolean
    )
    private data class PreviewNotification(val observer: String, val active: Boolean)

    private companion object {
        const val DurationMillis = 300
        const val MinimumCancelMillis = 160
        val Header = Rect(80f, 32f, 144f, 96f)
        val Cover = Rect(24f, 200f, 344f, 520f)
    }
}
