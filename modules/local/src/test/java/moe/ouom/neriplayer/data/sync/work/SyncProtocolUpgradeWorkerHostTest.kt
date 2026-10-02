package moe.ouom.neriplayer.data.sync.work

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerFailureClassifier
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SyncProtocolUpgradeWorkerHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `new installation permits background remote detection for both providers`() = runTest {
        val storeJob = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + storeJob),
            produceFile = { File(temporary.root, "fresh_worker.preferences_pb") }
        )
        try {
            val repository = SyncProtocolUpgradeRepository(store)
            var calls = 0
            for (provider in SyncProvider.entries) {
                val host = SyncRepositoryWorkerHost(provider, { true }, { true },
                    { repository.canSyncTarget("a".repeat(64)) }, { false }, { true }, {},
                    sync = { calls++; Result.success(moe.ouom.neriplayer.data.model.sync.SyncResult(success = true, message = "synced")) },
                    classifier = SyncWorkerFailureClassifier(emptyMap()),
                    readSilentFailure = { error("successful detection must not read notification settings") },
                    notifyFailure = { error("successful detection must not notify") })
                assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
            }
            assertEquals(SyncProvider.entries.size, calls)
        } finally {
            storeJob.cancelAndJoin()
        }
    }

    @Test
    fun `detected legacy target stops both worker hosts before playback network or notification callbacks`() = runTest {
        val storeJob = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + storeJob),
            produceFile = { File(temporary.root, "worker_approval.preferences_pb") }
        )
        try {
            val repository = SyncProtocolUpgradeRepository(store)
            val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))
            assertTrue(runCatching { repository.requireLegacyMigration(challenge) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            for (provider in SyncProvider.entries) {
                val host = SyncRepositoryWorkerHost(
                    provider = provider, readAutoSync = { true }, readConfigured = { true },
                    readProtocolUpgradeApproved = { repository.canSyncTarget(challenge.targetId) },
                    readPlayback = { error("pending approval must not check playback") },
                    readNetwork = { error("pending approval must not check network") },
                    defer = { error("pending approval must not defer work") },
                    sync = { error("pending approval must not start sync") },
                    classifier = SyncWorkerFailureClassifier(emptyMap()),
                    readSilentFailure = { error("pending approval must not read notification settings") },
                    notifyFailure = { error("pending approval must not notify") }
                )
                for ((force, user) in listOf(false to false, true to false, false to true, true to true)) {
                    assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(force, user))
                }
            }
        } finally {
            storeJob.cancelAndJoin()
        }
    }

    @Test
    fun `pending protocol approval finishes every worker quietly without retry or preference reads`() = runTest {
        for (provider in SyncProvider.entries) {
            val pending = SyncProtocolUpgradeRequiredException("upgrade required")
            val host = SyncRepositoryWorkerHost(
                provider, { true }, { true }, { true }, { false }, { true }, { },
                sync = { Result.failure(pending) },
                classifier = SyncWorkerFailureClassifier(mapOf(
                    SyncProtocolUpgradeRequiredException::class.java to SyncWorkerFailureKind.AUTHENTICATION
                )),
                readSilentFailure = { error("pending approval must not read notification settings") },
                notifyFailure = { error("pending approval must not notify") }
            )
            assertEquals(pending, host.synchronize().exceptionOrNull())
            for (manual in listOf(false, true)) {
                for (unexpected in listOf(false, true)) {
                    assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(pending, manual, unexpected))
                }
            }
        }
    }

    @Test
    fun `pending approval does not swallow subsequent real authentication failures`() = runTest {
        val authenticationFailures = listOf(
            SyncProvider.GITHUB to TokenExpiredException("token expired"),
            SyncProvider.WEBDAV to WebDavAuthException("credentials rejected")
        )
        for ((provider, failure) in authenticationFailures) {
            val notifications = mutableListOf<Throwable?>()
            val host = SyncRepositoryWorkerHost(
                provider, { true }, { true }, { true }, { false }, { true }, { },
                sync = { Result.failure(failure) },
                classifier = SyncWorkerFailureClassifier(mapOf(
                    TokenExpiredException::class.java to SyncWorkerFailureKind.AUTHENTICATION,
                    WebDavAuthException::class.java to SyncWorkerFailureKind.AUTHENTICATION
                )),
                readSilentFailure = { error("authentication failures must not be muted") },
                notifyFailure = { notifications += it }
            )
            val pending = SyncProtocolUpgradeRequiredException("upgrade required")
            assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(pending, manual = false, unexpected = false))
            assertTrue(notifications.isEmpty())
            assertEquals(SyncWorkerOutcome.FAILURE, host.handleFailure(failure, manual = false, unexpected = false))
            assertEquals(listOf(failure), notifications)
            assertEquals(SyncWorkerOutcome.RETRY, host.handleFailure(failure, manual = true, unexpected = true))
            assertEquals(listOf(failure, failure), notifications)
        }
    }

    @Test
    fun `ordinary GitHub failure waits for asynchronous notification preference and still retries`() = runTest {
        val failure = IOException("connection lost")
        val notifications = mutableListOf<Throwable?>()
        val preferenceRequested = CompletableDeferred<Unit>()
        val preferenceValue = CompletableDeferred<Boolean>()
        val host = SyncRepositoryWorkerHost(
            SyncProvider.GITHUB, { true }, { true }, { true }, { false }, { true }, { },
            sync = { Result.failure(failure) }, classifier = SyncWorkerFailureClassifier(emptyMap()),
            readSilentFailure = {
                preferenceRequested.complete(Unit)
                preferenceValue.await()
            },
            notifyFailure = { notifications += it }
        )
        val pending = SyncProtocolUpgradeRequiredException("upgrade required")
        assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(pending, manual = false, unexpected = false))
        assertFalse(preferenceRequested.isCompleted)
        val handling = async { host.handleFailure(failure, manual = false, unexpected = false) }
        preferenceRequested.await()
        assertFalse(handling.isCompleted)
        assertTrue(notifications.isEmpty())
        preferenceValue.complete(false)
        assertEquals(SyncWorkerOutcome.RETRY, handling.await())
        assertEquals(listOf(failure), notifications)
    }

    @Test
    fun `ordinary WebDAV automatic failure retries quietly after pending approval`() = runTest {
        val failure = IOException("connection lost")
        val notifications = mutableListOf<Throwable?>()
        val host = SyncRepositoryWorkerHost(
            SyncProvider.WEBDAV, { true }, { true }, { true }, { false }, { true }, { },
            sync = { Result.failure(failure) }, classifier = SyncWorkerFailureClassifier(emptyMap()),
            readSilentFailure = { error("WebDAV failures must not read GitHub notification settings") },
            notifyFailure = { notifications += it }
        )
        val pending = SyncProtocolUpgradeRequiredException("upgrade required")
        assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(pending, manual = false, unexpected = false))
        assertEquals(SyncWorkerOutcome.RETRY, host.handleFailure(failure, manual = false, unexpected = false))
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `ordinary manual failures still retry and notify without reading mute settings`() = runTest {
        for (provider in SyncProvider.entries) {
            val failure = IOException("connection lost")
            val notifications = mutableListOf<Throwable?>()
            val host = SyncRepositoryWorkerHost(
                provider, { true }, { true }, { true }, { false }, { true }, { },
                sync = { Result.failure(failure) }, classifier = SyncWorkerFailureClassifier(emptyMap()),
                readSilentFailure = { error("manual failures must not be muted") },
                notifyFailure = { notifications += it }
            )
            val pending = SyncProtocolUpgradeRequiredException("upgrade required")
            assertEquals(SyncWorkerOutcome.SUCCESS, host.handleFailure(pending, manual = true, unexpected = false))
            assertTrue(notifications.isEmpty())
            assertEquals(SyncWorkerOutcome.RETRY, host.handleFailure(failure, manual = true, unexpected = false))
            assertEquals(listOf(failure), notifications)
        }
    }
}
