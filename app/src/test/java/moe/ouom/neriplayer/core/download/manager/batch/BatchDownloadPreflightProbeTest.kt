package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup.Result
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadPreflightProbeTest {
    @Test
    fun repeatedReferencesShareEvidenceAndOnlyNewReferencesSpendTheBudget() {
        val calls = mutableListOf<String>()
        val probe = BatchDownloadPreflightProbe(maxReferences = 2) { reference ->
            calls += reference
            Observation(
                if (reference == "missing") Result.Missing else Result.Present,
                1L
            )
        }
        assertEquals(Result.Present, probe.inspect("present"))
        assertEquals(Result.Missing, probe.inspect("missing"))
        assertTrue(probe.isExhausted)
        assertEquals(Result.Present, probe.inspect(" present "))
        assertEquals(Result.Missing, probe.inspect("missing"))
        assertNull(probe.inspect("unknown"))
        assertEquals(listOf("present", "missing"), calls)
    }

    @Test
    fun oneSlowReferenceStopsFollowingProbesWithoutFabricatingMissingEvidence() {
        var now = 0L
        var calls = 0
        val probe = BatchDownloadPreflightProbe(budgetNanos = 500L, nanoTime = { now }) {
            calls++
            now += 600L
            Observation(Result.Present, 1L)
        }
        assertEquals(Result.Present, probe.inspect("slow"))
        assertNull(probe.inspect("not-inspected"))
        assertEquals(1, calls)
    }

    @Test
    fun newStartObtainsFreshEvidenceAfterPermissionChanges() {
        var permitted = false
        val inspect: (String) -> Observation = {
            Observation(
                if (permitted) Result.Present else Result.PermissionLost(SecurityException("fixture")),
                1L
            )
        }
        val firstStart = BatchDownloadPreflightProbe(inspectReference = inspect)
        assertTrue(firstStart.inspect("audio") is Result.PermissionLost)
        permitted = true
        assertTrue(firstStart.inspect("audio") is Result.PermissionLost)
        val nextStart = BatchDownloadPreflightProbe(inspectReference = inspect)
        assertEquals(Result.Present, nextStart.inspect("audio"))
    }
}
