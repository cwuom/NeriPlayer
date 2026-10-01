package moe.ouom.neriplayer.util.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeFormattingTest {
    @Test
    fun unitBoundariesAndNegativeValuesKeepTheirDisplay() {
        withLocale(Locale.US) {
            assertEquals("-1 B", formatFileSize(-1))
            assertEquals("0 B", formatFileSize(0))
            assertEquals("1023 B", formatFileSize(1023))
            assertEquals("1.0 KB", formatFileSize(1024))
            assertEquals("1.0 MB", formatFileSize(1024 * 1024))
            assertEquals("1.0 GB", formatFileSize(1024 * 1024 * 1024))
            assertEquals("8589934592.0 GB", formatFileSize(Long.MAX_VALUE))
        }
    }

    @Test
    fun decimalSeparatorFollowsTheCurrentLocale() {
        withLocale(Locale.GERMANY) {
            assertEquals("1,5 KB", formatFileSize(1536))
        }
    }

    private fun withLocale(locale: Locale, check: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            check()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
