package moe.ouom.neriplayer.data.sync.host

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncProtocolUpgradeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `approval is persisted before sync and survives reopening the real file`() = runTest {
        val file = File(temporary.root, "approval.preferences_pb")
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            assertFalse(repository.approvedFlow.first())
            repository.confirmAllDevicesUpdated(true)
            assertTrue(repository.approvedFlow.first())
            assertTrue(file.isFile)
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            assertTrue(repository.approvedFlow.first())
            assertEquals("synced", repository.executeIfApproved { Result.success("synced") }.getOrThrow())
        }
    }

    @Test
    fun `declining the all devices declaration does not write or approve`() = runTest {
        val store = FaultInjectingDataStore()
        val repository = SyncProtocolUpgradeRepository(store)
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(false) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, store.writeAttempts)
        assertFalse(repository.approvedFlow.first())
    }

    @Test
    fun `pending approval returns typed failure without invoking the sync action`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore()) { "upgrade required" }
        val failure = repository.executeIfApproved<String> { error("sync must not run") }.exceptionOrNull()
        assertTrue(failure is SyncProtocolUpgradeRequiredException)
        assertEquals("upgrade required", failure?.message)
    }

    @Test
    fun `confirmation permits the sync action and preserves its result`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        repository.confirmAllDevicesUpdated(true)
        var calls = 0
        val failure = IOException("backend failed")
        val result = repository.executeIfApproved<String> { calls++; Result.failure(failure) }
        assertEquals(1, calls)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `older protocol approval still requires a new explicit confirmation`() = runTest {
        val store = FaultInjectingDataStore(preferencesOf(ApprovedVersion to 2))
        val repository = SyncProtocolUpgradeRepository(store)
        assertFalse(repository.approvedFlow.first())
        assertTrue(repository.executeIfApproved<String> { error("sync must not run") }.isFailure)
        repository.confirmAllDevicesUpdated(true)
        assertEquals(SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION, store.data.first()[ApprovedVersion])
        assertTrue(repository.approvedFlow.first())
    }

    @Test
    fun `future approval stays blocked and confirmation cannot downgrade its durable version`() = runTest {
        val file = File(temporary.root, "future.preferences_pb")
        val futureVersion = SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION + 1
        withFileStore(file) { store ->
            store.edit { it[ApprovedVersion] = futureVersion }
            val repository = SyncProtocolUpgradeRepository(store)
            assertFalse(repository.approvedFlow.first())
            assertTrue(repository.executeIfApproved<String> { error("sync must not run") }
                .exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.confirmAllDevicesUpdated(true) }
                .exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        }
        withFileStore(file) { store ->
            assertEquals(futureVersion, store.data.first()[ApprovedVersion])
            assertFalse(SyncProtocolUpgradeRepository(store).approvedFlow.first())
        }
    }

    @Test
    fun `failed durable update does not publish optimistic approval and can retry`() = runTest {
        val failure = IOException("disk full")
        val store = FaultInjectingDataStore().apply { writeFailure = failure }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(failure, runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull())
        assertFalse(repository.approvedFlow.first())
        assertTrue(repository.executeIfApproved<String> { error("sync must not run") }
            .exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        store.writeFailure = null
        repository.confirmAllDevicesUpdated(true)
        assertTrue(repository.approvedFlow.first())
    }

    @Test
    fun `failed real file replacement cannot approve from the optimistic DataStore cache`() = runTest {
        val file = File(temporary.root, "blocked.preferences_pb")
        val blocker = File(file, "blocker")
        withFileStore(file) { store ->
            var blockReplacement = true
            val failingStore = object : DataStore<Preferences> {
                override val data = store.data

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    return store.updateData { preferences ->
                        val updated = transform(preferences)
                        if (blockReplacement) {
                            assertTrue(file.mkdir())
                            blocker.writeText("block replacement")
                            blockReplacement = false
                        }
                        updated
                    }
                }
            }
            val repository = SyncProtocolUpgradeRepository(failingStore)
            assertFalse(repository.approvedFlow.first())
            assertTrue(runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull() is IOException)
            assertTrue(repository.executeIfApproved<String> { error("sync must not run") }.exceptionOrNull() is IOException)
            assertTrue(blocker.delete())
            assertTrue(file.delete())
            assertFalse(repository.approvedFlow.first())
            repository.confirmAllDevicesUpdated(true)
            assertTrue(repository.approvedFlow.first())
        }
        withFileStore(file) { store ->
            assertTrue(SyncProtocolUpgradeRepository(store).approvedFlow.first())
        }
    }

    @Test
    fun `cancelled durable update propagates cancellation and leaves sync blocked`() = runTest {
        val cancellation = CancellationException("cancelled save")
        val store = FaultInjectingDataStore().apply { writeFailure = cancellation }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(cancellation, runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull())
        assertFalse(repository.approvedFlow.first())
        assertTrue(repository.executeIfApproved<String> { error("sync must not run") }
            .exceptionOrNull() is SyncProtocolUpgradeRequiredException)
    }

    @Test
    fun `cancelling confirmation during the real DataStore transaction cannot approve after reopening`() = runTest {
        val file = File(temporary.root, "cancelled.preferences_pb")
        withFileStore(file) { store ->
            val saving = CompletableDeferred<Unit>()
            val blockedStore = object : DataStore<Preferences> {
                override val data = store.data

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    return store.updateData { preferences ->
                        val updated = transform(preferences)
                        saving.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        updated
                    }
                }
            }
            val repository = SyncProtocolUpgradeRepository(blockedStore)
            val confirmation = launch { repository.confirmAllDevicesUpdated(true) }
            saving.await()
            confirmation.cancelAndJoin()
            assertTrue(confirmation.isCancelled)
            assertFalse(repository.approvedFlow.first())
        }
        withFileStore(file) { store ->
            assertFalse(SyncProtocolUpgradeRepository(store).approvedFlow.first())
        }
    }

    @Test
    fun `approval read failure is returned without invoking sync and confirmation cannot bypass it`() = runTest {
        val failure = IOException("approval unavailable")
        val store = FaultInjectingDataStore().apply { readFailure = failure }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(failure, repository.executeIfApproved<String> { error("sync must not run") }.exceptionOrNull())
        assertSame(failure, runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull())
        store.readFailure = null
        assertFalse(repository.approvedFlow.first())
    }

    @Test
    fun `cancelled approval read is propagated without invoking sync`() = runTest {
        val cancellation = CancellationException("cancelled read")
        val store = FaultInjectingDataStore().apply { readFailure = cancellation }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(cancellation, runCatching {
            repository.executeIfApproved<String> { error("sync must not run") }
        }.exceptionOrNull())
    }

    @Test
    fun `sync action cancellation is propagated when thrown or returned`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        repository.confirmAllDevicesUpdated(true)
        val cancellation = CancellationException("cancelled sync")
        assertSame(cancellation, runCatching {
            repository.executeIfApproved<String> { throw cancellation }
        }.exceptionOrNull())
        assertSame(cancellation, runCatching {
            repository.executeIfApproved<String> { Result.failure(cancellation) }
        }.exceptionOrNull())
    }

    private suspend fun TestScope.withFileStore(file: File, action: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
            produceFile = { file }
        )
        try {
            action(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    private class FaultInjectingDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writeAttempts = 0

        override val data: Flow<Preferences> = flow {
            readFailure?.let { throw it }
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeAttempts++
            readFailure?.let { throw it }
            val updated = transform(state.value)
            writeFailure?.let { throw it }
            state.value = updated
            return updated
        }
    }

    companion object {
        private val ApprovedVersion = intPreferencesKey("approved_protocol_version")
    }
}
