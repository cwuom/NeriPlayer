package moe.ouom.neriplayer.data.sync.remote

import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import org.junit.Assert.*
import org.junit.Test

class WebDavArchiveGcJournalTest {
    private val wall = 1_800_000_000_000L
    private val entry = WebDavArchiveEntry("neriplayer-sync-v4-${"a".repeat(64)}.zst", "\"first\"")

    @Test fun `first observation and a partial grace period never authorize deletion`() {
        val first = observe(WebDavArchiveGcState(), 0)
        assertTrue(WebDavArchiveGcJournal.eligible(first).isEmpty())
        val waiting = observe(first, WebDavArchiveGcJournal.GRACE_MS - 1)
        assertTrue(WebDavArchiveGcJournal.eligible(waiting).isEmpty())
        assertEquals(listOf(entry), WebDavArchiveGcJournal.eligible(observe(waiting, WebDavArchiveGcJournal.GRACE_MS)))
    }

    @Test fun `reference and ETag changes start a new grace period`() {
        val first = observe(WebDavArchiveGcState(), 0)
        assertTrue(WebDavArchiveGcJournal.protect(first, setOf(entry.path)).candidates.isEmpty())
        val protected = WebDavArchiveGcJournal.observe(first, listOf(entry), setOf(entry.path), wall + 1, 1001)
        assertTrue(protected.candidates.isEmpty())
        val changed = WebDavArchiveGcJournal.observe(first, listOf(entry.copy(etag = "\"second\"")), emptySet(),
            wall + WebDavArchiveGcJournal.GRACE_MS, 1000 + WebDavArchiveGcJournal.GRACE_MS)
        assertTrue(WebDavArchiveGcJournal.eligible(changed).isEmpty())
    }

    @Test fun `wall clock jumps rollback and reboot reset candidate age`() {
        val first = observe(WebDavArchiveGcState(), 0)
        for ((newWall, newUptime) in listOf(wall + WebDavArchiveGcJournal.GRACE_MS to 1001L,
            wall - 1 to 2000L, wall + WebDavArchiveGcJournal.GRACE_MS to 1L)) {
            val reset = WebDavArchiveGcJournal.observe(first, listOf(entry), emptySet(), newWall, newUptime)
            assertTrue(WebDavArchiveGcJournal.eligible(reset).isEmpty())
            assertEquals(newWall, reset.candidates.single().firstSeenMs)
        }
    }

    @Test fun `a small wall clock advance cannot shorten seven full elapsed days`() {
        val first = observe(WebDavArchiveGcState(), 0)
        val early = WebDavArchiveGcJournal.observe(first, listOf(entry), emptySet(), wall + WebDavArchiveGcJournal.GRACE_MS,
            1000 + WebDavArchiveGcJournal.GRACE_MS - 60_000L)
        assertTrue(WebDavArchiveGcJournal.eligible(early).isEmpty())
        val complete = WebDavArchiveGcJournal.observe(early, listOf(entry), emptySet(), wall + WebDavArchiveGcJournal.GRACE_MS + 60_000L,
            1000 + WebDavArchiveGcJournal.GRACE_MS)
        assertEquals(listOf(entry), WebDavArchiveGcJournal.eligible(complete))
    }

    @Test fun `candidate age saturates without overflow after a very long observation gap`() {
        val first = observe(WebDavArchiveGcState(), 0)
        val later = WebDavArchiveGcJournal.observe(first, listOf(entry), emptySet(), Long.MAX_VALUE,
            Long.MAX_VALUE - wall + 1000)
        assertEquals(WebDavArchiveGcJournal.GRACE_MS, later.candidates.single().observedAgeMs)
        assertEquals(listOf(entry), WebDavArchiveGcJournal.eligible(later))
        assertFalse(WebDavArchiveGcJournal.valid(later.copy(candidates = listOf(later.candidates.single().copy(observedAgeMs = Long.MAX_VALUE)))))
    }

    @Test fun `malformed persisted states cannot preserve candidate age`() {
        val invalid = listOf(WebDavArchiveGcState(version = 2), WebDavArchiveGcState(wallMs = -1),
            WebDavArchiveGcState(uptimeMs = -1), WebDavArchiveGcState(wallMs = wall,
                candidates = listOf(WebDavArchiveGcCandidate("user.txt", "\"v\"", 1))),
            WebDavArchiveGcState(wallMs = wall, candidates = listOf(WebDavArchiveGcCandidate(entry.path, "W/\"v\"", 1))),
            WebDavArchiveGcState(wallMs = wall, candidates = listOf(WebDavArchiveGcCandidate(entry.path, entry.etag, wall + 1))))
        for (state in invalid) {
            assertFalse(WebDavArchiveGcJournal.valid(state))
            assertTrue(WebDavArchiveGcJournal.eligible(observe(state, WebDavArchiveGcJournal.GRACE_MS)).isEmpty())
        }
    }

    @Test fun `missing objects are forgotten and candidate state stays bounded`() {
        val first = observe(WebDavArchiveGcState(), 0)
        assertTrue(WebDavArchiveGcJournal.observe(first, emptyList(), emptySet(), wall + 1, 1001).candidates.isEmpty())
        val entries = (0..WebDavArchiveGcJournal.MAX_CANDIDATES).map { index ->
            entry.copy(path = "neriplayer-sync-v4-${index.toString(16).padStart(64, '0')}.zst")
        }
        val bounded = WebDavArchiveGcJournal.observe(WebDavArchiveGcState(), entries, emptySet(), wall, 1000)
        assertEquals(WebDavArchiveGcJournal.MAX_CANDIDATES, bounded.candidates.size)
        val ready = WebDavArchiveGcJournal.observe(bounded, entries, emptySet(), wall + WebDavArchiveGcJournal.GRACE_MS,
            1000 + WebDavArchiveGcJournal.GRACE_MS)
        assertEquals(32, WebDavArchiveGcJournal.eligible(ready).size)
        assertFalse(WebDavArchiveGcJournal.valid(ready.copy(candidates = ready.candidates + ready.candidates.first())))
    }

    private fun observe(state: WebDavArchiveGcState, elapsed: Long): WebDavArchiveGcState =
        WebDavArchiveGcJournal.observe(state, listOf(entry), emptySet(), wall + elapsed, 1000 + elapsed)
}
