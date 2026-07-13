package moe.ouom.neriplayer.core.startup.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewDataDirectorySuffixTest {
    @Test
    fun `uses process segment after colon`() {
        assertEquals(
            "bili.login",
            WebViewDataDirectorySuffix.forProcess("moe.ouom.neriplayer:bili.login")
        )
    }

    @Test
    fun `uses full process name when no colon exists`() {
        assertEquals(
            "moe.ouom.neriplayer",
            WebViewDataDirectorySuffix.forProcess("moe.ouom.neriplayer")
        )
    }

    @Test
    fun `falls back when suffix is blank`() {
        assertEquals("webview", WebViewDataDirectorySuffix.forProcess("moe.ouom.neriplayer:"))
    }

    @Test
    fun `normalizes unsafe suffix characters`() {
        assertEquals(
            "login_process_1",
            WebViewDataDirectorySuffix.forProcess("moe.ouom.neriplayer:login process#1")
        )
    }
}
