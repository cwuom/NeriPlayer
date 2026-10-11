package moe.ouom.neriplayer.data.sync.host

import androidx.datastore.core.DataStore
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncProtocolFutureStateRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = "a".repeat(64)
    private val other = "b".repeat(64)
    private val fingerprint = "1".repeat(64)
    private val legacyChallenge = SyncProtocolUpgradeChallenge(target, fingerprint, fromVersion = 3, toVersion = 4)

    @Test fun `syncing a current remote after a downgrade reclaims the newer app markers durably`() = runTest {
        val file = File(temporary.root, "downgraded.preferences_pb")
        withStore(file) { store ->
            store.edit {
                it[ApprovedVersion] = 5
                it[StartupVersion] = 5
                it[observedKey(target)] = 5
                it[pendingKey(target)] = "4:5:$fingerprint"
            }
            val repository = SyncProtocolUpgradeRepository(store)

            val result = repository.executeIfApproved {
                repository.markCurrent(target)
                Result.success("synced")
            }

            assertEquals("synced", result.getOrThrow())
        }
        withStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            val saved = store.data.first()
            assertEquals(4, saved[ApprovedVersion])
            assertEquals(4, saved[StartupVersion])
            assertEquals(4, saved[observedKey(target)])
            assertNull(saved[pendingKey(target)])
            assertEquals(4, repository.versionFlow(target).first())
            assertTrue(repository.canSyncTarget(target))
            assertTrue(repository.approvedFlow.first())
            repository.initializeStartupTargets(setOf(target))
            assertTrue(repository.pendingFlow.first().isEmpty())
        }
    }

    @Test fun `a legacy remote read after a downgrade asks for this app's own migration`() = runTest {
        withStore(File(temporary.root, "legacy.preferences_pb")) { store ->
            store.edit {
                it[observedKey(target)] = 5
                it[approvalKey(target)] = "3:5:$fingerprint"
            }
            val repository = SyncProtocolUpgradeRepository(store)

            val failure = runCatching { repository.requireLegacyMigration(legacyChallenge) }.exceptionOrNull()

            assertEquals(legacyChallenge, (failure as SyncProtocolUpgradeRequiredException).challenge)
            assertEquals(legacyChallenge, repository.pendingChallengeFlow.first())
            assertEquals(3, repository.versionFlow(target).first())
            assertNull(store.data.first()[approvalKey(target)])
            repository.confirmAllDevicesUpdated(true, legacyChallenge)
            repository.requireLegacyMigration(legacyChallenge)
        }
    }

    @Test fun `a V3 observation drops only the newer migration record of that target`() = runTest {
        withStore(File(temporary.root, "v3.preferences_pb")) { store ->
            store.edit {
                it[pendingKey(target)] = "3:5:$fingerprint"
                it[pendingKey(other)] = "3:4:$fingerprint"
            }
            val repository = SyncProtocolUpgradeRepository(store)

            repository.markCurrent(target, 3)

            val saved = store.data.first()
            assertNull(saved[pendingKey(target)])
            assertEquals("3:4:$fingerprint", saved[pendingKey(other)])
            assertEquals(3, repository.versionFlow(target).first())
        }
    }

    @Test fun `a target whose remote is newer does not block syncing another target`() = runTest {
        withStore(File(temporary.root, "mixed.preferences_pb")) { store ->
            store.edit { it[observedKey(other)] = 5 }
            val repository = SyncProtocolUpgradeRepository(store)

            assertTrue(repository.canSyncTarget(target))
            val synced = repository.executeIfApproved {
                repository.markCurrent(target)
                Result.success(Unit)
            }
            val unreadable = repository.executeIfApproved<Unit> { Result.failure(IOException("Unsupported sync archive version")) }

            assertTrue(synced.isSuccess)
            assertEquals(4, repository.versionFlow(target).first())
            assertEquals(5, repository.versionFlow(other).first())
            assertTrue(unreadable.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertEquals(5, store.data.first()[observedKey(other)])
        }
    }

    @Test fun `startup targets registered by a newer app do not stop re-reading the remote`() = runTest {
        withStore(File(temporary.root, "startup.preferences_pb")) { store ->
            store.edit {
                it[StartupVersion] = 5
                it[StartupTargets] = setOf(target, other)
            }
            val repository = SyncProtocolUpgradeRepository(store)

            assertTrue(repository.canSyncTarget(target))
            repository.markCurrent(target)

            assertEquals(setOf(other), store.data.first()[StartupTargets])
            assertEquals(4, store.data.first()[StartupVersion])
            assertEquals(setOf(other), repository.startupPendingFlow.first())
        }
    }

    private fun observedKey(targetId: String) = intPreferencesKey("observed_protocol_$targetId")
    private fun pendingKey(targetId: String) = stringPreferencesKey("pending_legacy_$targetId")
    private fun approvalKey(targetId: String) = stringPreferencesKey("approved_legacy_$targetId")

    private suspend fun TestScope.withStore(file: File, action: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job), produceFile = { file }
        )
        try { action(store) } finally { job.cancelAndJoin() }
    }

    private companion object {
        val ApprovedVersion = intPreferencesKey("approved_protocol_version")
        val StartupVersion = intPreferencesKey("startup_registration_version")
        val StartupTargets = stringSetPreferencesKey("startup_legacy_targets")
    }
}
