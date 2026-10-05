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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlaybackStatsPhoneRefreshRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val tracks = (1..3).map(::track)
    private val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
    private val selectedPeriod = mutableStateOf(PlaybackStatsPeriod.ALL)
    private val request = mutableStateOf(StatsPageRequest())
    private val state = mutableStateOf(page(tracks, originalQuery))
    private var playedSongs: List<SongItem> = emptyList()
    private var playedIndex = -1

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun dayMonthAndYearRefreshRetainPhoneResultsWithoutPaintingDisabledRowBackgrounds(): Unit =
        UiFailureDiagnostics.onFailure("stats-phone-period-refresh") {
            render()
            val originalNodes = snapshots()
            val originalPixels = rowEdgePixels(tracks)
            assertTransparentEdges(originalPixels)
            assertOverviewAndChart(tracks)
            capture("before")

            listOf(
                PlaybackStatsPeriod.DAY to CoreCommonR.string.stats_period_day,
                PlaybackStatsPeriod.MONTH to CoreCommonR.string.stats_period_month,
                PlaybackStatsPeriod.YEAR to CoreCommonR.string.stats_period_year
            ).forEach { (period, label) ->
                composeRule.onNodeWithText(string(label)).performClick().assertIsSelected()
                composeRule.runOnIdle { assertEquals(period, selectedPeriod.value) }
                composeRule.onNodeWithTag("statsLoading").assertIsDisplayed()
                assertRetainedNodes(originalNodes)
                assertOverviewAndChart(tracks)
                assertSameEdgePixels(originalPixels, rowEdgePixels(tracks))
                assertOldPlaybackBlocked()
                capture("loading-${period.name.lowercase()}")
            }

            val finalQuery = originalQuery.copy(period = PlaybackStatsPeriod.YEAR)
            composeRule.runOnIdle { state.value = page(tracks, finalQuery) }
            composeRule.onNodeWithTag("statsLoading").assertDoesNotExist()
            assertRetainedNodes(originalNodes)
            assertSameEdgePixels(originalPixels, rowEdgePixels(tracks))
            tracks.forEach { row(it).assertIsEnabled() }
            capture("loaded")

            val replacement = (4..6).map(::track)
            composeRule.runOnIdle { state.value = page(replacement, finalQuery) }
            tracks.forEach { row(it).assertDoesNotExist() }
            assertOverviewAndChart(replacement, expectedDuration = "2h 30m")
            assertTransparentEdges(rowEdgePixels(replacement))
            row(replacement.first()).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            composeRule.runOnIdle {
                assertEquals(listOf(replacement.first().toPlaybackStatsSongItem()), playedSongs)
                assertEquals(0, playedIndex)
            }
            capture("replacement")
        }

    @Test
    fun failedPhoneRefreshKeepsTransparentResultsAndRetryRestoresTheirLayout(): Unit =
        UiFailureDiagnostics.onFailure("stats-phone-failed-refresh") {
            render()
            val originalNodes = snapshots()
            val originalPixels = rowEdgePixels(tracks)
            assertTransparentEdges(originalPixels)

            composeRule.runOnIdle {
                selectedPeriod.value = PlaybackStatsPeriod.DAY
                state.value = state.value.copy(loading = false, failed = true)
            }
            composeRule.onNodeWithTag("statsRetry").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithTag("statsLoading").assertDoesNotExist()
            // 重试按钮会增加顶部高度，结果只应整体下移，不能重建或改变内部布局
            assertRetainedNodes(originalNodes, retryVisible = true)
            assertOverviewAndChart(tracks)
            assertSameEdgePixels(originalPixels, rowEdgePixels(tracks))
            assertOldPlaybackBlocked()
            capture("failed")

            composeRule.onNodeWithTag("statsRetry").performClick()
            composeRule.runOnIdle { assertEquals(1, request.value.retry) }
            composeRule.onNodeWithTag("statsRetry").assertDoesNotExist()
            composeRule.onNodeWithTag("statsLoading").assertIsDisplayed()
            assertRetainedNodes(originalNodes)
            assertSameEdgePixels(originalPixels, rowEdgePixels(tracks))
            assertOldPlaybackBlocked()

            composeRule.runOnIdle {
                state.value = page(tracks, originalQuery.copy(period = PlaybackStatsPeriod.DAY), request.value)
            }
            composeRule.onNodeWithTag("statsLoading").assertDoesNotExist()
            assertRetainedNodes(originalNodes)
            assertSameEdgePixels(originalPixels, rowEdgePixels(tracks))
            row(tracks.first()).assertIsEnabled().performTouchInput { click() }
            composeRule.runOnIdle {
                assertEquals(listOf(tracks.first().toPlaybackStatsSongItem()), playedSongs)
                assertEquals(0, playedIndex)
            }
        }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val scale = minOf(maxWidth.value / PHONE_WIDTH, maxHeight.value / PHONE_HEIGHT)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale = 1f)) {
                        Box(Modifier.requiredSize(PHONE_WIDTH.dp, PHONE_HEIGHT.dp).background(BACKGROUND).testTag(VIEWPORT)) {
                            PlaybackStatsContent(
                                state = state.value,
                                request = request.value,
                                selectedPeriod = selectedPeriod.value,
                                sortMode = StatsSortMode.PLAY_COUNT,
                                onPeriodSelected = {
                                    selectedPeriod.value = it
                                    request.value = StatsPageRequest()
                                    state.value = state.value.copy(loading = true, failed = false)
                                },
                                onPageRequest = {
                                    request.value = it
                                    state.value = state.value.copy(loading = true, failed = false)
                                },
                                onSongClick = { songs, index -> playedSongs = songs; playedIndex = index },
                                offlineMode = true,
                                miniPlayerHeight = NeriMiniPlayerDefaults.Height,
                                tablet = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("statsTabletColumns").assertDoesNotExist()
        composeRule.onNodeWithTag("statsSingleColumn").assertIsDisplayed()
    }

    private data class NodeSnapshot(val id: Int, val bounds: Rect)

    private fun snapshots(): Map<String, NodeSnapshot> = retainedTags().associateWith { tag ->
        val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        NodeSnapshot(node.id, node.boundsInRoot)
    }

    private fun assertRetainedNodes(original: Map<String, NodeSnapshot>, retryVisible: Boolean = false) {
        val overviewOffset = if (retryVisible) bounds(OVERVIEW).top - original.getValue(OVERVIEW).bounds.top else 0f
        if (retryVisible) assertTrue("重试文案应只增加顶部高度", overviewOffset >= 0f)
        original.forEach { (tag, snapshot) ->
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertEquals("刷新重建了 $tag", snapshot.id, node.id)
            val offset = if (tag == "statsSingleColumn") 0f else overviewOffset
            val expected = Rect(
                snapshot.bounds.left, snapshot.bounds.top + offset,
                snapshot.bounds.right, snapshot.bounds.bottom + offset
            )
            assertEquals("刷新改变了 $tag 的布局", expected, node.boundsInRoot)
        }
    }

    private fun assertOverviewAndChart(renderedTracks: List<TrackStat>, expectedDuration: String = "1h 0m") {
        val summary = summary(renderedTracks)
        listOf(summary.trackCount.toString(), summary.totalPlayCount.toString(), expectedDuration)
            .forEach { value ->
                composeRule.onNode(hasText(value) and hasAnyAncestor(hasTestTag(OVERVIEW)), useUnmergedTree = true)
                    .assertIsDisplayed()
            }
        renderedTracks.forEach { stat ->
            composeRule.onNode(hasText(checkNotNull(stat.customName)) and hasAnyAncestor(hasTestTag(CHART)), useUnmergedTree = true)
                .assertIsDisplayed()
            row(stat).assertIsDisplayed()
        }
    }

    private fun assertOldPlaybackBlocked() {
        tracks.forEach { stat -> row(stat).assertIsNotEnabled().performTouchInput { click() } }
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertTrue("保留的旧结果不能触发播放", playedSongs.isEmpty())
            assertEquals(-1, playedIndex)
        }
    }

    private fun rowEdgePixels(renderedTracks: List<TrackStat>): Map<String, List<Color>> {
        val pixelsPerDp = bounds(VIEWPORT).width / PHONE_WIDTH
        return renderedTracks.associate { stat ->
            val image = row(stat).assertIsDisplayed().captureToImage()
            val pixels = image.toPixelMap()
            val y = (3f * pixelsPerDp).toInt().coerceIn(1, image.height - 1)
            rowTag(stat) to listOf(0.2f, 0.5f, 0.8f).map { fraction ->
                pixels[(image.width * fraction).toInt().coerceIn(0, image.width - 1), y]
            }
        }
    }

    private fun assertTransparentEdges(samples: Map<String, List<Color>>) {
        samples.forEach { (tag, colors) ->
            colors.forEach { color -> assertTrue("$tag 绘制了额外底色: $color", colorDistance(color, BACKGROUND) < COLOR_TOLERANCE) }
        }
    }

    private fun assertSameEdgePixels(expected: Map<String, List<Color>>, actual: Map<String, List<Color>>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (tag, colors) ->
            colors.zip(actual.getValue(tag)).forEach { (before, after) ->
                assertTrue("$tag 禁用后背景闪变: $before -> $after", colorDistance(before, after) < COLOR_TOLERANCE)
            }
        }
    }

    private fun row(stat: TrackStat) = composeRule.onNodeWithTag(rowTag(stat))
    private fun rowTag(stat: TrackStat) = "statsTrack_${stat.identityKey}"
    private fun retainedTags() = listOf("statsSingleColumn", OVERVIEW, CHART) + tracks.map(::rowTag)
    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun string(resourceId: Int) = ApplicationProvider.getApplicationContext<Context>().getString(resourceId)
    private fun colorDistance(first: Color, second: Color) = maxOf(abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue))

    private fun page(renderedTracks: List<TrackStat>, query: PlaybackStatsQuery, loadedRequest: StatsPageRequest = StatsPageRequest()) =
        StatsPageState(
            summary = summary(renderedTracks),
            page = PlaybackStatsPage(renderedTracks, PlaybackStatsCursor(20L, "next")),
            loading = false, loadedQuery = query, loadedRequest = loadedRequest
        )

    private fun summary(renderedTracks: List<TrackStat>) = PlaybackStatsSummary(
        trackCount = renderedTracks.size.toLong(), totalPlayCount = renderedTracks.sumOf { it.playCount.toLong() },
        totalListenMs = renderedTracks.sumOf { it.totalListenMs }, hasAnyStats = true
    )

    private fun track(index: Int) = TrackStat(
        id = index.toLong(), name = "track $index", artist = "artist", album = "album", coverUrl = null,
        durationMs = 180_000L, totalListenMs = index * 600_000L, playCount = index * 10 + 5,
        lastPlayedAt = 1_000L, firstPlayedAt = 1_000L, mediaUri = null, localFilePath = null, localFileName = null,
        customName = "Synthetic song $index", customArtist = "Synthetic artist", customCoverUrl = null, identityKey = "phone$index"
    )

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-stats-phone-$stage.png")
        val bitmap = composeRule.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("StatsPhoneRefreshTest", "screenshot=${file.absolutePath}")
    }

    private companion object {
        const val PHONE_WIDTH = 380f
        const val PHONE_HEIGHT = 780f
        const val VIEWPORT = "statsPhoneViewport"
        const val OVERVIEW = "statsOverview"
        const val CHART = "statsChart"
        const val COLOR_TOLERANCE = 0.025f
        val BACKGROUND = Color(0xFF18314B)
    }
}
