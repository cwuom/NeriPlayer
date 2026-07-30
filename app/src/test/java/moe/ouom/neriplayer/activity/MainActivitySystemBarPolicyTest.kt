package moe.ouom.neriplayer.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySystemBarPolicyTest {

    @Test
    fun `now playing uses light system bar icons in a light app theme`() {
        assertTrue(
            shouldUseLightSystemBarIcons(
                isDarkTheme = false,
                isNowPlayingVisible = true
            )
        )
    }

    @Test
    fun `non player screens continue to follow the app theme`() {
        assertFalse(
            shouldUseLightSystemBarIcons(
                isDarkTheme = false,
                isNowPlayingVisible = false
            )
        )
        assertTrue(
            shouldUseLightSystemBarIcons(
                isDarkTheme = true,
                isNowPlayingVisible = false
            )
        )
    }
}
