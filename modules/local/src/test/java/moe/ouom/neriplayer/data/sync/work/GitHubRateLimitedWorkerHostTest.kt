package moe.ouom.neriplayer.data.sync.work

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubRateLimitException
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerHost
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitedWorkerHostTest {
    @Test
    fun `rate continuation uses server deadline and preserves manual origin without blocking`() = runTest {
        val delegate = FakeHost()
        val scheduled = mutableListOf<Pair<Long, Boolean>>()
        val host = GitHubRateLimitedWorkerHost(delegate, { delay, manual -> scheduled += delay to manual }, { 1_000L })
        assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(limited(91_000L), manual = true, unexpected = false))
        assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(limited(61_000L), manual = false, unexpected = false))
        assertEquals(listOf(90_000L to true, 60_000L to false), scheduled)
        assertTrue(delegate.failures.isEmpty())
        assertTrue(host.configured())
    }

    @Test
    fun `exhausted rate attempts notify through existing policy and stop continuation`() = runTest {
        val delegate = FakeHost()
        val scheduled = mutableListOf<Long>()
        val error = limited(90_000L, automatic = false)
        val host = GitHubRateLimitedWorkerHost(delegate, { delay, _ -> scheduled += delay })
        assertEquals(SyncWorkerOutcome.FAILURE, host.handleFailure(error, manual = true, unexpected = false))
        assertEquals(listOf(error), delegate.failures)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `other failures retain existing policy and expired deadlines avoid immediate loops`() = runTest {
        val delegate = FakeHost()
        val scheduled = mutableListOf<Long>()
        val host = GitHubRateLimitedWorkerHost(delegate, { delay, _ -> scheduled += delay }, { 1_000L })
        val error = IllegalStateException("offline")
        assertEquals(SyncWorkerOutcome.RETRY, host.handleFailure(error, manual = false, unexpected = true))
        assertEquals(listOf(error), delegate.failures)
        assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(limited(100L), manual = false, unexpected = false))
        assertEquals(listOf(1_000L), scheduled)
    }

    @Test
    fun `rate wrapper preserves pending approval and cannot start sync or schedule continuation`() = runTest {
        val delegate = FakeHost().apply { approved = false }
        val host = GitHubRateLimitedWorkerHost(delegate, { _, _ -> error("pending approval must not schedule continuation") })
        assertFalse(host.protocolUpgradeApproved())
        assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(forceSync = true, triggerByUserAction = false))
        assertEquals(0, delegate.syncCalls)
        assertTrue(delegate.failures.isEmpty())
    }

    private fun limited(retryAt: Long, automatic: Boolean = true) = GitHubRateLimitException(429, retryAt, automatic, "limited")

    private class FakeHost : SyncWorkerHost {
        val failures = mutableListOf<Throwable?>()
        var approved = true
        var syncCalls = 0
        override fun autoSyncEnabled(): Boolean = false
        override fun configured(): Boolean = true
        override suspend fun protocolUpgradeApproved(): Boolean = approved
        override fun playbackActive(): Boolean = false
        override fun validatedNetwork(): Boolean = true
        override fun deferForPlayback() = Unit
        override suspend fun synchronize(): Result<SyncResult> {
            syncCalls++
            return Result.failure(IllegalStateException("unused"))
        }
        override suspend fun handleFailure(error: Throwable?, manual: Boolean, unexpected: Boolean): SyncWorkerOutcome {
            failures += error
            return SyncWorkerOutcome.RETRY
        }
    }
}
