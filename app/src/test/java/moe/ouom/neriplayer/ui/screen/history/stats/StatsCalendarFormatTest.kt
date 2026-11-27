package moe.ouom.neriplayer.ui.screen.history.stats

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCalendarFormatTest {
    private val date = LocalDate.of(2026, 11, 23)

    @Test
    fun endpointsRespectTheAppLocaleAndRoundTripThroughTheInput() {
        listOf(
            Locale.SIMPLIFIED_CHINESE to "2026/11/23",
            Locale.US to "11/23/2026",
            Locale.UK to "23/11/2026"
        ).forEach { (locale, display) ->
            val format = StatsCalendarFormat(locale)
            assertEquals(display, format.format(date))
            assertEquals(date, format.parse(display))
            assertEquals(date, format.parse("2026-11-23"))
        }
    }

    @Test
    fun compactInputsUseTheLocalFieldOrderAndSupportNonAsciiDigits() {
        assertEquals(date, StatsCalendarFormat(Locale.SIMPLIFIED_CHINESE).parse("２０２６１１２３"))
        assertEquals(date, StatsCalendarFormat(Locale.US).parse("11232026"))
        assertEquals(date, StatsCalendarFormat(Locale.UK).parse("23112026"))
    }

    @Test
    fun incompleteOrImpossibleDatesCannotBeApplied() {
        val format = StatsCalendarFormat(Locale.US)
        listOf("", "11/2", "13/1/2026", "2026-02-29", "11/31/2026", "00000000").forEach {
            assertNull(it, format.parse(it))
        }
        assertEquals(LocalDate.of(2024, 2, 29), format.parse("2024-02-29"))
    }
}
