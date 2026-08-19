package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.listentogether.session.isCurrentListenTogetherCoalescedControlJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherCoalescedControlPolicyTest {

    @Test
    fun `only the current coalesced job may consume its pending event`() {
        val oldJob = Job()
        val currentJob = Job()

        try {
            assertFalse(
                isCurrentListenTogetherCoalescedControlJob(
                    currentJob = currentJob,
                    completingJob = oldJob
                )
            )
            assertTrue(
                isCurrentListenTogetherCoalescedControlJob(
                    currentJob = currentJob,
                    completingJob = currentJob
                )
            )
        } finally {
            oldJob.cancel()
            currentJob.cancel()
        }
    }

    @Test
    fun `missing coalesced job cannot consume a pending event`() {
        val completingJob = Job()

        try {
            assertFalse(
                isCurrentListenTogetherCoalescedControlJob(
                    currentJob = null,
                    completingJob = completingJob
                )
            )
        } finally {
            completingJob.cancel()
        }
    }
}
