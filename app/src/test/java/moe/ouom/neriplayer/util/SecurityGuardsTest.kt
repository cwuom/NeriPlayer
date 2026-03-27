package moe.ouom.neriplayer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityGuardsTest {

    @Test
    fun `shouldBlockMainFrameNavigation allows disallowed subframe navigation`() {
        assertFalse(
            shouldBlockMainFrameNavigation(
                isForMainFrame = false,
                isAllowedNavigation = false
            )
        )
    }

    @Test
    fun `shouldBlockMainFrameNavigation blocks disallowed main frame navigation`() {
        assertTrue(
            shouldBlockMainFrameNavigation(
                isForMainFrame = true,
                isAllowedNavigation = false
            )
        )
    }

    @Test
    fun `shouldBlockMainFrameNavigation allows allowed main frame navigation`() {
        assertTrue(
            !shouldBlockMainFrameNavigation(
                isForMainFrame = true,
                isAllowedNavigation = true
            )
        )
    }
}
