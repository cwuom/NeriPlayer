package moe.ouom.neriplayer.platform.netease.api.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NeteaseClientOfflineGuardTest {
    private val client = NeteaseClient { error("Comment token is outside this test") }

    @Test
    fun `personalized preheat is skipped without a login`() {
        client.ensurePersonalizedSession()

        assertFalse(client.hasLogin())
        assertNull(client.getNeteaseRequestCookies()["__csrf"])
    }

    @Test
    fun `song detail rejects an empty id list before any request`() {
        val blocking = assertThrows(IllegalArgumentException::class.java) { client.getSongDetail(emptyList()) }
        val cancellable = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.getSongDetailCancellable(emptyList()) }
        }

        assertEquals("ids must not be empty", blocking.message)
        assertEquals("ids must not be empty", cancellable.message)
    }
}
