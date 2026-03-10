package moe.ouom.neriplayer

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import moe.ouom.neriplayer.util.formatDurationSec
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.formatPlayCount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Locale

class ExampleUnitTest {

    @Test
    fun `formatPlayCount rounds to chinese units`() {
        val context = mockContext(Locale.SIMPLIFIED_CHINESE)
        `when`(context.getString(R.string.number_ten_thousand, 9.9)).thenReturn("9.9\u4e07")
        `when`(context.getString(R.string.number_hundred_million, 1.0)).thenReturn("1.0\u4ebf")

        assertEquals("9.9\u4e07", formatPlayCount(context, 99_000))
        assertEquals("1.0\u4ebf", formatPlayCount(context, 100_000_000))
        assertEquals("500", formatPlayCount(context, 500))
    }

    @Test
    fun `formatPlayCount rounds to english units`() {
        val context = mockContext(Locale.US)
        `when`(context.getString(R.string.number_thousand, 2.5)).thenReturn("2.5K")
        `when`(context.getString(R.string.number_million, 3.0)).thenReturn("3.0M")
        `when`(context.getString(R.string.number_billion, 1.2)).thenReturn("1.2B")

        assertEquals("2.5K", formatPlayCount(context, 2_500))
        assertEquals("3.0M", formatPlayCount(context, 3_000_000))
        assertEquals("1.2B", formatPlayCount(context, 1_200_000_000))
    }

    @Test
    fun `formatFileSize chooses appropriate suffix`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1_024))
        assertEquals("2.0 MB", formatFileSize(2_097_152))
    }

    @Test
    fun `formatDurationSec adds hours when necessary`() {
        assertEquals("00:00", formatDurationSec(0))
        assertEquals("03:25", formatDurationSec(205))
        assertEquals("1:05:07", formatDurationSec(3_907))
    }

    private fun mockContext(locale: Locale): Context {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(android.content.res.Configuration::class.java)
        val locales = mock(LocaleList::class.java)

        `when`(context.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales[0]).thenReturn(locale)
        return context
    }
}
