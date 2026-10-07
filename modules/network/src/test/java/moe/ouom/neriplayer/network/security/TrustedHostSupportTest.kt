package moe.ouom.neriplayer.network.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedHostSupportTest {

    @Test
    fun `trusted hosts are trimmed of spaces and dots and lower cased`() {
        assertEquals("music.163.com", normalizeTrustedHost(" .Music.163.COM. "))
        assertEquals("", normalizeTrustedHost(null))
    }

    @Test
    fun `a domain matches itself and its subdomains only`() {
        assertTrue(hostMatchesDomain("interface.music.163.com", ".163.com"))
        assertTrue(hostMatchesDomain("163.COM", "163.com"))
        assertFalse(hostMatchesDomain("music163.com", "163.com"))
        assertFalse(hostMatchesDomain(null, "163.com"))
        assertFalse(hostMatchesDomain("163.com", " . "))
    }

    @Test
    fun `any domain match needs a host and one matching domain`() {
        val domains = listOf("163.com", "bilibili.com")

        assertTrue(hostMatchesAnyDomain("api.Bilibili.com", domains))
        assertFalse(hostMatchesAnyDomain("example.com", domains))
        assertFalse(hostMatchesAnyDomain(" ", domains))
        assertFalse(hostMatchesAnyDomain("api.bilibili.com", emptyList()))
    }
}
