package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingActiveIconFallbackTest {

    @Test
    fun `mid tone dark background falls back to the light blue accent when boosting fails`() {
        val background = Color(0xFF999999)

        val resolved = resolveNowPlayingActiveIconColor(
            accentColor = Color(0xFFFF0000),
            seedColor = Color(0xFF3366CC),
            inactiveContentColor = Color.White,
            backgroundColor = background
        )

        assertEquals(DarkBackgroundFallback, resolved)
    }

    @Test
    fun `mid tone light background falls back to the deep blue accent when boosting fails`() {
        val background = Color(0xFFC0C0C0)

        val resolved = resolveNowPlayingActiveIconColor(
            accentColor = Color(0xFFFF0000),
            seedColor = Color(0xFF3366CC),
            inactiveContentColor = Color.Black,
            backgroundColor = background
        )

        assertEquals(LightBackgroundFallback, resolved)
    }

    @Test
    fun `light background deepens a muted accent instead of replacing its hue`() {
        val accent = Color(0xFF8899AA)
        val background = Color.White

        val resolved = resolveNowPlayingActiveIconColor(
            accentColor = accent,
            seedColor = Color(0xFFCC3366),
            inactiveContentColor = Color.Black,
            backgroundColor = background
        )

        val argb = resolved.toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        assertNotEquals(accent, resolved)
        assertNotEquals(LightBackgroundFallback, resolved)
        assertTrue(blue > green && green > red)
        assertTrue(isNowPlayingActiveIconReadable(resolved, Color.Black, background))
    }

    @Test
    fun `gray accent and gray seed resolve to the dark theme fallback`() {
        val resolved = resolveNowPlayingActiveIconColor(
            accentColor = Color(0xFF808080),
            seedColor = Color(0xFF777777),
            inactiveContentColor = Color.White,
            backgroundColor = Color(0xFF101010)
        )

        assertEquals(DarkBackgroundFallback, resolved)
    }

    @Test
    fun `gray accent and gray seed resolve to the light theme fallback`() {
        val resolved = resolveNowPlayingActiveIconColor(
            accentColor = Color(0xFF808080),
            seedColor = Color(0xFF777777),
            inactiveContentColor = Color.Black,
            backgroundColor = Color(0xFFF5F5F5)
        )

        assertEquals(LightBackgroundFallback, resolved)
    }

    private companion object {
        val DarkBackgroundFallback = Color(0xFF8FD8FF)
        val LightBackgroundFallback = Color(0xFF0068B5)
    }
}
