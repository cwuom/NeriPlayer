package moe.ouom.neriplayer.ui.sync.upgrade

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncUpgradeImmediateSyncTest {
    @Test
    fun `a successful sync verifies the current protocol for the same address`() = runTest {
        val host = Host(Result.success(success))

        assertSame(success, host.runner.execute(target, false).getOrThrow())
        assertEquals(listOf(target), host.protocolTargets)
    }

    @Test
    fun `a mutation conflict success while still legacy remains a retryable upgrade failure`() = runTest {
        val host = Host(Result.success(SyncResult(true, "local changes need another sync")))
        host.currentProtocol = false

        val error = host.runner.execute(target, false).exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("Sync database upgrade did not finish", error?.message)
        assertEquals(1, host.syncTargets.size)
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `the migration retry must publish the current protocol before claiming completion`() = runTest {
        val host = Host(Result.failure(upgrade()), Result.success(success))
        host.currentProtocol = false

        assertTrue(host.runner.execute(target, true).exceptionOrNull() is IOException)
        assertEquals(listOf(true to challenge), host.approvals)
        assertEquals(listOf(target), host.protocolTargets)
        assertEquals(2, host.syncTargets.size)
    }

    @Test
    fun `a final protocol read failure remains a failure rather than a completed upgrade`() = runTest {
        val unavailable = IOException("protocol storage unavailable")
        val host = Host(Result.success(success))
        host.protocolError = unavailable

        assertSame(unavailable, host.runner.execute(target, false).exceptionOrNull())
        assertEquals(listOf(target), host.protocolTargets)
    }

    @Test
    fun `a cancelled final protocol read propagates without claiming completion`() = runTest {
        val cancelled = CancellationException("cancelled")
        val host = Host(Result.success(success))
        host.protocolError = cancelled

        assertSame(cancelled, expectCancellation { host.runner.execute(target, false) })
    }

    @Test
    fun `a failed synchronization never reads or changes its protocol completion status`() = runTest {
        val offline = IOException("offline")
        val unsuccessful = SyncResult(false, "still conflicted")
        for (result in listOf(Result.failure(offline), Result.success(unsuccessful))) {
            val host = Host(result)
            host.protocolError = IOException("must not inspect protocol")

            assertEquals(result, host.runner.execute(target, false))
            assertTrue(host.protocolTargets.isEmpty())
        }
    }

    @Test
    fun `an empty or current remote synchronizes once without a migration approval`() = runTest {
        val host = Host(Result.success(success))

        assertSame(success, host.runner.execute(target, true).getOrThrow())
        assertEquals(listOf("active", "sync"), host.events)
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `a known challenge confirmation cannot silently approve changed contents`() = runTest {
        val required = upgrade(challenge.copy(fingerprint = "2".repeat(64)))
        val host = Host(Result.failure(required))

        assertSame(required, host.runner.execute(target, false).exceptionOrNull())
        assertEquals(listOf("active", "sync"), host.events)
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `startup confirmation probes approves its exact fingerprint and synchronizes immediately`() = runTest {
        val host = Host(Result.failure(upgrade()), Result.success(success))

        assertSame(success, host.runner.execute(target, true).getOrThrow())
        assertEquals(listOf("active", "sync", "active", "confirm", "active", "sync"), host.events)
        assertEquals(listOf(true to challenge), host.approvals)
        assertEquals(listOf(target, target), host.syncTargets)
    }

    @Test
    fun `a challenge for another address never inherits startup confirmation`() = runTest {
        val required = upgrade(challenge.copy(targetId = "b".repeat(64)))
        val host = Host(Result.failure(required))

        assertSame(required, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.approvals.isEmpty())
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `an unsupported version without an exact challenge is never approved`() = runTest {
        val required = SyncProtocolUpgradeRequiredException("unsupported version")
        val host = Host(Result.failure(required))

        assertSame(required, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `a changed fingerprint during the retry requires a fresh declaration`() = runTest {
        val changed = upgrade(challenge.copy(fingerprint = "2".repeat(64)))
        val host = Host(Result.failure(upgrade()), Result.failure(changed))

        assertSame(changed, host.runner.execute(target, true).exceptionOrNull())
        assertEquals(listOf(true to challenge), host.approvals)
        assertEquals(2, host.syncTargets.size)
    }

    @Test
    fun `a second identical challenge does not produce an approval loop`() = runTest {
        val repeated = upgrade()
        val host = Host(Result.failure(upgrade()), Result.failure(repeated))

        assertSame(repeated, host.runner.execute(target, true).exceptionOrNull())
        assertEquals(1, host.approvals.size)
        assertEquals(2, host.syncTargets.size)
    }

    @Test
    fun `a reported unsuccessful sync retains its result and detail`() = runTest {
        val unsuccessful = SyncResult(success = false, message = "conflict remains", songsAdded = 4)
        val host = Host(Result.success(unsuccessful))

        assertSame(unsuccessful, host.runner.execute(target, true).getOrThrow())
        assertEquals(1, host.syncTargets.size)
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `a removed target stops before performing any synchronization`() = runTest {
        val host = Host()
        host.active = false

        assertTrue(host.runner.execute(target, true).isFailure)
        assertEquals(listOf("active"), host.events)
        assertTrue(host.syncTargets.isEmpty())
    }

    @Test
    fun `a target removed while probing is not approved`() = runTest {
        val host = Host(Result.failure(upgrade()))
        host.afterSync = { host.active = false }

        assertTrue(host.runner.execute(target, true).isFailure)
        assertEquals(listOf("active", "sync", "active"), host.events)
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `a target removed during the durable approval is not synchronized again`() = runTest {
        val host = Host(Result.failure(upgrade()))
        host.afterApproval = { host.active = false }

        assertTrue(host.runner.execute(target, true).isFailure)
        assertEquals(listOf("active", "sync", "active", "confirm", "active"), host.events)
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `an ordinary network result failure is returned without retry or approval`() = runTest {
        val offline = IOException("offline")
        val host = Host(Result.failure(offline))

        assertSame(offline, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.approvals.isEmpty())
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `a thrown network failure is returned as a result`() = runTest {
        val offline = IOException("offline")
        val host = Host()
        host.syncError = offline

        assertSame(offline, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.approvals.isEmpty())
    }

    @Test
    fun `a failed durable approval does not start the retry`() = runTest {
        val unavailable = IOException("approval storage unavailable")
        val host = Host(Result.failure(upgrade()))
        host.approvalError = unavailable

        assertSame(unavailable, host.runner.execute(target, true).exceptionOrNull())
        assertEquals(1, host.syncTargets.size)
        assertEquals(listOf("active", "sync", "active", "confirm"), host.events)
    }

    @Test
    fun `a failed configuration read returns its failure and never probes`() = runTest {
        val unavailable = IOException("configuration unavailable")
        val host = Host()
        host.activeError = unavailable

        assertSame(unavailable, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.syncTargets.isEmpty())
    }

    @Test
    fun `a configuration read failure after probing prevents approval`() = runTest {
        val unavailable = IOException("configuration unavailable")
        val host = Host(Result.failure(upgrade()))
        host.afterSync = { host.activeError = unavailable }

        assertSame(unavailable, host.runner.execute(target, true).exceptionOrNull())
        assertTrue(host.approvals.isEmpty())
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `a configuration read failure after approval prevents the retry`() = runTest {
        val unavailable = IOException("configuration unavailable")
        val host = Host(Result.failure(upgrade()))
        host.afterApproval = { host.activeError = unavailable }

        assertSame(unavailable, host.runner.execute(target, true).exceptionOrNull())
        assertEquals(listOf(true to challenge), host.approvals)
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `a fingerprint changed during durable approval is returned without another approval`() = runTest {
        val changed = upgrade(challenge.copy(fingerprint = "2".repeat(64)))
        val host = Host(Result.failure(upgrade()))
        host.approvalError = changed

        assertSame(changed, host.runner.execute(target, true).exceptionOrNull())
        assertEquals(listOf(true to challenge), host.approvals)
        assertEquals(1, host.syncTargets.size)
    }

    @Test
    fun `cancellation returned by either synchronization attempt is rethrown`() = runTest {
        for (cancelRetry in listOf(false, true)) {
            val cancellation = CancellationException("cancelled")
            val host = if (cancelRetry) Host(Result.failure(upgrade()), Result.failure(cancellation))
                else Host(Result.failure(cancellation))

            assertSame(cancellation, expectCancellation { host.runner.execute(target, true) })
            assertEquals(if (cancelRetry) 1 else 0, host.approvals.size)
        }
    }

    @Test
    fun `cancellation from configuration synchronization or approval propagates unchanged`() = runTest {
        for (stage in listOf("active", "sync", "confirm")) {
            val cancellation = CancellationException("cancelled")
            val host = Host(Result.failure(upgrade()))
            when (stage) {
                "active" -> host.activeError = cancellation
                "sync" -> host.syncError = cancellation
                "confirm" -> host.approvalError = cancellation
            }

            assertSame(cancellation, expectCancellation { host.runner.execute(target, true) })
            assertTrue(host.syncTargets.size <= 1)
        }
    }

    private suspend fun expectCancellation(action: suspend () -> Unit): CancellationException {
        try {
            action()
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            return error
        }
        error("Cancellation must propagate")
    }

    private class Host(first: Result<SyncResult>? = null, second: Result<SyncResult>? = null) {
        private val remaining = listOfNotNull(first, second).toMutableList()
        val events = mutableListOf<String>()
        val approvals = mutableListOf<Pair<Boolean, SyncProtocolUpgradeChallenge>>()
        val syncTargets = mutableListOf<String>()
        val protocolTargets = mutableListOf<String>()
        var active = true
        var currentProtocol = true
        var activeError: Exception? = null
        var syncError: Exception? = null
        var approvalError: Exception? = null
        var protocolError: Exception? = null
        var afterSync: () -> Unit = { }
        var afterApproval: () -> Unit = { }
        val runner = SyncUpgradeImmediateSync(
            performSync = { targetId ->
                events += "sync"
                syncTargets += targetId
                syncError?.let { throw it }
                remaining.removeAt(0).also { afterSync() }
            },
            confirmLegacy = { updated, value ->
                events += "confirm"
                approvals += updated to value
                approvalError?.let { throw it }
                afterApproval()
            },
            isTargetActive = { targetId ->
                assertEquals(target, targetId)
                events += "active"
                activeError?.let { throw it }
                active
            },
            hasCurrentProtocol = { targetId ->
                protocolTargets += targetId
                protocolError?.let { throw it }
                currentProtocol
            }
        )
    }

    private fun upgrade(value: SyncProtocolUpgradeChallenge = challenge) =
        SyncProtocolUpgradeRequiredException("legacy upgrade required", value)

    private companion object {
        val target = "a".repeat(64)
        val challenge = SyncProtocolUpgradeChallenge(target, "1".repeat(64))
        val success = SyncResult(success = true, message = "completed", songsAdded = 5)
    }
}
