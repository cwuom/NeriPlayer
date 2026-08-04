package moe.ouom.neriplayer.ui.screen.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliUploaderBackdropTest {
    @Test
    fun `keeps both banner and avatar sources when they are present`() {
        val backdrop = resolveBiliUploaderBackdropSources(
            bannerUrl = " https://example.com/banner.jpg ",
            avatarUrl = "https://example.com/avatar.jpg"
        )

        assertEquals("https://example.com/banner.jpg", backdrop.bannerUrl)
        assertEquals("https://example.com/avatar.jpg", backdrop.avatarUrl)
    }

    @Test
    fun `keeps avatar source when banner is blank`() {
        val backdrop = resolveBiliUploaderBackdropSources(
            bannerUrl = "   ",
            avatarUrl = " https://example.com/avatar.jpg "
        )

        assertEquals("", backdrop.bannerUrl)
        assertEquals("https://example.com/avatar.jpg", backdrop.avatarUrl)
    }
}
