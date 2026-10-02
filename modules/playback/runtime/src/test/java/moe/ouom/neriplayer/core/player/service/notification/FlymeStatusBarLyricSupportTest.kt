package moe.ouom.neriplayer.core.player.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlymeStatusBarLyricSupportTest {

    @Test
    fun `meizu manufacturer and brand are recognised case insensitively`() {
        assertTrue(isFlymeDevice("meizu", null))
        assertTrue(isFlymeDevice("MEIZU", null))
        assertTrue(isFlymeDevice(" Meizu ", "meizu"))
        assertTrue(isFlymeDevice(null, "MeiZu"))
    }

    @Test
    fun `other or missing vendors are not flyme`() {
        assertFalse(isFlymeDevice(null, null))
        assertFalse(isFlymeDevice("", "  "))
        assertFalse(isFlymeDevice("Xiaomi", "Xiaomi"))
        assertFalse(isFlymeDevice("MeizuX", "NotMeizu"))
    }

    @Test
    fun `reflected flags win over the vendor fallback`() {
        val reflected = FlymeStatusBarLyricFlags(0x11111111, 0x22222222)

        assertEquals(reflected, resolveFlymeStatusBarLyricFlags(reflected, "meizu", "meizu"))
        assertEquals(reflected, resolveFlymeStatusBarLyricFlags(reflected, "Xiaomi", null))
    }

    @Test
    fun `flyme devices fall back to the documented flag values when reflection is blocked`() {
        val flags = resolveFlymeStatusBarLyricFlags(null, "meizu", "meizu")

        assertEquals(FlymeStatusBarLyricFlags(0x01000000, 0x02000000), flags)
    }

    @Test
    fun `non flyme devices without reflected flags are unsupported`() {
        assertNull(resolveFlymeStatusBarLyricFlags(null, "Xiaomi", "Xiaomi"))
        assertNull(resolveFlymeStatusBarLyricFlags(null, null, null))
    }

    @Test
    fun `no ticker payload without support, lyric line or playback`() {
        val flags = FlymeStatusBarLyricFlags(0x01000000, 0x02000000)

        assertNull(
            resolveFlymeStatusBarLyricTicker(
                lyricState(enabled = true, line = "line"),
                null,
                playing = true,
            )
        )
        assertNull(
            resolveFlymeStatusBarLyricTicker(
                lyricState(enabled = false, line = "line"),
                flags,
                playing = true,
            )
        )
        assertNull(
            resolveFlymeStatusBarLyricTicker(
                lyricState(enabled = true, line = null),
                flags,
                playing = true,
            )
        )
    }

    @Test
    fun `paused playback writes no ticker so the status bar lyrics disappear`() {
        val flags = FlymeStatusBarLyricFlags(0x01000000, 0x02000000)

        assertNull(
            resolveFlymeStatusBarLyricTicker(
                // 暂停后歌词行会停留在最后一行，此时绝不能继续写 ticker
                lyricState(enabled = true, line = "last line before pause"),
                flags,
                playing = false,
            )
        )
    }

    @Test
    fun `supported devices with a lyric line produce the ticker payload`() {
        val flags = FlymeStatusBarLyricFlags(0x01000000, 0x02000000)

        assertEquals(
            FlymeStatusBarLyricTicker(
                text = "line",
                alwaysShowTicker = 0x01000000,
                onlyUpdateTicker = 0x02000000,
            ),
            resolveFlymeStatusBarLyricTicker(
                lyricState(enabled = true, line = "line"),
                flags,
                playing = true,
            ),
        )
    }

    private fun lyricState(
        enabled: Boolean,
        line: String?,
    ): StatusBarLyricNotificationState =
        resolveStatusBarLyricNotificationState(enabled = enabled, line = line)
}
