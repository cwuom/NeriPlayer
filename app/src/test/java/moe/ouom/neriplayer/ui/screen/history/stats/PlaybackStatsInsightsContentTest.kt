package moe.ouom.neriplayer.ui.screen.history.stats

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w393dp-h860dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackStatsInsightsContentTest {
    @get:Rule val compose = createComposeRule()

    private val song = TrackStat(
        id = 7, name = "Original title", artist = "Original artist", album = "Waterline", coverUrl = null,
        durationMs = 45_000L, totalListenMs = 61_000L, playCount = 3, lastPlayedAt = 10L, firstPlayedAt = 4L,
        mediaUri = null, localFilePath = null, localFileName = null, customName = "My edited title",
        customArtist = "Mica Vale", customCoverUrl = null, identityKey = "spotlight-track"
    )
    private val insights = PlaybackStatsInsights(
        summary = PlaybackStatsSummary(trackCount = 10, totalListenMs = 200_000L, totalPlayCount = 15),
        artists = (1..5).map { PlaybackStatsRank("Artist $it", 2, 61_000, 3, 0.305) },
        albums = listOf(PlaybackStatsRank("Waterline", 3, 122_000, 6, 0.61)),
        artistCount = 5, albumCount = 1, topTrack = song, activeDays = 2,
        averageDailyListenMs = 100_000L, averageListenMsPerPlay = 13_333L,
        weekdayListenMs = listOf(61_000L, 139_000L, 0L, 0L, 0L, 0L, 0L)
    )
    private val hours = mutableStateOf(List(24) { if (it == 3) 48_000L else 0L })
    private var played: TrackStat? = null

    @Test fun materialRanksAndRhythmCanBeInspected() = verifyInteractions(UiStyle.Material)
    @Test fun cleanRanksAndRhythmCanBeInspected() = verifyInteractions(UiStyle.Clean)

    private fun verifyInteractions(style: UiStyle) {
        render(style)
        compose.onNodeWithText("My edited title").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(song, played) }
        val artistTab = compose.onNodeWithText("Artists · 5")
        val role = artistTab.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
        assertEquals(style == UiStyle.Clean, role == Role.Tab)

        compose.onNodeWithText("Artist 5").assertDoesNotExist()
        compose.onNodeWithText("Show top 5").performScrollTo().performClick()
        compose.onNodeWithText("Artist 5").performScrollTo().performClick()
        compose.onNodeWithText("1m 1s").assertExists()
        compose.onNodeWithText("Time share").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Albums · 1").performScrollTo().performClick()
        compose.onNodeWithText("Artist 1").assertDoesNotExist()
        compose.onNodeWithText("Waterline").performScrollTo().performClick()
        compose.onNodeWithText("2m 2s").assertExists()
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Tuesday").assertExists()
        compose.onNodeWithContentDescription("Previous time slot").performScrollTo().performClick()
        compose.onNodeWithText("Monday").assertExists()
        compose.onNodeWithText("Hour").performScrollTo().performClick()
        val chart = compose.onNodeWithTag("statsRhythmChart")
        chart.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        val chartBounds = chart.fetchSemanticsNode().boundsInRoot
        val noon = compose.onNodeWithText("12").fetchSemanticsNode().boundsInRoot
        assertEquals(chartBounds.left + chartBounds.width * 12.5f / 24f, noon.center.x, chartBounds.width / 120f)
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals(true, "48s listened" in description)
        compose.onNodeWithContentDescription("Next time slot").performClick()
        val next = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals(true, "0s listened" in next)
    }

    @Test fun hourChartAppearsWhenListeningIsRecorded() {
        hours.value = emptyList()
        render(UiStyle.Material)
        compose.onNodeWithText("Tuesday").assertExists()
        compose.onNodeWithText("Hour").assertDoesNotExist()

        compose.runOnIdle { hours.value = List(24) { if (it == 1) 15_000L else 0L } }
        compose.onNodeWithText("Hour").performScrollTo().performClick()
        compose.onNodeWithText("15s").assertExists()
        compose.onNodeWithText("The hourly chart covers", substring = true).assertDoesNotExist()
        compose.onNodeWithText("use only records with known dates", substring = true).assertDoesNotExist()
    }

    private fun render(style: UiStyle) {
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = style == UiStyle.Clean,
                dynamicColor = false, seedColorHex = "#6750A4", uiStyle = style, animateColors = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlaybackStatsInsightsContent(insights,
                        hours.value, offlineMode = true, onTrackClick = { played = it })
                }
            }
        }
    }
}
