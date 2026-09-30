package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOperationStateCompatibilityTest {
    @Test
    fun `wire parsing preserves unknown and normalized records`() {
        DownloadOperationState.entries.filterNot { it == DownloadOperationState.UNKNOWN }
            .forEach { assertEquals(it, DownloadOperationState.parse(" ${it.wireName} ")) }
        listOf(null, "", "future", "queued").forEach {
            assertEquals(DownloadOperationState.UNKNOWN, DownloadOperationState.parse(it))
        }
    }

    @Test
    fun `missing state accepts an initial write while unknown records remain fenced`() {
        listOf(null, "", " ").forEach {
            assertEquals("FUTURE", DownloadOperationStateTransitions.resolve(it, "FUTURE"))
        }
        assertEquals("FUTURE", DownloadOperationStateTransitions.resolve(" FUTURE ", "FUTURE"))
        assertNull(DownloadOperationStateTransitions.resolve("FUTURE", "QUEUED"))
        assertEquals("FUTURE", DownloadOperationStateTransitions.resolve("QUEUED", "FUTURE"))
    }

    @Test
    fun `noncanonical requested states retain their existing admission behavior`() {
        assertEquals(" QUEUED ", DownloadOperationStateTransitions.resolve("PENDING_QUEUE", " QUEUED "))
        assertNull(DownloadOperationStateTransitions.resolve("RETRYABLE", " RETRYABLE "))
        assertNull(DownloadOperationStateTransitions.resolve("CORE_COMMITTED", " CORE_COMMITTED "))
        assertNull(DownloadOperationStateTransitions.resolve("CANCELLED", " CANCELLED "))
    }

    @Test
    fun `durable recovery groups retain complete alias and reject pre-core states`() {
        listOf("CORE_COMMITTED", "ASSETS_ENRICHING", "FINALIZED", "DEGRADED_COMPLETE",
            "COMPLETED", "METADATA_ACTION_REQUIRED", "COMPLETE").forEach {
            assertTrue(isPostCoreDownloadOperationState(" ${it.lowercase()} "))
        }
        listOf(null, "RUNNING", "COMMITTING", "INVALID", "FUTURE").forEach {
            assertFalse(isPostCoreDownloadOperationState(it))
        }
        assertEquals(listOf("CORE_COMMITTED", "ASSETS_ENRICHING", "FINALIZED", "DEGRADED_COMPLETE"),
            DownloadOperationStateTransitions.coreCommittedWireNames)
        assertEquals(setOf("CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE"),
            DownloadOperationStateTransitions.resumableCoreWireNames)
        assertEquals(setOf("RUNNING", "COMMITTING", "CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE"),
            DownloadOperationStateTransitions.interruptedWireNames)
    }
}
