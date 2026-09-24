package moe.ouom.neriplayer.core.download.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartupRecoveryJournalTest {
    @Test
    fun `journal codec preserves startup boundaries`() {
        var nowNs = 1_000L
        val tracker = DownloadStartupDeadlineTracker(
            nowNs = { nowNs },
            deadlineNs = 5_000L
        )
        tracker.begin()
        nowNs = 1_500L
        tracker.markQueueReady()
        nowNs = 3_000L
        val snapshot = requireNotNull(tracker.markTransferStarted())

        val record = DownloadStartupRecoveryJournalCodec.fromSnapshot(
            snapshot = snapshot,
            recordedAtWallMs = 123L
        )
        val restored = DownloadStartupRecoveryJournalCodec.decode(
            DownloadStartupRecoveryJournalCodec.encode(record)
        )

        assertEquals(record, restored)
        assertEquals(2_000L, restored?.t0ToT2Ns)
        assertTrue(restored?.withinDeadline == true)
    }

    @Test
    fun `journal codec rejects malformed or negative values`() {
        val malformed = mapOf<String, Any?>(
            "generation" to 1L,
            "phase" to "NOT_A_PHASE",
            "recorded_at_wall_ms" to 1L
        )
        assertNull(DownloadStartupRecoveryJournalCodec.decode(malformed))

        val negativeDuration = mapOf<String, Any?>(
            "generation" to 1L,
            "phase" to DownloadStartupDeadlineTracker.Phase.QUEUE_READY.name,
            "recorded_at_wall_ms" to 1L,
            "t0_to_t1_ns" to -1L
        )
        assertNull(DownloadStartupRecoveryJournalCodec.decode(negativeDuration))
    }

    @Test
    fun `blocked reason is normalized and bounded`() {
        val tracker = DownloadStartupDeadlineTracker(nowNs = { 1L })
        tracker.begin()
        val snapshot = requireNotNull(tracker.markBlocked(" "))
        val record = DownloadStartupRecoveryJournalCodec.fromSnapshot(
            snapshot = snapshot,
            recordedAtWallMs = 1L
        )

        assertEquals("unspecified", record.blockedReason)
        assertTrue(record.blockedReason!!.length <= 256)
    }

    @Test
    fun `new process generation stays ahead of persisted generation`() {
        val tracker = DownloadStartupDeadlineTracker(nowNs = { 1L })

        val first = tracker.begin(previousGeneration = 41L)
        val second = tracker.begin(previousGeneration = first.generation)
        val restoredFromOlderProcess = tracker.begin(previousGeneration = 7L)

        assertEquals(42L, first.generation)
        assertEquals(43L, second.generation)
        assertEquals(44L, restoredFromOlderProcess.generation)
    }
}
