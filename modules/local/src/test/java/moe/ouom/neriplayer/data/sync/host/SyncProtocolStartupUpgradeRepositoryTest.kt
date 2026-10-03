package moe.ouom.neriplayer.data.sync.host

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncProtocolStartupUpgradeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private val third = "c".repeat(64)

    @Test
    fun `first startup captures configured targets once and grants no content permission`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())

        repository.initializeStartupTargets(setOf(first))
        repository.initializeStartupTargets(setOf(first, second))

        assertEquals(setOf(first), repository.startupPendingFlow.first())
        assertNull(repository.pendingChallengeFlow.first())
        assertFalse(repository.canSyncTarget(first))
        assertTrue(repository.canSyncTarget(second))
        val challenge = challenge(first)
        assertTrue(runCatching { repository.requireLegacyMigration(challenge) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertEquals(challenge, repository.pendingChallengeFlow.first())
    }

    @Test
    fun `new installation stays current after configuring a later sync target`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())

        repository.initializeStartupTargets(emptySet())
        repository.initializeStartupTargets(setOf(first))

        assertTrue(repository.startupPendingFlow.first().isEmpty())
        assertTrue(repository.approvedFlow.first())
        assertEquals(4, repository.versionFlow(first).first())
        assertTrue(repository.canSyncTarget(first))
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
    }

    @Test
    fun `startup capture skips current and detected targets while approved legacy targets remain visible`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())
        repository.markCurrent(first)
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(second)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(third)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        repository.confirmAllDevicesUpdated(true, challenge(third))

        repository.initializeStartupTargets(setOf(first, second, third, "d".repeat(64)))

        assertEquals(setOf(third, "d".repeat(64)), repository.startupPendingFlow.first())
        assertEquals(challenge(second), repository.pendingChallengeFlow.first())
        repository.requireLegacyMigration(challenge(third))
    }

    @Test
    fun `approved legacy target remains a startup prompt after completion until current format is observed`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())
        repository.initializeStartupTargets(emptySet())
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        repository.confirmAllDevicesUpdated(true, challenge(first))

        repository.completeStartupUpgrade(first)

        assertEquals(setOf(first), repository.startupPendingFlow.first())
        assertTrue(repository.canSyncTarget(first))
        assertTrue(repository.approvedFlow.first())
        assertEquals(0, repository.versionFlow(first).first())
        repository.requireLegacyMigration(challenge(first))
        repository.markCurrent(first)
        assertTrue(repository.startupPendingFlow.first().isEmpty())
        assertEquals(4, repository.versionFlow(first).first())
    }

    @Test
    fun `approved migration unfinished before restart prompts again without blocking its safe background retry`() = runTest {
        val file = File(temporary.root, "unfinished.preferences_pb")
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(emptySet())
            repository.markCurrent(first)
            assertTrue(runCatching { repository.requireLegacyMigration(challenge(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            repository.confirmAllDevicesUpdated(true, challenge(first))
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(emptySet())
            assertEquals(setOf(first), repository.startupPendingFlow.first())
            assertTrue(repository.canSyncTarget(first))
            assertEquals(0, repository.versionFlow(first).first())
            repository.requireLegacyMigration(challenge(first))
            repository.markCurrent(first)
        }
        withFileStore(file) { store ->
            assertTrue(SyncProtocolUpgradeRepository(store).startupPendingFlow.first().isEmpty())
        }
    }

    @Test
    fun `configured old installation remains traditional and blocks background sync until handled`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore(preferencesOf(
            StartupVersion to 3, StartupTargets to setOf(first)
        )))

        assertFalse(repository.approvedFlow.first())
        assertFalse(repository.canSyncTarget(first))
        assertTrue(repository.canSyncTarget(second))
        assertEquals(0, repository.versionFlow(first).first())
        assertEquals(4, repository.versionFlow(second).first())
        assertEquals("probe", repository.executeIfApproved { Result.success("probe") }.getOrThrow())
    }

    @Test
    fun `observing current remote data durably clears startup upgrade candidate`() = runTest {
        val store = StartupDataStore(preferencesOf(StartupVersion to 3, StartupTargets to setOf(first, second)))
        val repository = SyncProtocolUpgradeRepository(store)

        repository.markCurrent(first)

        assertEquals(setOf(second), store.data.first()[StartupTargets])
        assertEquals(4, store.data.first()[intPreferencesKey("observed_protocol_$first")])
        assertTrue(repository.canSyncTarget(first))
        assertFalse(repository.canSyncTarget(second))
    }

    @Test
    fun `future startup registration cannot sync or downgrade upgrade state`() = runTest {
        val initial = preferencesOf(StartupVersion to 5, StartupTargets to setOf(first))
        val store = StartupDataStore(initial)
        val repository = SyncProtocolUpgradeRepository(store)

        assertFalse(repository.approvedFlow.first())
        assertFalse(repository.canSyncTarget(first))
        assertEquals(5, repository.versionFlow(first).first())
        assertTrue(repository.executeIfApproved<String> { error("must not sync") }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertTrue(runCatching { repository.markCurrent(first) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertTrue(runCatching { repository.initializeStartupTargets(setOf(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertTrue(runCatching { repository.completeStartupUpgrade(first) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertTrue(runCatching { repository.startupPendingFlow.first() }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertEquals(initial, store.data.first())
    }

    @Test
    fun `completing startup prompt cannot authorize a pending or future legacy challenge`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())
        repository.initializeStartupTargets(setOf(first, second))
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)

        repository.completeStartupUpgrade(first)

        assertEquals(setOf(second), repository.startupPendingFlow.first())
        assertEquals(challenge(first), repository.pendingChallengeFlow.first())
        assertFalse(repository.canSyncTarget(first))
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(second)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        repository.completeStartupUpgrade(second)
        assertTrue(repository.startupPendingFlow.first().isEmpty())
        assertFalse(repository.approvedFlow.first())
    }

    @Test
    fun `completed startup target cannot be registered again and still detects legacy content`() = runTest {
        val repository = SyncProtocolUpgradeRepository(StartupDataStore())
        repository.initializeStartupTargets(setOf(first))
        repository.completeStartupUpgrade(first)
        repository.initializeStartupTargets(setOf(first, second))

        assertTrue(repository.startupPendingFlow.first().isEmpty())
        assertTrue(repository.canSyncTarget(first))
        assertTrue(repository.approvedFlow.first())
        assertTrue(runCatching { repository.requireLegacyMigration(challenge(first)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
    }

    @Test
    fun `first startup registration persists its original target set after reopen`() = runTest {
        val file = File(temporary.root, "startup.preferences_pb")
        withFileStore(file) { store ->
            SyncProtocolUpgradeRepository(store).initializeStartupTargets(setOf(first))
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(setOf(second))
            assertEquals(setOf(first), repository.startupPendingFlow.first())
            repository.completeStartupUpgrade(first)
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(setOf(first, second))
            assertTrue(repository.startupPendingFlow.first().isEmpty())
        }
    }

    @Test
    fun `failed or cancelled startup registration preserves first registration for retry`() = runTest {
        for (failure in listOf(IOException("disk full"), CancellationException("cancelled"))) {
            val store = StartupDataStore().apply { writeFailure = failure }
            val repository = SyncProtocolUpgradeRepository(store)
            assertSame(failure, runCatching { repository.initializeStartupTargets(setOf(first)) }.exceptionOrNull())
            assertNull(store.data.first()[StartupVersion])
            assertTrue(repository.startupPendingFlow.first().isEmpty())
            store.writeFailure = null
            repository.initializeStartupTargets(setOf(second))
            assertEquals(setOf(second), repository.startupPendingFlow.first())
        }
    }

    @Test
    fun `failed startup completion keeps background sync paused until retry`() = runTest {
        val store = StartupDataStore()
        val repository = SyncProtocolUpgradeRepository(store)
        repository.initializeStartupTargets(setOf(first))
        val failure = IOException("disk full")
        store.writeFailure = failure

        assertSame(failure, runCatching { repository.completeStartupUpgrade(first) }.exceptionOrNull())
        assertEquals(setOf(first), repository.startupPendingFlow.first())
        assertFalse(repository.canSyncTarget(first))
        store.writeFailure = null
        repository.completeStartupUpgrade(first)
        assertTrue(repository.startupPendingFlow.first().isEmpty())
    }

    @Test
    fun `startup registration rejects malformed targets before changing persistent state`() = runTest {
        val store = StartupDataStore()
        val repository = SyncProtocolUpgradeRepository(store)

        assertTrue(runCatching { repository.initializeStartupTargets(setOf("invalid")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyPreferences(), store.data.first())
        repository.initializeStartupTargets(setOf(first))
        assertEquals(setOf(first), repository.startupPendingFlow.first())
    }

    private fun challenge(target: String) = SyncProtocolUpgradeChallenge(target, "1".repeat(64))

    private suspend fun TestScope.withFileStore(file: File, action: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job), produceFile = { file }
        )
        try { action(store) } finally { job.cancelAndJoin() }
    }

    private class StartupDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var writeFailure: Exception? = null
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            if (updated != state.value) writeFailure?.let { throw it }
            return updated.also { state.value = it }
        }
    }

    private companion object {
        val StartupVersion = intPreferencesKey("startup_registration_version")
        val StartupTargets = stringSetPreferencesKey("startup_legacy_targets")
    }
}
