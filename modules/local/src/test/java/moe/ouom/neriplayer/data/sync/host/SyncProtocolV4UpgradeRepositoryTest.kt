package moe.ouom.neriplayer.data.sync.host

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncProtocolV4UpgradeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = "a".repeat(64)
    private val other = "b".repeat(64)
    private val fingerprint = "1".repeat(64)
    private val challenge = SyncProtocolUpgradeChallenge(target, fingerprint, fromVersion = 3, toVersion = 4)

    @Test fun `existing observed V3 targets register for V4 and remain V3 after reopening`() = runTest {
        val file = File(temporary.root, "observed-v3.preferences_pb")
        withStore(file) { store ->
            store.edit {
                it[intPreferencesKey("startup_registration_version")] = 3
                it[stringSetPreferencesKey("observed_current_targets")] = setOf(target)
            }
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(setOf(target, other))
            assertEquals(3, repository.versionFlow(target).first())
            assertEquals(setOf(target, other), repository.startupPendingFlow.first())
            assertFalse(repository.canSyncTarget(target))
        }
        withStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(setOf(target, other))
            assertEquals(3, repository.versionFlow(target).first())
            assertEquals(4, store.data.first()[intPreferencesKey("startup_registration_version")])
            assertEquals(setOf(target, other), repository.startupPendingFlow.first())
        }
    }

    @Test fun `V3 observation cannot complete a V4 migration and V4 completion is target specific`() = runTest {
        withStore(File(temporary.root, "versions.preferences_pb")) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.initializeStartupTargets(setOf(target, other))
            assertRequired(repository, challenge)
            repository.confirmAllDevicesUpdated(true, challenge)
            repository.markCurrent(target, 3)
            assertEquals(3, repository.versionFlow(target).first())
            assertTrue(target in repository.startupPendingFlow.first())
            repository.requireLegacyMigration(challenge)
            repository.markCurrent(target, 4)
            assertEquals(4, repository.versionFlow(target).first())
            assertEquals(setOf(other), repository.startupPendingFlow.first())
            assertTrue(repository.canSyncTarget(target))
            assertRequired(repository, challenge)
        }
    }

    @Test fun `approval binds source and destination versions as well as target and fingerprint`() = runTest {
        withStore(File(temporary.root, "approval.preferences_pb")) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            assertRequired(repository, challenge)
            repository.confirmAllDevicesUpdated(true, challenge)
            repository.requireLegacyMigration(challenge)
            assertRequired(repository, challenge.copy(fromVersion = 0))
            assertEquals(0, repository.versionFlow(target).first())
            assertRequired(repository, challenge.copy(targetId = other))
            assertTrue(runCatching {
                repository.confirmAllDevicesUpdated(true, challenge.copy(toVersion = 5))
            }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        }
    }

    @Test fun `old fingerprint approval cannot authorize a new V4 migration`() = runTest {
        withStore(File(temporary.root, "old-approval.preferences_pb")) { store ->
            store.edit {
                it[intPreferencesKey("approved_protocol_version")] = 3
                it[stringPreferencesKey("approved_legacy_$target")] = fingerprint
            }
            val repository = SyncProtocolUpgradeRepository(store)
            assertRequired(repository, challenge.copy(fromVersion = 0))
            repository.confirmAllDevicesUpdated(true, challenge.copy(fromVersion = 0))
            repository.requireLegacyMigration(challenge.copy(fromVersion = 0))
        }
    }

    @Test fun `future and malformed observed versions cannot be published as current`() = runTest {
        withStore(File(temporary.root, "invalid-version.preferences_pb")) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            for (version in listOf(-1, 0, 2, 5)) {
                assertTrue(runCatching { repository.markCurrent(target, version) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(store.data.first().asMap().isEmpty())
        }
    }

    @Test fun `future observed state cannot be synced or overwritten after reopening`() = runTest {
        val file = File(temporary.root, "future-observed.preferences_pb")
        withStore(file) { store ->
            store.edit { it[intPreferencesKey("observed_protocol_$target")] = 5 }
        }
        withStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            assertEquals(5, repository.versionFlow(target).first())
            assertFalse(repository.canSyncTarget(target))
            assertTrue(runCatching { repository.markCurrent(target) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.initializeStartupTargets(setOf(target)) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertEquals(5, store.data.first()[intPreferencesKey("observed_protocol_$target")])
        }
    }

    @Test fun `future startup and migration state blocks every target without changing saved state`() = runTest {
        val states = mapOf<String, (MutablePreferences) -> Unit>(
            "startup" to { it[intPreferencesKey("startup_registration_version")] = 5 },
            "pending" to { it[stringPreferencesKey("pending_legacy_$other")] = "3:5:$fingerprint" },
            "approval" to { it[stringPreferencesKey("approved_legacy_$other")] = "3:5:$fingerprint" }
        )
        for ((name, initialize) in states) {
            val file = File(temporary.root, "future-$name.preferences_pb")
            lateinit var saved: Preferences
            withStore(file) { store ->
                store.edit { initialize(it) }
                saved = store.data.first()
            }
            repeat(2) {
                withStore(file) { store ->
                    val repository = SyncProtocolUpgradeRepository(store)
                    assertEquals(5, repository.versionFlow(target).first())
                    assertEquals(5, repository.versionFlow(other).first())
                    assertFalse(repository.approvedFlow.first())
                    assertFalse(repository.canSyncTarget(target))
                    assertFalse(repository.canSyncTarget(other))
                    var executed = false
                    val failure = repository.executeIfApproved {
                        executed = true
                        Result.success(Unit)
                    }.exceptionOrNull()
                    assertFalse(executed)
                    assertTrue(failure is SyncProtocolUpgradeRequiredException)
                    assertTrue(runCatching {
                        repository.markCurrent(target)
                    }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
                    assertTrue(runCatching {
                        repository.requireLegacyMigration(challenge)
                    }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
                    assertTrue(runCatching {
                        repository.confirmAllDevicesUpdated(true, challenge)
                    }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
                    assertTrue(runCatching {
                        repository.initializeStartupTargets(setOf(target))
                    }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
                    assertEquals(saved, store.data.first())
                }
            }
        }
    }

    @Test fun `multiple migration and observed versions use the highest owned version`() = runTest {
        withStore(File(temporary.root, "multiple-versions.preferences_pb")) { store ->
            store.edit {
                it[intPreferencesKey("approved_protocol_version")] = 4
                it[intPreferencesKey("startup_registration_version")] = 4
                it[stringPreferencesKey("pending_legacy_$target")] = "3:4:$fingerprint"
                it[stringPreferencesKey("approved_legacy_$other")] = "0:6:$fingerprint"
                it[intPreferencesKey("observed_protocol_$target")] = 3
                it[intPreferencesKey("observed_protocol_$other")] = 7
                it[intPreferencesKey("unrelated_setting")] = 999
            }
            val repository = SyncProtocolUpgradeRepository(store)
            assertEquals(7, repository.versionFlow(target).first())
            assertFalse(repository.canSyncTarget(target))
            store.edit { it[intPreferencesKey("observed_protocol_$other")] = 4 }
            assertEquals(6, repository.versionFlow(target).first())
            assertFalse(repository.canSyncTarget(target))
            store.edit { it[stringPreferencesKey("approved_legacy_$other")] = "0:4:$fingerprint" }
            assertEquals(3, repository.versionFlow(target).first())
            assertEquals(0, repository.versionFlow(other).first())
        }
    }

    @Test fun `malformed migration field counts and versions reject actions and survive reopening`() = runTest {
        for ((index, value) in listOf("3:4", "not-a-version:4:$fingerprint", "3:not-a-version:$fingerprint").withIndex()) {
            assertCorruptStatePreserved("bad-migration-$index", IOException::class.java) {
                it[stringPreferencesKey("pending_legacy_$target")] = value
            }
        }
    }

    @Test fun `invalid migration hashes and version ordering reject actions and survive reopening`() = runTest {
        for ((index, value) in listOf("3:4:invalid-hash", "-1:4:$fingerprint", "4:3:$fingerprint").withIndex()) {
            assertCorruptStatePreserved("invalid-challenge-$index", IllegalArgumentException::class.java) {
                it[stringPreferencesKey("approved_legacy_$other")] = value
            }
        }
        assertCorruptStatePreserved("invalid-target", IllegalArgumentException::class.java) {
            it[stringPreferencesKey("pending_legacy_invalid-target")] = "3:4:$fingerprint"
        }
    }

    @Test fun `wrong stored migration and observed value types cannot grant permission`() = runTest {
        assertCorruptStatePreserved("wrong-migration-type", IOException::class.java) {
            it[intPreferencesKey("pending_legacy_$other")] = 4
        }
        assertCorruptStatePreserved("wrong-observed-type", IOException::class.java) {
            it[stringPreferencesKey("observed_protocol_$other")] = "4"
        }
    }

    private suspend fun TestScope.assertCorruptStatePreserved(
        name: String,
        failureType: Class<out Exception>,
        initialize: (MutablePreferences) -> Unit
    ) {
        val file = File(temporary.root, "$name.preferences_pb")
        lateinit var saved: Preferences
        withStore(file) { store ->
            store.edit { initialize(it) }
            saved = store.data.first()
        }
        repeat(2) {
            withStore(file) { store ->
                val repository = SyncProtocolUpgradeRepository(store)
                var executed = false
                val failure = repository.executeIfApproved {
                    executed = true
                    Result.success(Unit)
                }.exceptionOrNull()
                assertFalse(executed)
                assertTrue("$name: $failure", failureType.isInstance(failure))
                assertTrue(failureType.isInstance(runCatching {
                    repository.versionFlow(target).first()
                }.exceptionOrNull()))
                assertTrue(failureType.isInstance(runCatching {
                    repository.markCurrent(target)
                }.exceptionOrNull()))
                assertEquals(saved, store.data.first())
            }
        }
    }

    private suspend fun assertRequired(repository: SyncProtocolUpgradeRepository, value: SyncProtocolUpgradeChallenge) {
        val failure = runCatching { repository.requireLegacyMigration(value) }.exceptionOrNull()
        assertTrue(failure is SyncProtocolUpgradeRequiredException)
        assertEquals(value, (failure as SyncProtocolUpgradeRequiredException).challenge)
    }

    private suspend fun TestScope.withStore(file: File, action: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job), produceFile = { file }
        )
        try { action(store) } finally { job.cancelAndJoin() }
    }
}
