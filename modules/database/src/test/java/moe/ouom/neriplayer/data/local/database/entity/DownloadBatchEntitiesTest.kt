package moe.ouom.neriplayer.data.local.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadBatchEntitiesTest {
    private fun batch(
        batchId: String = "batch",
        generation: Long = 1L,
        totalCount: Int = 1,
        stateBits: Int = DownloadBatchState.OPEN
    ) = DownloadBatchEntity(batchId, generation, totalCount, stateBits, 0L, null, 0L, 0L)

    private fun member(
        ordinal: Int = 0,
        stableKey: String = "song",
        terminalBits: Int = DownloadBatchMemberTerminal.NONE,
        maxFractionMilli: Int = 0
    ) = DownloadBatchMemberEntity("batch", ordinal, stableKey, terminalBits, maxFractionMilli, updatedAtMs = 0L)

    @Test fun `batches accept open and single terminal states`() {
        for (state in listOf(0, DownloadBatchState.OPEN or DownloadBatchState.USER_MOBILE_ALLOWED,
            DownloadBatchState.COMPLETED, DownloadBatchState.CANCELLED)) {
            assertEquals(state, batch(stateBits = state).stateBits)
        }
    }

    @Test fun `batches reject blank ids, empty generations and counts, and mixed terminal states`() {
        assertThrows(IllegalArgumentException::class.java) { batch(batchId = " ") }
        assertThrows(IllegalArgumentException::class.java) { batch(generation = 0L) }
        assertThrows(IllegalArgumentException::class.java) { batch(totalCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { batch(stateBits = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            batch(stateBits = DownloadBatchState.COMPLETED or DownloadBatchState.CANCELLED)
        }
    }

    @Test fun `members default to an untouched pending row`() {
        val created = DownloadBatchMemberEntity("batch", 0, "song", updatedAtMs = 5L)

        assertEquals(DownloadBatchMemberEntity("batch", 0, "song", 0, 0, false, null, null, 5L), created)
        assertEquals(1000, member(maxFractionMilli = 1000, terminalBits = DownloadBatchMemberTerminal.FAILED).maxFractionMilli)
    }

    @Test fun `members reject negative order, blank keys, unknown terminal bits and fractions outside a thousand`() {
        assertThrows(IllegalArgumentException::class.java) { member(ordinal = -1) }
        assertThrows(IllegalArgumentException::class.java) { member(stableKey = "") }
        assertThrows(IllegalArgumentException::class.java) { member(terminalBits = 8) }
        assertThrows(IllegalArgumentException::class.java) { member(maxFractionMilli = -1) }
        assertThrows(IllegalArgumentException::class.java) { member(maxFractionMilli = 1001) }
    }
}
