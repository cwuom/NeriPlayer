package moe.ouom.neriplayer.data.sync.schedule

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerExecutionTest {
    @Test
    fun `disabled automatic sync does not read remote or configured state`() = runTest {
        val host = Host().apply { automatic = false }
        assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
        assertEquals(listOf("automatic"), host.calls)
    }

    @Test
    fun `manual flags bypass automatic switch and playback deferral but still require configuration`() = runTest {
        for ((force, user) in listOf(true to false, false to true, true to true)) {
            val host = Host().apply { automatic = false; playback = true }
            assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(force, user))
            assertEquals(listOf("configured", "approval", "network", "sync"), host.calls)
        }
        val unconfigured = Host().apply { configured = false }
        assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(unconfigured).execute(true, false))
        assertEquals(listOf("configured"), unconfigured.calls)
    }

    @Test
    fun `automatic playback defers once before network checks`() = runTest {
        val host = Host().apply { playback = true }
        assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
        assertEquals(listOf("automatic", "configured", "approval", "playback", "defer"), host.calls)
    }

    @Test
    fun `unvalidated network retries without upload`() = runTest {
        val host = Host().apply { network = false }
        assertEquals(SyncWorkerOutcome.RETRY, SyncWorkerExecution(host).execute(false, false))
        assertTrue("sync" !in host.calls)
    }

    @Test
    fun `successful automatic sync reads eligibility in order`() = runTest {
        val host = Host()
        assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
        assertEquals(listOf("automatic", "configured", "approval", "playback", "network", "sync"), host.calls)
    }

    @Test
    fun `pending approval finishes manual and automatic work before playback or network decisions`() = runTest {
        for ((force, user) in listOf(false to false, true to false, false to true, true to true)) {
            for (playing in listOf(false, true)) {
                for (online in listOf(false, true)) {
                    val host = Host().apply { approved = false; playback = playing; network = online }
                    assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(force, user))
                    val eligibility = if (force || user) listOf("configured") else listOf("automatic", "configured")
                    assertEquals(eligibility + "approval", host.calls)
                    assertEquals(null, host.failure)
                }
            }
        }
    }

    @Test
    fun `cancelling a suspended approval read propagates before deferral notification or retry`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val readResult = CompletableDeferred<Boolean>()
        val host = Host().apply {
            playback = true
            network = false
            readApproval = { readStarted.complete(Unit); readResult.await() }
        }
        val execution = async { SyncWorkerExecution(host).execute(false, false) }
        readStarted.await()
        assertFalse(execution.isCompleted)
        assertEquals(listOf("automatic", "configured", "approval"), host.calls)
        execution.cancelAndJoin()
        assertTrue(runCatching { execution.await() }.exceptionOrNull() is CancellationException)
        assertEquals(null, host.failure)
        assertEquals(listOf("automatic", "configured", "approval"), host.calls)
    }

    @Test
    fun `approval read errors retain unexpected failure handling without checking network or syncing`() = runTest {
        val host = Host().apply {
            readApproval = { throw IOException("approval storage unavailable") }
            outcome = SyncWorkerOutcome.FAILURE
        }
        assertEquals(SyncWorkerOutcome.FAILURE, SyncWorkerExecution(host).execute(false, false))
        assertEquals(Triple("approval storage unavailable", false, true), host.failure)
        assertEquals(listOf("automatic", "configured", "approval"), host.calls)
    }

    @Test
    fun `sync failure retains manual flag and distinguishes unexpected exception`() = runTest {
        val returned = Host().apply { result = Result.failure(IOException("remote")); outcome = SyncWorkerOutcome.FAILURE }
        assertEquals(SyncWorkerOutcome.FAILURE, SyncWorkerExecution(returned).execute(false, true))
        assertEquals(Triple("remote", true, false), returned.failure)
        val thrown = Host().apply { error = IOException("startup") }
        assertEquals(SyncWorkerOutcome.RETRY, SyncWorkerExecution(thrown).execute(false, false))
        assertEquals(Triple("startup", false, true), thrown.failure)
    }

    @Test
    fun `cancellation is propagated without notifications or retry classification`() = runTest {
        val host = Host().apply { error = CancellationException("cancelled") }
        try {
            SyncWorkerExecution(host).execute(false, false)
            throw AssertionError("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(null, host.failure)
        }
    }

    private class Host : SyncWorkerHost {
        val calls = mutableListOf<String>()
        var automatic = true
        var configured = true
        var approved = true
        var readApproval: suspend () -> Boolean = { approved }
        var playback = false
        var network = true
        var result = Result.success(SyncResult(success = true, message = "synced"))
        var error: Exception? = null
        var outcome = SyncWorkerOutcome.RETRY
        var failure: Triple<String?, Boolean, Boolean>? = null
        override fun autoSyncEnabled(): Boolean { calls += "automatic"; return automatic }
        override fun configured(): Boolean { calls += "configured"; return configured }
        override suspend fun protocolUpgradeApproved(): Boolean { calls += "approval"; return readApproval() }
        override fun playbackActive(): Boolean { calls += "playback"; return playback }
        override fun validatedNetwork(): Boolean { calls += "network"; return network }
        override fun deferForPlayback() { calls += "defer" }
        override suspend fun synchronize(): Result<SyncResult> { calls += "sync"; error?.let { throw it }; return result }
        override suspend fun handleFailure(error: Throwable?, manual: Boolean, unexpected: Boolean): SyncWorkerOutcome {
            failure = Triple(error?.message, manual, unexpected)
            return outcome
        }
    }
}
