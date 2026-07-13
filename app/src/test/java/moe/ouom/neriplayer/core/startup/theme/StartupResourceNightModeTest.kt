package moe.ouom.neriplayer.core.startup.theme

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupResourceNightModeTest {
    @Test
    fun `dark ui mode is recognized`() {
        assertTrue(StartupResourceNightMode.isDark(Configuration.UI_MODE_NIGHT_YES))
    }

    @Test
    fun `light ui mode is not dark`() {
        assertFalse(StartupResourceNightMode.isDark(Configuration.UI_MODE_NIGHT_NO))
    }

    @Test
    fun `night mask ignores unrelated bits`() {
        val uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

        assertTrue(StartupResourceNightMode.isDark(uiMode))
    }
}
