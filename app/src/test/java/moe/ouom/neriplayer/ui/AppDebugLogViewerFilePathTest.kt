package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDebugLogViewerFilePathTest {
    @Test
    fun missingLogPathResolvesToEmptyString() {
        assertEquals("", debugLogViewerFilePath(null))
        assertEquals("", debugLogViewerFilePath(""))
    }

    @Test
    fun presentLogPathIsPreserved() {
        assertEquals("/logs/crash.txt", debugLogViewerFilePath("/logs/crash.txt"))
    }
}
