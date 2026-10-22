package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTransportSecurityTest {
    @Test
    fun `cleartext detection follows the transport parser for accepted http urls`() {
        assertTrue(isWebDavCleartextUrl("http://dav.example.invalid"))
        assertTrue(isWebDavCleartextUrl("  HTTP://dav.example.invalid:8080/remote.php/dav  "))
        assertTrue(isWebDavCleartextUrl("http:dav.example.invalid"))
    }

    @Test
    fun `https and invalid addresses never produce a cleartext warning`() {
        assertFalse(isWebDavCleartextUrl("https://dav.example.invalid/path?next=http://other.invalid"))
        assertFalse(isWebDavCleartextUrl("  HTTPS://dav.example.invalid  "))
        assertFalse(isWebDavCleartextUrl(""))
        assertFalse(isWebDavCleartextUrl("dav.example.invalid"))
        assertFalse(isWebDavCleartextUrl("ftp://dav.example.invalid"))
    }
}
