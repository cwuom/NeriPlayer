package moe.ouom.neriplayer.core.download.observability

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPumpSelectionMetricsTest {
    @After
    fun clearTrace() {
        DownloadPumpSelectionTrace.clearForTests()
    }

    @Test
    fun `selection metrics expose scan filters and elapsed time`() {
        val collector = DownloadPumpSelectionMetricsCollector(maxSamples = 2)
        val sample = DownloadPumpSelectionMetrics(
            capacity = 8,
            pagesRead = 3,
            rowsRead = 192,
            rowsFilteredAttempted = 10,
            rowsFilteredDuplicateOperation = 2,
            rowsFilteredStableKey = 4,
            rowsDeferredUidt = 6,
            candidateCount = 8,
            roomQueryNs = 2_000_000L,
            selectionNs = 5_000_000L
        )

        collector.record(sample)

        assertEquals(1, collector.snapshot().size)
        assertEquals(2.0, sample.roomQueryMs, 0.001)
        assertEquals(5.0, sample.selectionMs, 0.001)
        assertEquals(192, collector.snapshot().single().rowsRead)
    }

    @Test
    fun `selection window remains bounded and observer failures are isolated`() {
        var observed = 0
        val collector = DownloadPumpSelectionMetricsCollector(
            maxSamples = 4,
            onSample = {
                observed++
                error("metrics sink unavailable")
            }
        )
        repeat(1_000) { index ->
            collector.record(
                DownloadPumpSelectionMetrics(
                    capacity = 1,
                    pagesRead = 1,
                    rowsRead = index,
                    rowsFilteredAttempted = 0,
                    rowsFilteredDuplicateOperation = 0,
                    rowsFilteredStableKey = 0,
                    rowsDeferredUidt = 0,
                    candidateCount = 1,
                    roomQueryNs = 1L,
                    selectionNs = 1L
                )
            )
        }

        assertEquals(1_000, observed)
        assertEquals(4, collector.snapshot().size)
        assertTrue(collector.snapshot().first().rowsRead >= 996)
    }
}
