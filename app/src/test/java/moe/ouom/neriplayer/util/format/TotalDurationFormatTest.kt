package moe.ouom.neriplayer.util.format

import android.content.Context
import android.content.res.Resources
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class TotalDurationFormatTest {

    private val resources = mock(Resources::class.java)
    private val context = mock(Context::class.java).also { `when`(it.resources).thenReturn(resources) }

    @Test
    fun `empty or negative totals use the zero minutes text`() {
        `when`(context.getString(CoreCommonR.string.time_zero_minutes)).thenReturn("0 min")

        assertEquals("0 min", formatTotalDuration(context, 0L))
        assertEquals("0 min", formatTotalDuration(context, -1L))
        verifyNoInteractions(resources)
    }

    @Test
    fun `totals under an hour show whole minutes and drop leftover seconds`() {
        `when`(resources.getQuantityString(CoreCommonR.plurals.time_minutes_only, 45, 45))
            .thenReturn("45 minutes")

        assertEquals("45 minutes", formatTotalDuration(context, 45 * 60_000L + 59_999L))
    }

    @Test
    fun `totals of an hour or more wrap the minute text with the hour count`() {
        `when`(resources.getQuantityString(CoreCommonR.plurals.time_minutes_only, 30, 30))
            .thenReturn("30 minutes")
        `when`(resources.getQuantityString(CoreCommonR.plurals.time_hours_minutes, 1, 1, "30 minutes"))
            .thenReturn("1 hour 30 minutes")

        assertEquals("1 hour 30 minutes", formatTotalDuration(context, 90 * 60_000L))
    }

    @Test
    fun `whole hours still pass a zero minute part`() {
        `when`(resources.getQuantityString(CoreCommonR.plurals.time_minutes_only, 0, 0))
            .thenReturn("0 minutes")
        `when`(resources.getQuantityString(CoreCommonR.plurals.time_hours_minutes, 2, 2, "0 minutes"))
            .thenReturn("2 hours 0 minutes")

        assertEquals("2 hours 0 minutes", formatTotalDuration(context, 2 * 3_600_000L))
    }
}
