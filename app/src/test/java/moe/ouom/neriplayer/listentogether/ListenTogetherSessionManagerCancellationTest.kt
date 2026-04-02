package moe.ouom.neriplayer.listentogether

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSessionManagerCancellationTest {

    @Test
    fun `background reconnect jobs are cancelled together`() {
        val reconnectJob: CompletableJob = Job()
        val membershipRecoveryJob: CompletableJob = Job()

        cancelListenTogetherBackgroundJobs(reconnectJob, membershipRecoveryJob)

        assertTrue(reconnectJob.isCancelled)
        assertTrue(membershipRecoveryJob.isCancelled)
    }
}
