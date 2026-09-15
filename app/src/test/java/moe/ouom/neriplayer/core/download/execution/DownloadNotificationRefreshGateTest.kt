package moe.ouom.neriplayer.core.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationRefreshGateTest {
    @Test
    fun `frequent progress requests share one delayed refresh`() {
        var now = 1_000L
        val gate = DownloadNotificationRefreshGate(
            minIntervalMs = 750L,
            nowElapsedMs = { now }
        )

        assertTrue(gate.request(immediate = true).refreshNow)
        now += 100L
        val first = gate.request()
        val second = gate.request()

        assertFalse(first.refreshNow)
        assertNotNull(first.delayMs)
        assertEquals(first.token, second.token)
        assertEquals(first.delayMs, second.delayMs)
    }

    @Test
    fun `timer refreshes when the minimum interval has elapsed`() {
        var now = 1_000L
        val gate = DownloadNotificationRefreshGate(
            minIntervalMs = 750L,
            nowElapsedMs = { now }
        )

        gate.request(immediate = true)
        now += 100L
        val pending = gate.request()
        now += 650L
        val fired = gate.onPendingTimer(pending.token)

        assertTrue(fired.refreshNow)
        assertNull(fired.delayMs)
    }

    @Test
    fun `immediate request invalidates an older timer`() {
        var now = 1_000L
        val gate = DownloadNotificationRefreshGate(
            minIntervalMs = 750L,
            nowElapsedMs = { now }
        )

        gate.request(immediate = true)
        now += 100L
        val pending = gate.request()
        now += 50L
        gate.request(immediate = true)

        val stale = gate.onPendingTimer(pending.token)
        assertTrue(stale.ignored)
    }
}
