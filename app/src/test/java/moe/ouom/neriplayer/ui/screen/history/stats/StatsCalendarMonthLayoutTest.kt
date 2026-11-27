package moe.ouom.neriplayer.ui.screen.history.stats

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalDate
import moe.ouom.neriplayer.common.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp-mdpi")
class StatsCalendarMonthLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val applyLabel = ApplicationProvider.getApplicationContext<Application>().getString(R.string.action_apply)

    @Test
    fun fourFiveAndSixWeekMonthsKeepTheApplyButtonAndDialogBottomInPlace() {
        showCalendar(LocalDate.of(2026, 2, 1), dateRange = true)
        val apply = compose.onNodeWithText(applyLabel)
        val dialog = compose.onNode(isDialog())
        val buttonTop = apply.getUnclippedBoundsInRoot().top
        val dialogBottom = dialog.getUnclippedBoundsInRoot().bottom
        compose.onAllNodes(hasClickAction() and hasContentDescription("February", substring = true)).assertCountEquals(28)
        for (month in 3..5) {
            compose.onNodeWithTag("statsDateNextMonth").performClick()
            val firstDay = compose.onNodeWithTag("statsDateDay:${LocalDate.of(2026, month, 1)}").getUnclippedBoundsInRoot()
            assertTrue(firstDay.bottom - firstDay.top >= 36.dp)
            assertEquals("Apply position in month $month", buttonTop, apply.getUnclippedBoundsInRoot().top)
            assertEquals("Dialog bottom in month $month", dialogBottom, dialog.getUnclippedBoundsInRoot().bottom)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h450dp-mdpi")
    fun shortScreenCanScrollToTheSixthWeekAndApplyTheLastDay() {
        var applied: LocalDate? = null
        val last = LocalDate.of(2026, 5, 31)
        showCalendar(last.withDayOfMonth(1), dateRange = false) { applied = it }
        compose.onNodeWithTag("statsDateDay:$last").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(applyLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(last, applied) }
    }

    private fun showCalendar(start: LocalDate, dateRange: Boolean, onApply: (LocalDate) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                StatsCalendarDialog(dateRange, start, null, LocalDate.of(2026, 6, 1), DayOfWeek.SUNDAY,
                    onDismiss = {}, onApply = { first, _ -> onApply(first) })
            }
        }
    }
}
