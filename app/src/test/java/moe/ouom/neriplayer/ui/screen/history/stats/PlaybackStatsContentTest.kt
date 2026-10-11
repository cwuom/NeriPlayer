package moe.ouom.neriplayer.ui.screen.history.stats

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaybackStatsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nextCursor = PlaybackStatsCursor(sortValue = 1L, identityKey = "next")
    private val previousCursor = PlaybackStatsCursor(sortValue = 9L, identityKey = "previous")

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `phone layout charts the loaded sort and routes period song and page actions`() {
        val tracks = listOf(
            track(1, plays = 12, listenMs = 3_660_000L, coverUrl = "https://example.com/cover.jpg"),
            track(2, plays = 8, listenMs = 600_000L),
            track(3, plays = 4, listenMs = 45_000L),
            track(4, plays = 0, listenMs = 0L)
        )
        var dark by mutableStateOf(false)
        val periods = mutableListOf<PlaybackStatsPeriod>()
        val pageRequests = mutableListOf<StatsPageRequest>()
        val playedSongs = mutableListOf<List<SongItem>>()
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                PlaybackStatsContent(
                    state = state(
                        tracks,
                        nextCursor = nextCursor,
                        loadedQuery = PlaybackStatsQuery(sort = PlaybackStatsSort.LISTEN_TIME),
                        loadedRequest = StatsPageRequest()
                    ),
                    request = StatsPageRequest(),
                    selectedPeriod = PlaybackStatsPeriod.WEEK,
                    sortMode = StatsSortMode.PLAY_COUNT,
                    onPeriodSelected = { periods += it },
                    onPageRequest = { pageRequests += it },
                    onSongClick = { songs, _ -> playedSongs += songs },
                    offlineMode = false,
                    miniPlayerHeight = 0.dp,
                    tablet = false
                )
            }
        }

        composeRule.onNodeWithTag("statsSingleColumn").assertExists()
        composeRule.onNodeWithTag("statsChart").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_top_tracks)).assertExists()
        assertEquals(2, composeRule.onAllNodesWithText("1h 1m").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("45s").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("statsOverview").assertExists()

        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_day)).performClick()
        composeRule.onNodeWithTag("statsTrack_track-1").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).performClick()
        dark = true
        composeRule.onNodeWithTag("statsTrack_track-4").assertExists()

        assertEquals(listOf(PlaybackStatsPeriod.DAY), periods)
        assertEquals(listOf(1L), playedSongs.single().map(SongItem::id))
        assertEquals(listOf(StatsPageRequest(nextCursor, offset = 4)), pageRequests)
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun `phone layout explains empty loading and failed results`() {
        var current by mutableStateOf(state(emptyList(), summary = PlaybackStatsSummary()))
        val pageRequests = mutableListOf<StatsPageRequest>()
        composeRule.setContent {
            MaterialTheme {
                PlaybackStatsContent(
                    state = current,
                    request = StatsPageRequest(offset = 100, retry = 2),
                    selectedPeriod = PlaybackStatsPeriod.ALL,
                    sortMode = StatsSortMode.RECENT,
                    onPeriodSelected = {},
                    onPageRequest = { pageRequests += it },
                    onSongClick = { _, _ -> },
                    offlineMode = true,
                    miniPlayerHeight = 64.dp,
                    tablet = false,
                    modifier = Modifier
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.stats_empty)).assertExists()

        current = state(emptyList(), summary = PlaybackStatsSummary(hasAnyStats = true, usesLegacyBreakdown = true))
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_missing_breakdown)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_compat_notice)).assertDoesNotExist()

        current = state(emptyList(), summary = PlaybackStatsSummary(hasAnyStats = true))
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_empty)).assertExists()

        current = current.copy(loading = true)
        composeRule.onNodeWithTag("statsLoading").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_empty)).assertDoesNotExist()

        current = current.copy(loading = false, failed = true)
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_empty)).assertDoesNotExist()
        composeRule.onNodeWithTag("statsRetry").performClick()

        assertEquals(listOf(StatsPageRequest(offset = 100, retry = 3)), pageRequests)
    }

    @Test
    @Config(qualifiers = "w1280dp-h1000dp-land")
    fun `tablet columns show the ranking title chart card notice and empty pane`() {
        var current by mutableStateOf(
            state(
                listOf(track(1, plays = 5, listenMs = 120_000L), track(2, plays = 3, listenMs = 60_000L)),
                summary = PlaybackStatsSummary(trackCount = 2, totalPlayCount = 8, totalListenMs = 180_000L,
                    usesLegacyBreakdown = true, hasAnyStats = true),
                previousCursor = previousCursor,
                loadedRequest = StatsPageRequest()
            )
        )
        val pageRequests = mutableListOf<StatsPageRequest>()
        composeRule.setContent {
            MaterialTheme {
                PlaybackStatsContent(
                    state = current,
                    request = StatsPageRequest(cursor = previousCursor, offset = 150),
                    selectedPeriod = PlaybackStatsPeriod.MONTH,
                    sortMode = StatsSortMode.FIRST_PLAYED,
                    onPeriodSelected = {},
                    onPageRequest = { pageRequests += it },
                    onSongClick = { _, _ -> },
                    offlineMode = false,
                    miniPlayerHeight = 0.dp,
                    tablet = true
                )
            }
        }

        composeRule.onNodeWithTag("statsTabletColumns").assertExists()
        composeRule.onNodeWithTag("statsOverview").assertExists()
        composeRule.onNodeWithTag("statsMetricPlays").assertExists()
        composeRule.onNodeWithText("2m").assertExists()
        composeRule.onNodeWithTag("statsChart").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_sort_first_played)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_compat_notice)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).performClick()
        assertEquals(
            listOf(StatsPageRequest(previousCursor, before = true, offset = 50)),
            pageRequests
        )

        current = current.copy(loadedRequest = StatsPageRequest(offset = 100))
        composeRule.onNodeWithTag("statsChart").assertDoesNotExist()

        current = state(emptyList(), summary = PlaybackStatsSummary(hasAnyStats = true))
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_sort_first_played)).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h1000dp-land")
    fun `narrow tablet content keeps a single column with the compact chart card`() {
        composeRule.setContent {
            MaterialTheme {
                PlaybackStatsContent(
                    state = state(listOf(track(1, plays = 2, listenMs = 1_000L), track(2, plays = 1, listenMs = 500L))),
                    request = StatsPageRequest(),
                    selectedPeriod = PlaybackStatsPeriod.YEAR,
                    sortMode = StatsSortMode.PLAY_COUNT,
                    onPeriodSelected = {},
                    onPageRequest = {},
                    onSongClick = { _, _ -> },
                    offlineMode = false,
                    miniPlayerHeight = 0.dp,
                    tablet = true,
                    modifier = Modifier.width(700.dp)
                )
            }
        }

        composeRule.onNodeWithTag("statsSingleColumn").assertExists()
        composeRule.onNodeWithTag("statsTabletColumns").assertDoesNotExist()
        composeRule.onNodeWithTag("statsMetricTracks").assertExists()
        composeRule.onNodeWithTag("statsChart").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_period_year)).assertExists()
    }

    @Test
    fun `standalone overview card and page navigation follow their state`() {
        var current by mutableStateOf(state(listOf(track(1, plays = 1, listenMs = 1_000L)), loading = true))
        val rowStat = track(9, plays = 2, listenMs = 61_000L)
        var rowClicks = 0
        composeRule.setContent {
            MaterialTheme {
                StatsOverviewCard(totalPlayCount = 7L, totalListenMs = 7_200_000L, trackCount = 3L)
                StatsPageNavigation(current, StatsPageRequest(cursor = previousCursor), change = {})
                StatTrackRow(rank = 9, stat = rowStat, onClick = { rowClicks++ })
            }
        }

        composeRule.onNodeWithText("Track 9").performClick()
        assertEquals(1, rowClicks)
        composeRule.onNodeWithText("2h 0m").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_total_plays)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()

        current = current.copy(loading = false, failed = true)
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).assertIsNotEnabled()

        current = current.copy(failed = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_previous_page)).assertIsEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.stats_next_page)).assertIsNotEnabled()
    }

    @Test
    fun `chart helpers and page requests are derived from the selected sort and cursors`() {
        val first = track(1, plays = 4, listenMs = 90_000L)
        val second = track(2, plays = 2, listenMs = 30_000L)

        assertEquals(4f, statsChartMaxValue(listOf(first, second), StatsSortMode.RECENT))
        assertEquals(90_000f, statsChartMaxValue(listOf(first, second), StatsSortMode.LISTEN_TIME))
        assertEquals(1f, statsChartMaxValue(emptyList(), StatsSortMode.PLAY_COUNT))
        assertEquals(0.5f, statsChartFraction(statsChartValue(second, StatsSortMode.FIRST_PLAYED), 4f))
        assertEquals(0f, statsChartFraction(3f, 0f))
        assertEquals("1m", statsChartValueLabel(first, StatsSortMode.LISTEN_TIME))
        assertEquals("4", statsChartValueLabel(first, StatsSortMode.PLAY_COUNT))
        assertEquals("1h 0m", formatListenDuration(3_600_000L))
        assertEquals("59s", formatListenDuration(59_999L))

        val withPrevious = state(listOf(first), previousCursor = previousCursor, nextCursor = nextCursor)
        assertTrue(hasPreviousStatsPage(withPrevious, StatsPageRequest()))
        assertTrue(hasPreviousStatsPage(state(listOf(first)), StatsPageRequest(cursor = nextCursor)))
        assertEquals(false, hasPreviousStatsPage(state(listOf(first)), StatsPageRequest()))
        assertEquals(
            StatsPageRequest(previousCursor, before = true, offset = 0),
            previousStatsPageRequest(withPrevious, StatsPageRequest(offset = 40))
        )
        assertEquals(StatsPageRequest(), previousStatsPageRequest(state(emptyList(), previousCursor = previousCursor), StatsPageRequest(offset = 200)))
        assertEquals(StatsPageRequest(), previousStatsPageRequest(state(listOf(first)), StatsPageRequest(offset = 200)))
        assertEquals(StatsPageRequest(nextCursor, offset = 41), nextStatsPageRequest(withPrevious, StatsPageRequest(offset = 40)))
    }

    private fun string(id: Int): String = context.getString(id)

    private fun state(
        tracks: List<TrackStat>,
        summary: PlaybackStatsSummary = PlaybackStatsSummary(
            trackCount = tracks.size.toLong(),
            totalPlayCount = tracks.sumOf { it.playCount.toLong() },
            totalListenMs = tracks.sumOf(TrackStat::totalListenMs),
            hasAnyStats = tracks.isNotEmpty()
        ),
        loading: Boolean = false,
        nextCursor: PlaybackStatsCursor? = null,
        previousCursor: PlaybackStatsCursor? = null,
        loadedQuery: PlaybackStatsQuery? = null,
        loadedRequest: StatsPageRequest? = null
    ) = StatsPageState(
        summary = summary,
        page = PlaybackStatsPage(tracks, nextCursor, previousCursor),
        loading = loading,
        loadedQuery = loadedQuery,
        loadedRequest = loadedRequest
    )

    private fun track(index: Int, plays: Int, listenMs: Long, coverUrl: String? = null) = TrackStat(
        id = index.toLong(),
        name = "Track $index",
        artist = "Artist $index",
        album = "Album",
        coverUrl = coverUrl,
        durationMs = 180_000L,
        totalListenMs = listenMs,
        playCount = plays,
        lastPlayedAt = 0L,
        firstPlayedAt = 0L,
        mediaUri = null,
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = "track-$index"
    )
}
