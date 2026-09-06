package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DownloadRetryDeadlineWakeCoordinatorTest {
    @Test
    fun `coalesces later deadlines and replaces with an earlier one`() {
        val context = mockContext()
        val scheduledDelays = mutableListOf<Long>()
        val coordinator = DownloadRetryDeadlineWakeCoordinator(
            nowMs = { 1_000L },
            schedulePump = { _, delayMs ->
                scheduledDelays += delayMs
                true
            }
        )

        assertTrue(coordinator.schedule(context, 3_000L))
        assertFalse(coordinator.schedule(context, 5_000L))
        assertTrue(coordinator.schedule(context, 2_000L))

        assertEquals(listOf(2_000L, 1_000L), scheduledDelays)
        assertEquals(2_000L, coordinator.scheduledDeadlineForTests())
    }

    @Test
    fun `failed scheduling leaves no reservation and pump start permits retry`() {
        val context = mockContext()
        var attempts = 0
        val coordinator = DownloadRetryDeadlineWakeCoordinator(
            nowMs = { 4_000L },
            schedulePump = { _, _ ->
                attempts++
                attempts > 1
            }
        )

        assertFalse(coordinator.schedule(context, 5_000L))
        assertEquals(null, coordinator.scheduledDeadlineForTests())
        assertTrue(coordinator.schedule(context, 5_000L))
        coordinator.onPumpStarted()
        assertEquals(null, coordinator.scheduledDeadlineForTests())
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }
}
