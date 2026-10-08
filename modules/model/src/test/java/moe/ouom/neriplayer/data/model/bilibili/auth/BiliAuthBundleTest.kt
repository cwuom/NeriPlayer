package moe.ouom.neriplayer.data.model.bilibili.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAuthBundleTest {

    @Test
    fun `login requires a non blank SESSDATA cookie`() {
        assertTrue(BiliAuthBundle(mapOf("SESSDATA" to "token")).hasLoginCookies())
        assertFalse(BiliAuthBundle(mapOf("SESSDATA" to " ")).hasLoginCookies())
        assertFalse(BiliAuthBundle(mapOf("bili_jct" to "csrf")).hasLoginCookies())
    }

    @Test
    fun `normalising drops blank cookie names and restamps the bundle`() {
        val normalized = BiliAuthBundle(
            cookies = mapOf("SESSDATA" to "token", " " to "x", "bili_jct" to "csrf"),
            savedAt = 1L
        ).normalized(savedAt = 9L)

        assertEquals(listOf("SESSDATA" to "token", "bili_jct" to "csrf"), normalized.cookies.toList())
        assertEquals(9L, normalized.savedAt)
    }

    @Test
    fun `resolved user mid is added as DedeUserID without restamping the bundle`() {
        val bundle = BiliAuthBundle(
            cookies = mapOf("SESSDATA" to "token", "bili_jct" to "csrf"),
            savedAt = 5L
        ).withUserMid(42L)

        assertEquals(
            listOf("SESSDATA" to "token", "bili_jct" to "csrf", "DedeUserID" to "42"),
            bundle.cookies.toList()
        )
        assertEquals(5L, bundle.savedAt)
    }
}
