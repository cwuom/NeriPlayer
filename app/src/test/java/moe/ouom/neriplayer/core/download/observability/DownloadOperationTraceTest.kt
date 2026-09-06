package moe.ouom.neriplayer.core.download.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class DownloadOperationTraceTest {

    @After
    fun clearCollector() {
        DownloadOperationTrace.clearForTests()
    }

    @Test
    fun `timing exposes independent queue permit transfer core and enrichment waits`() {
        var nowNs = 10L
        val collector = DownloadOperationTimingCollector(nowNs = { nowNs })
        val token = requireNotNull(collector.begin("operation-1", attemptId = 1L))

        collector.mark(token, DownloadOperationTracePhase.ENQUEUED)
        nowNs = 20L
        collector.mark(token, DownloadOperationTracePhase.QUEUE_SELECTED)
        collector.mark(token, DownloadOperationTracePhase.HOST_ADMISSION_REQUESTED)
        nowNs = 35L
        collector.mark(token, DownloadOperationTracePhase.HOST_ADMISSION_GRANTED)
        collector.mark(token, DownloadOperationTracePhase.NETWORK_PERMIT_REQUESTED)
        nowNs = 50L
        collector.mark(token, DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED)
        collector.mark(token, DownloadOperationTracePhase.NETWORK_STARTED)
        nowNs = 80L
        collector.mark(token, DownloadOperationTracePhase.NETWORK_FINISHED)
        collector.mark(token, DownloadOperationTracePhase.CORE_COMMIT_REQUESTED)
        nowNs = 90L
        collector.mark(token, DownloadOperationTracePhase.CORE_COMMIT_GRANTED)
        collector.mark(token, DownloadOperationTracePhase.CORE_COMMIT_STARTED)
        nowNs = 120L
        collector.mark(token, DownloadOperationTracePhase.CORE_COMMIT_FINISHED)
        collector.mark(token, DownloadOperationTracePhase.CORE_COMMITTED)
        collector.mark(token, DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED)
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_ENQUEUED)
        nowNs = 140L
        val snapshot = requireNotNull(
            collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_STARTED)
        )

        assertEquals(10L, snapshot.queueWaitNs)
        assertEquals(15L, snapshot.hostAdmissionWaitNs)
        assertEquals(15L, snapshot.networkPermitWaitNs)
        assertEquals(30L, snapshot.transferNs)
        assertEquals(70L, snapshot.networkPermitHeldNs)
        assertEquals(10L, snapshot.coreCommitWaitNs)
        assertEquals(30L, snapshot.coreCommitIoNs)
        assertEquals(20L, snapshot.enrichmentQueueWaitNs)
    }

    @Test
    fun `timing exposes enrichment asset stages independently`() {
        var nowNs = 100L
        val collector = DownloadOperationTimingCollector(nowNs = { nowNs })
        val token = requireNotNull(collector.begin("operation-assets", attemptId = 1L))

        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_METADATA_STARTED)
        nowNs = 130L
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_METADATA_FINISHED)
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_COVER_STARTED)
        nowNs = 175L
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_COVER_FINISHED)
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_LYRICS_STARTED)
        nowNs = 205L
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_LYRICS_FINISHED)
        collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_TAG_STARTED)
        nowNs = 220L
        val snapshot = requireNotNull(
            collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_TAG_FINISHED)
        )

        assertEquals(30L, snapshot.metadataNs)
        assertEquals(45L, snapshot.coverNs)
        assertEquals(30L, snapshot.lyricsNs)
        assertEquals(15L, snapshot.tagNs)
    }

    @Test
    fun `late callback from an older attempt cannot mutate the newer revision`() {
        var nowNs = 1L
        val collector = DownloadOperationTimingCollector(nowNs = { nowNs })
        val first = requireNotNull(collector.begin("operation-1", attemptId = 1L))
        collector.mark(first, DownloadOperationTracePhase.ENQUEUED)

        nowNs = 2L
        val second = requireNotNull(collector.begin("operation-1", attemptId = 2L))
        collector.mark(second, DownloadOperationTracePhase.ENQUEUED)
        nowNs = 3L

        assertNull(collector.mark(first, DownloadOperationTracePhase.NETWORK_STARTED))
        val current = requireNotNull(collector.snapshot(second))
        assertEquals(2L, current.attemptId)
        assertNull(current.markNs(DownloadOperationTracePhase.NETWORK_STARTED))
        assertTrue(second.revision > first.revision)
    }

    @Test
    fun `terminal mark is idempotent and post core enrichment remains observable`() {
        var nowNs = 1L
        val collector = DownloadOperationTimingCollector(nowNs = { nowNs++ })
        val token = requireNotNull(collector.begin("operation-1", attemptId = 1L))

        collector.mark(token, DownloadOperationTracePhase.ENQUEUED)
        collector.mark(token, DownloadOperationTracePhase.TERMINAL)
        val enrichment = collector.mark(token, DownloadOperationTracePhase.ENRICHMENT_STARTED)
        val terminal = collector.mark(token, DownloadOperationTracePhase.TERMINAL)

        assertEquals(3L, enrichment?.markNs(DownloadOperationTracePhase.ENRICHMENT_STARTED))
        assertEquals(4L, terminal?.markNs(DownloadOperationTracePhase.TERMINAL))
        assertEquals(3L, terminal?.lifetimeNs)
        assertNull(collector.mark(token, DownloadOperationTracePhase.TERMINAL))
    }

    @Test
    fun `bounded window evicts old samples without exceeding capacity`() {
        var nowNs = 1L
        val collector = DownloadOperationTimingCollector(
            nowNs = { nowNs++ },
            maxOperations = 8
        )
        val firstToken = requireNotNull(collector.begin("operation-0", 0L))
        collector.mark(firstToken, DownloadOperationTracePhase.ENQUEUED)
        collector.mark(firstToken, DownloadOperationTracePhase.TERMINAL)
        repeat(7) { index ->
            val itemIndex = index + 1
            val token = requireNotNull(collector.begin("operation-$itemIndex", itemIndex.toLong()))
            collector.mark(token, DownloadOperationTracePhase.ENQUEUED)
            collector.mark(token, DownloadOperationTracePhase.TERMINAL)
        }

        repeat(1_000) { index ->
            collector.begin("new-operation-$index", index.toLong())
        }

        assertEquals(8, collector.size())
        assertNull(collector.mark(firstToken, DownloadOperationTracePhase.NETWORK_STARTED))
    }

    @Test
    fun `observer failure does not affect sampling`() {
        var callbackCount = 0
        val collector = DownloadOperationTimingCollector(
            onSnapshot = {
                callbackCount++
                error("diagnostic sink unavailable")
            }
        )
        val token = requireNotNull(collector.begin("operation-1"))
        collector.mark(token, DownloadOperationTracePhase.ENQUEUED)
        collector.mark(token, DownloadOperationTracePhase.TERMINAL)

        assertEquals(2, callbackCount)
        assertEquals(1, collector.size())
    }

    @Test
    fun `identity lookup requires the matching attempt`() {
        val collector = DownloadOperationTimingCollector()
        val token = requireNotNull(collector.begin("operation-1", attemptId = 7L))

        assertNull(
            collector.mark(
                operationId = "operation-1",
                attemptId = 6L,
                phase = DownloadOperationTracePhase.ENQUEUED
            )
        )
        assertTrue(
            collector.mark(
                operationId = "operation-1",
                attemptId = 7L,
                phase = DownloadOperationTracePhase.ENQUEUED
            ) != null
        )
        assertEquals(
            7L,
            collector.snapshot("operation-1", attemptId = 7L)?.attemptId
        )
        assertEquals(7L, token.attemptId)
    }
}
