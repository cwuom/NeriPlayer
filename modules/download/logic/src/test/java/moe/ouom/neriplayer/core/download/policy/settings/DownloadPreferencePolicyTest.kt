package moe.ouom.neriplayer.core.download.policy.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadPreferencePolicyTest {
    @Test
    fun parallelismUsesTheSupportedRange() {
        assertEquals(1, normalizeDownloadParallelism(Int.MIN_VALUE))
        assertEquals(1, normalizeDownloadParallelism(0))
        assertEquals(6, normalizeDownloadParallelism(6))
        assertEquals(8, normalizeDownloadParallelism(8))
        assertEquals(8, normalizeDownloadParallelism(Int.MAX_VALUE))
    }

    @Test
    fun blankFileNameTemplateUsesTheDefault() {
        assertNull(normalizeDownloadFileNameTemplate(null))
        assertNull(normalizeDownloadFileNameTemplate(" \n\t "))
    }

    @Test
    fun fileNameTemplatePreservesPlaceholdersAndTrimsSurroundingSpace() {
        assertEquals(
            "%title% - %artist%",
            normalizeDownloadFileNameTemplate("  %title% - %artist%  ")
        )
    }
}
