package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegion
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.resolveCurrentAdvancedGlassRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class PlaybackStatsMetricGlassTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val period = mutableStateOf(PlaybackStatsPeriod.DAY)
    private val state = mutableStateOf(page(PlaybackStatsPeriod.DAY, 75))
    private val blurEnabled = mutableStateOf(true)
    private val visible = mutableStateOf(true)
    private lateinit var registry: AdvancedGlassRegionRegistry
    private lateinit var background: AdvancedGlassBackdrop
    private lateinit var content: AdvancedGlassBackdrop
    private var fixtureWindowOrigin = Offset.Zero
    private var density = 1f
    private var fallbackColor = Color.Unspecified
    private var rankingFallbackColor = Color.Unspecified

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun landscapeMetricsUseNativeRoundedGlassAndKeepMasksDuringDateRefresh() {
        assertGlassRefreshAndCleanup(AdvancedBlurQuality.High, DpSize(1280.dp, 800.dp))
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun portraitMetricsUseLocalRoundedGlassAndKeepMasksDuringDateRefresh() {
        assertGlassRefreshAndCleanup(AdvancedBlurQuality.Low, DpSize(800.dp, 1100.dp))
    }

    @Test
    fun narrowTabletWithoutHostKeepsRoundedMetricFallbacks() {
        render(DpSize(600.dp, 900.dp), AdvancedBlurQuality.High, host = false)
        MetricTags.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        assertFallbackPixels()
        assertRoundedCornersLeaveBackdropUnchanged()
        capture("stats-metric-no-host")
    }

    private fun assertGlassRefreshAndCleanup(quality: AdvancedBlurQuality, size: DpSize) {
        render(size, quality, host = true)
        composeRule.waitUntil(2_000) { registry.regions.size == 4 && background.hasActiveBlur }
        val initialRegions = currentRegions()
        assertMetricRegions(initialRegions)
        val initialProviders = registry.regions.map { checkNotNull(it.regionProvider) }.toSet()
        val initialIds = GlassTags.map { composeRule.onNodeWithTag(it, true).fetchSemanticsNode().id }
        val initialBounds = GlassTags.map(::bounds)
        val initialNativeEffect = background.renderEffect
        val initialLocalPlan = background.localBlurPlan
        if (quality == AdvancedBlurQuality.High) {
            assertNotNull(initialNativeEffect)
            assertNull(initialLocalPlan)
        } else {
            assertNotNull(initialLocalPlan)
            assertNull(initialNativeEffect)
        }
        assertFalse("统计卡片只采样背景层", content.hasActiveBlur)
        val blurredContrast = GlassTags.associateWith { metricContrast(it, fixtureImage()) }
        blurredContrast.forEach { (tag, contrast) ->
            assertTrue("$tag 真实玻璃应模糊背景条纹，contrast=$contrast", contrast < 0.35f)
        }
        assertRoundedCornersLeaveBackdropUnchanged(GlassTags)
        capture("stats-metric-${quality.name.lowercase()}")

        composeRule.mainClock.autoAdvance = false
        try {
            listOf(PlaybackStatsPeriod.MONTH, PlaybackStatsPeriod.YEAR).forEachIndexed { index, nextPeriod ->
                composeRule.onNodeWithText(periodLabel(nextPeriod)).performClick()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onNodeWithText(periodLabel(nextPeriod)).assertIsSelected()
                composeRule.onNodeWithTag("statsLoading").assertIsDisplayed()
                repeat(3) {
                    composeRule.mainClock.advanceTimeByFrame()
                    assertEquals(initialIds, GlassTags.map { composeRule.onNodeWithTag(it, true).fetchSemanticsNode().id })
                    assertEquals(initialBounds, GlassTags.map(::bounds))
                    assertMetricRegions(currentRegions())
                    composeRule.runOnIdle {
                        assertEquals(initialProviders, registry.regions.map { checkNotNull(it.regionProvider) }.toSet())
                        assertTrue(background.hasActiveBlur)
                        if (quality == AdvancedBlurQuality.High) assertSame(initialNativeEffect, background.renderEffect)
                        else assertSame(initialLocalPlan, background.localBlurPlan)
                    }
                }
                val refreshing = fixtureImage()
                GlassTags.forEach { tag ->
                    assertTrue("$tag 日期刷新不能移除实际模糊遮罩", metricContrast(tag, refreshing) < 0.35f)
                }
                composeRule.runOnIdle { state.value = page(nextPeriod, 150L + index) }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onNode(hasText((150L + index).toString()) and hasAnyAncestor(hasTestTag("statsMetricPlays")))
                    .assertIsDisplayed()
                composeRule.runOnIdle {
                    assertEquals(initialProviders, registry.regions.map { checkNotNull(it.regionProvider) }.toSet())
                }
                assertMetricRegions(currentRegions())
            }

            composeRule.runOnIdle { blurEnabled.value = false }
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.waitUntil(2_000) {
                composeRule.mainClock.advanceTimeByFrame()
                registry.regions.isEmpty() && !background.hasActiveBlur
            }
            assertFallbackPixels(GlassTags)
            GlassTags.forEach { tag ->
                val fallbackContrast = metricContrast(tag, fixtureImage())
                val minimumContrast = if (tag == RankingTag) 0.14f else 0.6f
                assertTrue("$tag 回退保留未模糊背景，contrast=$fallbackContrast", fallbackContrast > minimumContrast)
                assertTrue("$tag native/local应真正降低背景对比", checkNotNull(blurredContrast[tag]) < fallbackContrast * 0.7f)
            }

            composeRule.runOnIdle { blurEnabled.value = true }
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.waitUntil(2_000) {
                composeRule.mainClock.advanceTimeByFrame()
                registry.regions.size == 4 && background.hasActiveBlur
            }
            assertMetricRegions(currentRegions())
            composeRule.runOnIdle { visible.value = false }
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.waitUntil(2_000) {
                composeRule.mainClock.advanceTimeByFrame()
                registry.regions.isEmpty() && !background.hasActiveBlur
            }
            GlassTags.forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
            val cleared = fixtureImage().toPixelMap()
            initialBounds.forEach { card ->
                val sample = samplePoint(card)
                assertTrue("离开统计页后原metric位置不能残留遮罩", colorDistance(cleared[sample.x.toInt(), sample.y.toInt()], stripeColor(sample.x)) < 0.03f)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun render(size: DpSize, quality: AdvancedBlurQuality, host: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val base = LocalDensity.current
                    val scale = minOf(maxWidth.value / size.width.value, maxHeight.value / size.height.value)
                    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, 1f)) {
                        val localDensity = LocalDensity.current.density
                        val localFallback = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                        val localRankingFallback = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f)
                        SideEffect {
                            density = localDensity
                            fallbackColor = localFallback
                            rankingFallbackColor = localRankingFallback
                        }
                        val controller = AdvancedGlassController(
                            sdkInt = Build.VERSION.SDK_INT,
                            advancedBlurEnabled = blurEnabled.value,
                            enhancedAdvancedBlurEnabled = blurEnabled.value,
                            backendReady = true,
                            advancedBlurQuality = quality
                        )
                        if (host) {
                            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                            val contentBackdrop = rememberAdvancedGlassBackdrop()
                            AdvancedGlassHost(controller, backgroundBackdrop, contentBackdrop) {
                                val backdrops = checkNotNull(LocalAdvancedGlassBackdrops.current)
                                SideEffect {
                                    registry = backdrops.regionRegistry
                                    background = backgroundBackdrop
                                    content = contentBackdrop
                                }
                                Scene(size, backgroundBackdrop)
                            }
                        } else {
                            CompositionLocalProvider(LocalAdvancedGlassController provides controller) { Scene(size, null) }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Scene(size: DpSize, backdrop: AdvancedGlassBackdrop?) {
        Box(Modifier.requiredSize(size).testTag(RootTag).onGloballyPositioned { fixtureWindowOrigin = it.positionInWindow() }) {
            val captureModifier = if (backdrop == null) Modifier else Modifier.captureAdvancedGlassBackdrop(backdrop)
            Canvas(Modifier.fillMaxSize().then(captureModifier)) {
                val stripeWidth = StripeWidthDp.dp.toPx()
                repeat(ceil(this.size.width / stripeWidth).toInt()) { stripe ->
                    drawRect(
                        if (stripe % 2 == 0) Color.Black else Color.White,
                        topLeft = Offset(stripe * stripeWidth, 0f), size = Size(stripeWidth, this.size.height)
                    )
                }
            }
            if (visible.value) {
                PlaybackStatsContent(
                    state = state.value, request = StatsPageRequest(offset = 100), selectedPeriod = period.value,
                    sortMode = StatsSortMode.PLAY_COUNT,
                    onPeriodSelected = { period.value = it; state.value = state.value.copy(loading = true) },
                    onPageRequest = {}, onSongClick = { _, _ -> }, offlineMode = true,
                    miniPlayerHeight = 76.dp, tablet = true, modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun assertMetricRegions(regions: List<AdvancedGlassRegion>) {
        assertEquals("三张metric及排行榜必须各有独立区域", 4, regions.size)
        val fixture = bounds(RootTag)
        val expected = GlassTags.map { it to bounds(it) }.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        regions.zip(expected).forEach { (region, taggedBounds) ->
            val (tag, card) = taggedBounds
            assertEquals(AdvancedGlassRole.SemanticCard, region.role)
            val actual = region.boundsInWindow.translate(fixture.topLeft - fixtureWindowOrigin)
            assertEquals(card.left, actual.left, 1f)
            assertEquals(card.top, actual.top, 1f)
            assertEquals(card.right, actual.right, 1f)
            assertEquals(card.bottom, actual.bottom, 1f)
            with(region.cornerRadiiPx) {
                val cornerDp = if (tag == RankingTag) 24f else 20f
                listOf(topLeft, topRight, bottomLeft, bottomRight).forEach { assertEquals(cornerDp * density, it, 1f) }
            }
            assertEquals(1f, region.opacity, 0.001f)
        }
    }

    private fun assertFallbackPixels(tags: List<String> = MetricTags) {
        val image = fixtureImage().toPixelMap()
        tags.forEach { tag ->
            val point = samplePoint(bounds(tag))
            val fallback = if (tag == RankingTag) rankingFallbackColor else fallbackColor
            val expected = fallback.compositeOver(stripeColor(point.x))
            val actual = image[point.x.toInt(), point.y.toInt()]
            assertTrue("$tag 应保留原背景色回退，actual=$actual expected=$expected", colorDistance(actual, expected) < 0.04f)
        }
    }

    private fun assertRoundedCornersLeaveBackdropUnchanged(tags: List<String> = MetricTags) {
        val image = fixtureImage().toPixelMap()
        val fixture = bounds(RootTag)
        tags.forEach { tag ->
            val card = bounds(tag)
            val x = (card.left - fixture.left + 1f).toInt()
            val y = (card.top - fixture.top + 1f).toInt()
            assertTrue("$tag 圆角外不能出现矩形玻璃遮罩", colorDistance(image[x, y], stripeColor(x.toFloat())) < 0.03f)
        }
    }

    private fun metricContrast(tag: String, image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        val fixture = bounds(RootTag)
        val card = bounds(tag)
        val y = (card.bottom - fixture.top - 8f * density).toInt()
        val left = card.left - fixture.left + 24f * density
        val right = card.right - fixture.left - 24f * density
        val firstStripe = ceil(left / (StripeWidthDp * density)).toInt()
        val lastStripe = floor(right / (StripeWidthDp * density)).toInt() - 1
        val luminances = (firstStripe..lastStripe).map { stripe ->
            val color = pixels[((stripe + 0.5f) * StripeWidthDp * density).toInt(), y]
            (color.red + color.green + color.blue) / 3f
        }
        assertTrue("$tag 需要足够背景采样点", luminances.size >= 4)
        return checkNotNull(luminances.maxOrNull()) - checkNotNull(luminances.minOrNull())
    }

    private fun samplePoint(card: Rect): Offset {
        val fixture = bounds(RootTag)
        val left = card.left - fixture.left + 24f * density
        val stripe = ceil(left / (StripeWidthDp * density))
        return Offset((stripe + 0.5f) * StripeWidthDp * density, card.bottom - fixture.top - 8f * density)
    }

    private fun stripeColor(x: Float): Color =
        if (floor(x / (StripeWidthDp * density)).toInt() % 2 == 0) Color.Black else Color.White

    private fun currentRegions(): List<AdvancedGlassRegion> =
        resolveCurrentAdvancedGlassRegions(registry.regions)
            .sortedWith(compareBy({ it.boundsInWindow.top }, { it.boundsInWindow.left }))

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    private fun fixtureImage(): ImageBitmap = composeRule.onNodeWithTag(RootTag).captureToImage()

    private fun periodLabel(period: PlaybackStatsPeriod): String = ApplicationProvider.getApplicationContext<Context>()
        .getString(if (period == PlaybackStatsPeriod.MONTH) CoreCommonR.string.stats_period_month else CoreCommonR.string.stats_period_year)

    private fun colorDistance(first: Color, second: Color): Float =
        max(max(abs(first.red - second.red), abs(first.green - second.green)), abs(first.blue - second.blue))

    private fun capture(stage: String) {
        val args = InstrumentationRegistry.getArguments()
        val prefix = args.getString("capturePrefix")?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)
            ?.takeIf(String::isNotBlank) ?: "stats".takeIf { args.getString("captureUi").toBoolean() } ?: return
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$prefix-$stage.png")
        file.outputStream().use { assertTrue(fixtureImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("StatsMetricGlassTest", "screenshot=${file.absolutePath}")
    }

    private companion object {
        const val RootTag = "statsMetricGlassFixture"
        const val RankingTag = "statsRankingPane"
        const val StripeWidthDp = 8f
        val MetricTags = listOf("statsMetricPlays", "statsMetricListenTime", "statsMetricTracks")
        val GlassTags = MetricTags + RankingTag

        fun page(period: PlaybackStatsPeriod, plays: Long) = StatsPageState(
            summary = PlaybackStatsSummary(3L, plays, 9_000_000L, hasAnyStats = true),
            page = PlaybackStatsPage(listOf(TrackStat(
                id = 1L, name = "offline track", artist = "fixture artist", album = "fixture album",
                coverUrl = null, durationMs = 180_000L, totalListenMs = 9_000_000L,
                playCount = 75, lastPlayedAt = 1_000L, firstPlayedAt = 1_000L,
                mediaUri = null, localFilePath = null, localFileName = null, customName = null,
                customArtist = null, customCoverUrl = null, identityKey = "metric-glass-track"
            )), null),
            loading = false, loadedQuery = PlaybackStatsQuery(period = period, nowMillis = 1_000L),
            loadedRequest = StatsPageRequest(offset = 100)
        )
    }
}
