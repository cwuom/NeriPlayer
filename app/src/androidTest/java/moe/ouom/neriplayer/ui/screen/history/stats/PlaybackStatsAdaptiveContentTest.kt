package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MIN_SDK
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackStatsAdaptiveContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selectedPeriod = mutableStateOf(PlaybackStatsPeriod.ALL)
    private val pageRequest = mutableStateOf(StatsPageRequest())
    private var playedSongs: List<SongItem> = emptyList()
    private var playedIndex = -1
    private var viewportWidth = 0f
    private var regionRegistry: AdvancedGlassRegionRegistry? = null
    private val tracks = (1..5).map(::track)

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletLandscapeUsesBoundedCompactOverviewAndIndependentRanking() {
        render(1600.dp, 800.dp, tablet = true)

        val frame = bounds("viewport")
        val dashboard = bounds("statsTabletColumns")
        val overview = bounds("statsOverviewPane")
        val ranking = bounds("statsRankingPane")
        assertEquals(logicalPixels(1200.dp), dashboard.width, 1f)
        assertEquals(frame.left + (frame.width - dashboard.width) / 2f, dashboard.left, 1f)
        assertTrue(overview.right < ranking.left)
        assertTrue(ranking.width > overview.width)
        assertInside(overview, dashboard)
        assertInside(ranking, dashboard)
        assertTrue(bounds("statsMetricPlays").height < logicalPixels(100.dp))
        assertTrue(bounds("statsMetricPlays").bottom < bounds("statsMetricListenTime").top)
        assertTrue(bounds("statsChart").top > bounds("statsOverview").bottom)
        capture("stats-tablet-landscape")
    }

    @Test
    fun tabletPortraitAlsoUsesReadableColumns() {
        render(800.dp, 1100.dp, tablet = true)

        val overview = bounds("statsOverviewPane")
        val ranking = bounds("statsRankingPane")
        assertTrue(ranking.width >= logicalPixels(400.dp))
        assertTrue(overview.right < ranking.left)
        assertInside(overview, bounds("viewport"))
        assertInside(ranking, bounds("viewport"))
        capture("stats-tablet-portrait")
    }

    @Test
    fun narrowTabletKeepsThreeCompactMetricsInOneRow() {
        render(600.dp, 900.dp, tablet = true)

        composeRule.onNodeWithTag("statsTabletColumns").assertDoesNotExist()
        assertInside(bounds("statsSingleColumn"), bounds("viewport"))
        val plays = bounds("statsMetricPlays")
        val time = bounds("statsMetricListenTime")
        val count = bounds("statsMetricTracks")
        assertEquals(plays.top, time.top, 1f)
        assertEquals(time.top, count.top, 1f)
        assertTrue(plays.right < time.left && time.right < count.left)
        capture("stats-tablet-narrow")
    }

    @Test
    fun widePhoneKeepsTheSingleColumnPresentation() {
        render(840.dp, 360.dp, tablet = false)

        composeRule.onNodeWithTag("statsTabletColumns").assertDoesNotExist()
        composeRule.onNodeWithTag("statsMetricPlays").assertDoesNotExist()
        assertEquals(bounds("viewport").width, bounds("statsSingleColumn").width, 1f)
        capture("stats-phone-landscape")
    }

    @Test
    fun tabletPeriodSelectionAndTrackPlaybackKeepTheirExistingCallbacks() {
        render(1280.dp, 800.dp, tablet = true)

        val dayLabel = string(CoreCommonR.string.stats_period_day)
        composeRule.onNodeWithText(dayLabel).performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(PlaybackStatsPeriod.DAY, selectedPeriod.value) }
        composeRule.onNodeWithTag("statsTrack_track1").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(tracks.first().toPlaybackStatsSongItem()), playedSongs)
            assertEquals(0, playedIndex)
        }
    }

    @Test
    fun laterPagesKeepRankOffsetsAndNavigationWithoutRepeatingTheTopChart() {
        val initialCursor = PlaybackStatsCursor(10L, "before")
        pageRequest.value = StatsPageRequest(initialCursor, offset = 100)
        render(1280.dp, 800.dp, tablet = true)

        composeRule.onNodeWithTag("statsChart").assertDoesNotExist()
        composeRule.onNode(
            hasText("101") and hasAnyAncestor(hasTestTag("statsTrack_track1")),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).performClick()
        composeRule.runOnIdle {
            assertEquals(PlaybackStatsCursor(20L, "next"), pageRequest.value.cursor)
            assertEquals(105, pageRequest.value.offset)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).performClick()
        composeRule.runOnIdle {
            assertEquals(PlaybackStatsCursor(5L, "previous"), pageRequest.value.cursor)
            assertTrue(pageRequest.value.before)
            assertEquals(5, pageRequest.value.offset)
        }
    }

    @Test
    fun resizingBetweenSingleAndDualColumnsKeepsTheMiddleTrackVisible() {
        val width = mutableStateOf(600.dp)
        val middleTrack = "statsTrack_track40"
        render(600.dp, 900.dp, tablet = true, renderedTracks = (1..80).map(::track), widthState = width)

        composeRule.onNodeWithTag("statsSingleColumn").performScrollToNode(hasTestTag(middleTrack))
        composeRule.onNodeWithTag(middleTrack).assertIsDisplayed()
        composeRule.onNodeWithTag("statsTrack_track1").assertIsNotDisplayed()

        composeRule.runOnIdle { width.value = 1280.dp }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("statsTabletColumns").assertIsDisplayed()
        composeRule.onNodeWithTag(middleTrack).assertIsDisplayed()
        composeRule.onNodeWithTag("statsTrack_track1").assertIsNotDisplayed()

        composeRule.runOnIdle { width.value = 600.dp }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("statsSingleColumn").assertIsDisplayed()
        composeRule.onNodeWithTag(middleTrack).assertIsDisplayed()
        composeRule.onNodeWithTag("statsTrack_track1").assertIsNotDisplayed()
    }

    @Test
    fun periodRefreshKeepsCardIdentityAndCorrectRankWhileBlockingOldPlayback() {
        val originalRequest = StatsPageRequest(PlaybackStatsCursor(10L, "before"), offset = 100)
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val state = mutableStateOf(page(tracks, originalQuery, originalRequest))
        pageRequest.value = originalRequest
        render(1280.dp, 800.dp, tablet = true, stateOverride = state, glassRegistration = true)

        val metricId = composeRule.onNodeWithTag("statsMetricPlays").fetchSemanticsNode().id
        val rankingId = composeRule.onNodeWithTag("statsRankingPane").fetchSemanticsNode().id
        val rankingBounds = bounds("statsRankingPane")
        val originalProviders = composeRule.runOnIdle {
            checkNotNull(regionRegistry).regions.map { checkNotNull(it.regionProvider) }.toSet()
        }
        assertEquals(4, originalProviders.size)
        composeRule.runOnIdle {
            selectedPeriod.value = PlaybackStatsPeriod.DAY
            pageRequest.value = StatsPageRequest()
            state.value = state.value.copy(loading = true)
        }
        composeRule.onNodeWithTag("statsLoading").assertIsDisplayed()
        composeRule.onNodeWithTag("statsTrack_track1").assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()
        composeRule.onNode(
            hasText("101") and hasAnyAncestor(hasTestTag("statsTrack_track1")), useUnmergedTree = true
        ).assertIsDisplayed()
        assertEquals(metricId, composeRule.onNodeWithTag("statsMetricPlays").fetchSemanticsNode().id)
        assertEquals(rankingId, composeRule.onNodeWithTag("statsRankingPane").fetchSemanticsNode().id)
        assertEquals(rankingBounds, bounds("statsRankingPane"))
        composeRule.runOnIdle {
            assertEquals(originalProviders, checkNotNull(regionRegistry).regions.map { checkNotNull(it.regionProvider) }.toSet())
            assertEquals(-1, playedIndex)
        }
        capture("stats-period-refresh")

        composeRule.runOnIdle { state.value = page(tracks, originalQuery.copy(period = PlaybackStatsPeriod.DAY)) }
        composeRule.onNodeWithTag("statsLoading").assertDoesNotExist()
        composeRule.onNodeWithTag("statsTrack_track1").assertIsEnabled().performClick()
        composeRule.onNode(
            hasText("1") and hasAnyAncestor(hasTestTag("statsTrack_track1")), useUnmergedTree = true
        ).assertIsDisplayed()
        assertEquals(metricId, composeRule.onNodeWithTag("statsMetricPlays").fetchSemanticsNode().id)
        composeRule.runOnIdle { assertEquals(0, playedIndex) }
    }

    @Test
    fun firstPageRapidPeriodRefreshKeepsMetricsAndChartGlassUntilNewResultsAreReady() {
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val state = mutableStateOf(page(tracks, originalQuery))
        render(1280.dp, 800.dp, tablet = true, stateOverride = state, glassRegistration = true)

        val retainedTags = listOf(
            "statsMetricPlays", "statsMetricListenTime", "statsMetricTracks", "statsChart", "statsRankingPane"
        )
        val originalNodes = retainedTags.associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().id to bounds(tag)
        }
        val originalProviderBounds = composeRule.runOnIdle {
            checkNotNull(regionRegistry).regions.associate { region ->
                val provider = checkNotNull(region.regionProvider)
                provider to checkNotNull(provider()).boundsInWindow
            }
        }
        assertEquals(5, originalProviderBounds.size)
        composeRule.onNodeWithTag("statsChart").assertIsDisplayed()

        fun assertRetainedCards() {
            originalNodes.forEach { (tag, snapshot) ->
                assertEquals(snapshot.first, composeRule.onNodeWithTag(tag).fetchSemanticsNode().id)
                assertEquals(snapshot.second, bounds(tag))
            }
            composeRule.runOnIdle {
                val currentProviders = checkNotNull(regionRegistry).regions
                    .map { checkNotNull(it.regionProvider) }.toSet()
                assertEquals(originalProviderBounds.keys, currentProviders)
                originalProviderBounds.forEach { (provider, originalBounds) ->
                    assertEquals(originalBounds, checkNotNull(provider()).boundsInWindow)
                }
            }
        }

        listOf(
            PlaybackStatsPeriod.DAY to CoreCommonR.string.stats_period_day,
            PlaybackStatsPeriod.MONTH to CoreCommonR.string.stats_period_month,
            PlaybackStatsPeriod.YEAR to CoreCommonR.string.stats_period_year
        ).forEach { (period, labelResId) ->
            composeRule.onNodeWithText(string(labelResId)).performClick().assertIsSelected()
            composeRule.runOnIdle {
                assertEquals(period, selectedPeriod.value)
                pageRequest.value = StatsPageRequest()
                state.value = state.value.copy(loading = true)
            }
            composeRule.onNodeWithTag("statsLoading").assertIsDisplayed()
            composeRule.onNodeWithTag("statsTrack_track1").assertIsNotEnabled()
                .performTouchInput { click() }
            composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()
            assertRetainedCards()
            composeRule.runOnIdle {
                assertEquals(originalQuery, state.value.loadedQuery)
                assertEquals(tracks, state.value.page.tracks)
                assertEquals(-1, playedIndex)
                assertTrue(playedSongs.isEmpty())
            }
        }
        capture("stats-first-page-rapid-refresh")

        val replacementTracks = (11..15).map(::track)
        composeRule.runOnIdle {
            state.value = page(replacementTracks, originalQuery.copy(period = PlaybackStatsPeriod.YEAR))
        }
        composeRule.onNodeWithTag("statsLoading").assertDoesNotExist()
        tracks.forEach { stat -> composeRule.onNodeWithTag("statsTrack_${stat.identityKey}").assertDoesNotExist() }
        assertRetainedCards()
        composeRule.onNodeWithTag("statsTrack_track11").assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(listOf(replacementTracks.first().toPlaybackStatsSongItem()), playedSongs)
            assertEquals(0, playedIndex)
        }
    }

    @Test
    fun emptyPeriodKeepsColumnsAndReleasesOldResults() {
        val state = mutableStateOf(page(tracks))
        render(800.dp, 1100.dp, tablet = true, stateOverride = state)
        val rankingId = composeRule.onNodeWithTag("statsRankingPane").fetchSemanticsNode().id
        val metricId = composeRule.onNodeWithTag("statsMetricPlays").fetchSemanticsNode().id

        composeRule.runOnIdle {
            selectedPeriod.value = PlaybackStatsPeriod.DAY
            state.value = state.value.copy(loading = true)
        }
        composeRule.onNodeWithTag("statsTabletColumns").assertIsDisplayed()
        composeRule.runOnIdle { state.value = page(emptyList()).copy(summary = PlaybackStatsSummary(hasAnyStats = true)) }
        composeRule.onNodeWithTag("statsTabletColumns").assertIsDisplayed()
        composeRule.onNodeWithTag("statsTrack_track1").assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_empty)).assertIsDisplayed()
        assertEquals(rankingId, composeRule.onNodeWithTag("statsRankingPane").fetchSemanticsNode().id)
        assertEquals(metricId, composeRule.onNodeWithTag("statsMetricPlays").fetchSemanticsNode().id)
        assertTrue(bounds("statsRankingPane").height < logicalPixels(400.dp))
        capture("stats-empty-period")
    }

    @Test
    fun failedRefreshKeepsTheRankingAndRetryCallbackAvailable() {
        val state = mutableStateOf(page(tracks))
        render(1280.dp, 800.dp, tablet = true, stateOverride = state)
        composeRule.runOnIdle { state.value = state.value.copy(failed = true) }

        composeRule.onNodeWithTag("statsTrack_track1").assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()
        composeRule.onNodeWithTag("statsRetry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, pageRequest.value.retry) }
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_day)).performClick().assertIsSelected()
    }

    @Test
    fun fewTracksDoNotStretchTheRankingPanelToTheBottom() {
        render(1280.dp, 800.dp, tablet = true, renderedTracks = tracks.take(1))
        val ranking = bounds("statsRankingPane")
        assertTrue(ranking.height < logicalPixels(320.dp))
        assertTrue(ranking.bottom < bounds("viewport").bottom - logicalPixels(72.dp + 16.dp))
        composeRule.onNodeWithTag("statsTrack_track1").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("statsPageNavigation").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, playedIndex) }
        capture("stats-sparse-ranking")
    }

    @Test
    fun shortTabletWindowKeepsBothPanesScrollableAboveTheMiniPlayer() {
        render(800.dp, 480.dp, tablet = true, renderedTracks = (1..80).map(::track), fontScale = 1.3f)
        val ranking = bounds("statsRankingPane")
        assertTrue(ranking.bottom <= bounds("viewport").bottom - logicalPixels(72.dp + 16.dp) + 1f)
        composeRule.onNodeWithTag("statsChart").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("statsRankings").performScrollToNode(hasTestTag("statsTrack_track80"))
        composeRule.onNodeWithTag("statsTrack_track80").assertIsDisplayed()
        composeRule.onNodeWithTag("statsRankings").performScrollToNode(hasTestTag("statsPageNavigation"))
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(80, pageRequest.value.offset) }
        val rankLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNode(
            hasText("160") and hasAnyAncestor(hasTestTag("statsTrack_track80")), useUnmergedTree = true
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(rankLayouts))
        }
        assertEquals(1, rankLayouts.size)
        assertEquals(1, rankLayouts.single().lineCount)
        capture("stats-short-tablet")
        val rankLayout = rankLayouts.single()
        assertFalse(rankLayout.multiParagraph.didExceedMaxLines)
        assertEquals(3, rankLayout.getLineEnd(0, visibleEnd = true))
        assertTrue("rank text exceeds its width", rankLayout.getLineRight(0) <= rankLayout.size.width + 1f)
        assertTrue("rank text exceeds its height", rankLayout.getLineBottom(0) <= rankLayout.size.height + 1f)
    }

    private fun render(
        width: Dp,
        height: Dp,
        tablet: Boolean,
        renderedTracks: List<TrackStat> = tracks,
        widthState: State<Dp>? = null,
        stateOverride: State<StatsPageState>? = null,
        fontScale: Float = 1f,
        glassRegistration: Boolean = false
    ) {
        viewportWidth = width.value
        val state = StatsPageState(
            summary = PlaybackStatsSummary(5L, 75L, 9_000_000L, hasAnyStats = true),
            page = PlaybackStatsPage(renderedTracks, PlaybackStatsCursor(20L, "next"), PlaybackStatsCursor(5L, "previous")),
            loading = false
        )
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val currentWidth = widthState?.value ?: width
                    val density = LocalDensity.current
                    val scale = minOf(maxWidth.value / currentWidth.value, maxHeight.value / height.value)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale = fontScale)) {
                        val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                        val contentBackdrop = rememberAdvancedGlassBackdrop()
                        val registry = remember { AdvancedGlassRegionRegistry() }
                        regionRegistry = registry
                        val glassController = AdvancedGlassController(
                            sdkInt = ADVANCED_GLASS_MIN_SDK, advancedBlurEnabled = glassRegistration,
                            enhancedAdvancedBlurEnabled = glassRegistration, backendReady = glassRegistration
                        )
                        CompositionLocalProvider(
                            LocalAdvancedGlassController provides glassController,
                            LocalAdvancedGlassBackdrops provides AdvancedGlassBackdrops(backgroundBackdrop, contentBackdrop, registry)
                        ) {
                            Box(Modifier.requiredSize(currentWidth, height).captureAdvancedGlassBackdrop(backgroundBackdrop)
                                .captureAdvancedGlassBackdrop(contentBackdrop).background(MaterialTheme.colorScheme.background).testTag("viewport")) {
                                PlaybackStatsContent(
                                    stateOverride?.value ?: state, pageRequest.value, selectedPeriod.value, StatsSortMode.PLAY_COUNT,
                                    onPeriodSelected = { selectedPeriod.value = it },
                                    onPageRequest = { pageRequest.value = it },
                                    onSongClick = { songs, index -> playedSongs = songs; playedIndex = index },
                                    offlineMode = true, miniPlayerHeight = 72.dp, tablet = tablet,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun page(
        renderedTracks: List<TrackStat>,
        query: PlaybackStatsQuery = PlaybackStatsQuery(nowMillis = 1_000L),
        request: StatsPageRequest = StatsPageRequest()
    ) = StatsPageState(
        summary = PlaybackStatsSummary(renderedTracks.size.toLong(), 75L, 9_000_000L, hasAnyStats = true),
        page = PlaybackStatsPage(renderedTracks, PlaybackStatsCursor(20L, "next"), PlaybackStatsCursor(5L, "previous")),
        loading = false, loadedQuery = query, loadedRequest = request
    )

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun logicalPixels(dp: Dp): Float = bounds("viewport").width / viewportWidth * dp.value

    private fun assertInside(child: Rect, parent: Rect) {
        assertTrue(child.left >= parent.left - 1f && child.right <= parent.right + 1f)
        assertTrue(child.top >= parent.top - 1f && child.bottom <= parent.bottom + 1f)
    }

    private fun string(resourceId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resourceId)

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag("viewport").captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("StatsAdaptiveContentTest", "screenshot=${file.absolutePath}")
    }

    private fun track(index: Int) = TrackStat(
        id = index.toLong(), name = "track $index", artist = "artist", album = "album",
        coverUrl = null, durationMs = 180_000L, totalListenMs = index * 600_000L,
        playCount = index * 5, lastPlayedAt = 1_000L, firstPlayedAt = 1_000L,
        mediaUri = null, localFilePath = null, localFileName = null, customName = "custom track $index",
        customArtist = "custom artist", customCoverUrl = null, identityKey = "track$index"
    )
}
