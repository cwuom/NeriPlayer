package moe.ouom.neriplayer.api.ltw.reconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherReconnectPolicyTest {
    @Test
    fun `expired membership and closed or missing rooms stop reconnecting`() {
        listOf(" Unauthorized ", "room closed", "failed (http=410 Gone)", "request failed (410)",
            "room not initialized", "not found in do").forEach {
            assertTrue(it, isTerminalListenTogetherReconnectError(it))
        }
    }

    @Test
    fun `transient and unspecified failures can reconnect`() {
        listOf(null, "", " ", "timeout", "http=502 Bad Gateway").forEach {
            assertFalse(isTerminalListenTogetherReconnectError(it))
        }
    }

    @Test
    fun `reconnect budget outlasts the server controller offline grace period`() {
        repeat(20) {
            val totalMs = (1..LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS).sumOf(::listenTogetherReconnectDelayMs)
            assertTrue("total=$totalMs", totalMs > 10 * 60_000L)
        }
    }

    @Test
    fun `backoff remains bounded including later attempts`() {
        for ((attempt, base) in listOf(1 to 1500L, 2 to 3000L, 3 to 5000L, 4 to 8000L, 15 to 12000L)) {
            repeat(20) {
                assertTrue(listenTogetherReconnectDelayMs(attempt) in (base * 8 / 10)..(base * 12 / 10))
            }
        }
    }
}
