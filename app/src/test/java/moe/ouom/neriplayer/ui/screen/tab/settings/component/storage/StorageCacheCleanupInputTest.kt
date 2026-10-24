package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageCacheCleanupInputTest {
    @Test
    fun `retention accepts only nonnegative integer song counts`() {
        assertEquals(0, parseRetainRecentSongs("0"))
        assertEquals(25, parseRetainRecentSongs("25"))
        assertEquals(Int.MAX_VALUE, parseRetainRecentSongs(Int.MAX_VALUE.toString()))
        listOf("", "-1", "+1", "1.5", " 1", "1 ", "abc", "2147483648").forEach {
            assertNull("must reject $it", parseRetainRecentSongs(it))
        }
    }
}
