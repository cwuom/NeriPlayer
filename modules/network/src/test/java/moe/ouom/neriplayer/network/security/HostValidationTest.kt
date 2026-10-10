package moe.ouom.neriplayer.network.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostValidationTest {

    @Test
    fun `root domains match themselves and dotted subdomains after normalisation`() {
        assertTrue("Music.163.COM.".matchesRootDomain("163.com"))
        assertTrue("163.com".matchesRootDomain(".163.com."))
        assertTrue(" interface.music.163.com ".matchesRootDomain("MUSIC.163.com"))
    }

    @Test
    fun `lookalike, blank or rootless hosts never match`() {
        assertFalse("evil163.com".matchesRootDomain("163.com"))
        assertFalse("163.com.evil.example".matchesRootDomain("163.com"))
        assertFalse(" . ".matchesRootDomain("163.com"))
        assertFalse("music.163.com".matchesRootDomain(" "))
    }
}
