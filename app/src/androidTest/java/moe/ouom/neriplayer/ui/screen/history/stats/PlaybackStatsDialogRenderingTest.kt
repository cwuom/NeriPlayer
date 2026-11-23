package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsDialogRenderingTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 23)
    private val selection = mutableStateOf(StatsDateFilter(trailingDays = 7))
    private val hourlyListening = listOf(0, 0, 0, 0, 0, 0, 4, 18, 32, 26, 12, 8, 15, 6, 0, 4, 0, 10, 25, 48, 37, 22, 8, 3)
        .map { it * 60_000L }
    private val album = PlaybackStatsRank("I Look To You", 1, 3_636_000L, 19, 0.1922)
    private val insights = PlaybackStatsInsights(
        summary = PlaybackStatsSummary(trackCount = 94, totalPlayCount = 156, totalListenMs = 18_918_000L),
        albums = listOf(album, PlaybackStatsRank("The Greatest Hits", 6, 2_000_000L, 12, 0.1057)),
        albumCount = 2,
        activeDays = 1,
        weekdayListenMs = List(7) { if (it == 0) hourlyListening.sum() else 0L },
        averageDailyListenMs = hourlyListening.sum(),
        averageListenMsPerPlay = hourlyListening.sum() / 156
    )

    @Test fun cleanDialogsKeepChineseDatesReadableAndUseTheSelectedRange() = verifyDialogs(UiStyle.Clean)
    @Test fun materialDialogsKeepChineseDatesReadableAndUseTheSelectedRange() = verifyDialogs(UiStyle.Material)

    private fun verifyDialogs(style: UiStyle) {
        compose.setContent {
            val base = LocalContext.current
            val localized = remember(base) {
                base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    setLocale(Locale.SIMPLIFIED_CHINESE)
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration
            ) {
                NeriTheme(followSystemDark = false, forceDark = style == UiStyle.Clean, dynamicColor = false,
                    seedColorHex = "#837057", uiStyle = style, animateColors = false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("播放统计", style = MaterialTheme.typography.headlineMedium)
                            StatsPeriodSelector(selection.value.period) { selection.value = StatsDateFilter(it) }
                            StatsDateControls(selection.value, today, DayOfWeek.MONDAY) { selection.value = it }
                            PlaybackStatsRankingInsights(insights, offlineMode = true, onTrackClick = {})
                            PlaybackStatsRhythm(insights, hourlyListening, selection.value.isSingleDay)
                        }
                    }
                }
            }
        }

        openDateRange()
        capture(style, "range")
        assertEndpointSingleLine("statsDateStart")
        assertEndpointSingleLine("statsDateEnd")
        compose.onNodeWithTag("statsDateInputMode").performClick()
        compose.onNodeWithText("开始日期", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("结束日期", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("statsDateEndInput").performTextReplacement("2026/11/24")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("日期不能晚于今天", substring = true).assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsNotEnabled()
        capture(style, "input-error")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(StatsDateFilter(trailingDays = 7), selection.value) }

        openDateRange()
        compose.onNodeWithTag("statsDateDay:2026-11-10").performClick()
        compose.onNodeWithTag("statsDateDay:2026-11-20").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.runOnIdle {
            assertEquals(today.minusDays(13)..today.minusDays(3), selection.value.dates(today, DayOfWeek.MONDAY))
        }

        compose.onNodeWithText(album.name).performScrollTo().performClick()
        listOf(album.name, "曲目数", "播放次数", "时长占比").forEach { label ->
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("statsRankDetail")), useUnmergedTree = true)
                .assertIsDisplayed()
        }
        capture(style, "rank")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("statsRankDetail").assertDoesNotExist()

        compose.onNode(hasText("日") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("statsRhythmChart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("小时").assertIsDisplayed()
        compose.onNodeWithText("星期").assertDoesNotExist()
        capture(style, "day-hours")
    }

    private fun openDateRange() {
        compose.onNodeWithText("近 7 天").performClick()
        compose.onNodeWithText("自选日期").performClick()
    }

    private fun assertEndpointSingleLine(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(tag, 1, layout.lineCount)
        val details = "$tag (${layout.layoutInput.text}, ${layout.size}, " +
            "line bounds ${layout.getLineLeft(0)}..${layout.getLineRight(0)}, " +
            "height ${layout.getLineBottom(0)}, ellipsized ${layout.isLineEllipsized(0)})"
        assertFalse(details, layout.isLineEllipsized(0))
        assertEquals(details, layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
        assertTrue(details, layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width)
        assertTrue(details, layout.getLineTop(0) >= 0f && layout.getLineBottom(0) <= layout.size.height)
    }

    private fun capture(style: UiStyle, stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: return
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(450, 5_000)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(directory, "$prefix-${style.name.lowercase()}-$stage.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
