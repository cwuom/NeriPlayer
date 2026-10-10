package moe.ouom.neriplayer.core.download.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadOperationTimingLookupTest {
    @Test
    fun `identity lookup returns the trace only for the same operation and attempt`() {
        val collector = DownloadOperationTimingCollector(nowNs = { 100L })
        val token = requireNotNull(collector.begin(" operation-1 ", attemptId = 2L))
        collector.mark(token, DownloadOperationTracePhase.ENQUEUED)

        val timing = requireNotNull(collector.snapshot(" operation-1 ", attemptId = 2L))

        assertEquals("operation-1", timing.operationId)
        assertEquals(2L, timing.attemptId)
        assertEquals(mapOf(DownloadOperationTracePhase.ENQUEUED to 100L), timing.marksNs)
        assertNull(collector.snapshot("operation-1", attemptId = 1L))
        assertNull(collector.snapshot("operation-1", attemptId = null))
        assertNull(collector.snapshot("operation-2", attemptId = 2L))
        assertNull(collector.snapshot("   ", attemptId = 2L))
    }
}
