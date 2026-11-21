package moe.ouom.neriplayer.core.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCallTimingTest {
    private var enabled = true
    private var elapsedMs = 0L
    private var uptimeMs = 0L
    private val reports = mutableListOf<String>()

    @Test
    fun `logs the gap between writes and time spent inside a write`() {
        val timing = timing()
        assertEquals(7, timing.measure { 7 })

        elapsedMs = 800
        uptimeMs = 30
        timing.measure {
            elapsedMs += 120
            uptimeMs += 120
        }

        assertEquals(
            listOf("operation=write atMs=800 gapMs=800 gapUptimeMs=30 durationMs=120 durationUptimeMs=120"),
            reports
        )
    }

    @Test
    fun `reset and disabled timing discard the prior call`() {
        val timing = timing()
        timing.measure {}
        timing.reset()
        elapsedMs = 1_000
        uptimeMs = 1_000
        timing.measure {}

        enabled = false
        elapsedMs += 1_000
        timing.measure { elapsedMs += 200 }
        enabled = true
        elapsedMs += 1_000
        timing.measure {}

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun `position sampling reports slow calls without reporting idle intervals`() {
        val timing = timing(reportGaps = false)
        timing.measure {}
        elapsedMs = 1_000
        uptimeMs = 1_000
        timing.measure { elapsedMs += 101 }

        assertEquals(
            listOf("operation=write atMs=1000 gapMs=0 gapUptimeMs=0 durationMs=101 durationUptimeMs=0"),
            reports
        )
    }

    private fun timing(reportGaps: Boolean = true) = PlaybackCallTiming(
        operation = "write",
        enabled = { enabled },
        reportGaps = reportGaps,
        elapsedRealtimeMs = { elapsedMs },
        uptimeMs = { uptimeMs },
        report = { reports += it }
    )
}
