package moe.ouom.neriplayer.core.download.observability

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHostTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

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
    fun `journal codec omits absent optional boundaries`() {
        val blocked = DownloadStartupRecoveryJournalRecord(
            generation = 2L,
            phase = DownloadStartupDeadlineTracker.Phase.BLOCKED,
            recordedAtWallMs = 0L,
            t0ToT1Ns = null,
            t1ToT2Ns = null,
            t0ToT2Ns = null,
            withinDeadline = null,
            blockedReason = "storage locked"
        )

        val encoded = DownloadStartupRecoveryJournalCodec.encode(blocked)

        assertEquals(
            mapOf(
                "generation" to 2L,
                "phase" to "BLOCKED",
                "recorded_at_wall_ms" to 0L,
                "blocked_reason" to "storage locked"
            ),
            encoded
        )
        assertEquals(blocked, DownloadStartupRecoveryJournalCodec.decode(encoded))
    }

    @Test
    fun `journal read restores the persisted boundary and tolerates unavailable preferences`() {
        val record = DownloadStartupRecoveryJournalRecord(
            generation = 3L,
            phase = DownloadStartupDeadlineTracker.Phase.QUEUE_READY,
            recordedAtWallMs = 10L,
            t0ToT1Ns = 5L,
            t1ToT2Ns = null,
            t0ToT2Ns = null,
            withinDeadline = null,
            blockedReason = null
        )
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences().apply {
            values.putAll(DownloadStartupRecoveryJournalCodec.encode(record))
        }
        val unreadable = mock(SharedPreferences::class.java)
        `when`(unreadable.all).thenThrow(IllegalStateException("preferences corrupted"))
        val unavailable = journalContext(null)
        `when`(unavailable.getSharedPreferences(anyString(), anyInt()))
            .thenThrow(IllegalStateException("user locked"))

        assertEquals(record, DownloadStartupRecoveryJournal.read(journalContext(preferences)))
        assertNull(
            DownloadStartupRecoveryJournal.read(
                journalContext(DownloadExecutionHostTestSupport.StatefulSharedPreferences())
            )
        )
        assertNull(DownloadStartupRecoveryJournal.read(journalContext(unreadable)))
        assertNull(DownloadStartupRecoveryJournal.read(unavailable))
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

    private fun journalContext(preferences: SharedPreferences?): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences("download_startup_recovery_journal", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        return context
    }
}
