package moe.ouom.neriplayer.data.sync.work

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerFailureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryWorkerHostTest {
    @Test
    fun `ports read the injected source and defer only through host callback`() = runTest {
        val events = mutableListOf<String>()
        val host = SyncRepositoryWorkerHost(
            SyncProvider.GITHUB, readAutoSync = { false }, readConfigured = { true },
            readProtocolUpgradeApproved = { events += "approval"; false },
            readPlayback = { false }, readNetwork = { true }, defer = { events += "defer" },
            sync = { events += "sync"; Result.failure(IllegalStateException("offline")) },
            classifier = SyncWorkerFailureClassifier(emptyMap()), readSilentFailure = { false }, notifyFailure = { }
        )
        assertFalse(host.autoSyncEnabled())
        assertTrue(host.configured())
        assertFalse(host.protocolUpgradeApproved())
        assertFalse(host.playbackActive())
        assertTrue(host.validatedNetwork())
        host.deferForPlayback()
        assertTrue(host.synchronize().isFailure)
        assertEquals(listOf("approval", "defer", "sync"), events)
    }

    @Test
    fun `only automatic generic GitHub failure reads silent preference`() = runTest {
        for (provider in SyncProvider.entries) for (manual in listOf(false, true)) {
            val events = mutableListOf<String>()
            val host = SyncRepositoryWorkerHost(
                provider, { true }, { true }, { true }, { false }, { true }, { },
                sync = { error("unused") }, classifier = SyncWorkerFailureClassifier(mapOf(
                    SecurityException::class.java to SyncWorkerFailureKind.AUTHENTICATION,
                    UnsupportedOperationException::class.java to SyncWorkerFailureKind.MISSING_CONDITION,
                    IllegalStateException::class.java to SyncWorkerFailureKind.ALREADY_RUNNING
                )),
                readSilentFailure = { events += "silent"; true }, notifyFailure = { events += "notify" }
            )
            for (error in listOf(SecurityException(), UnsupportedOperationException(), IllegalStateException())) {
                host.handleFailure(error, manual, unexpected = false)
                assertFalse(events.contains("silent"))
                events.clear()
            }
            val outcome = host.handleFailure(null, manual, unexpected = false)
            assertEquals(SyncWorkerOutcome.RETRY, outcome)
            assertEquals(provider == SyncProvider.GITHUB && !manual, events.contains("silent"))
            assertEquals(manual, events.contains("notify"))
        }
    }
}
