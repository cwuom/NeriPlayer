package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.content.SharedPreferences
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
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.withSettings

class SyncProtocolUpgradeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))
    private val second = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))

    @Test fun `new installation uses the current sync format without confirmation`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        assertTrue(repository.approvedFlow.first())
        assertNull(repository.pendingChallengeFlow.first())
        assertEquals(4, repository.versionFlow(first.targetId).first())
        assertEquals("synced", repository.executeIfApproved { Result.success("synced") }.getOrThrow())
    }

    @Test fun `legacy approval persists and only authorizes the exact detected content`() = runTest {
        val file = File(temporary.root, "approval.preferences_pb")
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            detect(repository, first)
            assertEquals(0, repository.versionFlow(first.targetId).first())
            assertFalse(repository.canSyncTarget(first.targetId))
            repository.confirmAllDevicesUpdated(true, first)
            assertTrue(repository.canSyncTarget(first.targetId))
            assertTrue(file.isFile)
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            repository.requireLegacyMigration(first)
            assertNull(repository.pendingChallengeFlow.first())
            detect(repository, first.copy(fingerprint = "3".repeat(64)))
        }
    }

    @Test fun `old global approval cannot authorize newly detected legacy data`() = runTest {
        for (version in listOf(2, 3)) {
            val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore(preferencesOf(ApprovedVersion to version)))
            assertTrue(repository.approvedFlow.first())
            detect(repository, first)
        }
    }

    @Test fun `approval cannot cross targets and a stale challenge cannot be confirmed`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        detect(repository, first)
        repository.confirmAllDevicesUpdated(true, first)
        detect(repository, second)
        assertTrue(repository.canSyncTarget(first.targetId))
        assertFalse(repository.canSyncTarget(second.targetId))
        assertEquals(0, repository.versionFlow(first.targetId).first())
        assertEquals(0, repository.versionFlow(second.targetId).first())
        val changed = second.copy(fingerprint = "4".repeat(64))
        detect(repository, changed)
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(true, second) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertEquals(changed, repository.pendingChallengeFlow.first())
        repository.confirmAllDevicesUpdated(true, changed)
        repository.requireLegacyMigration(changed)
    }

    @Test fun `current remote observation consumes the old approval and legacy return requires confirmation`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        detect(repository, first)
        repository.confirmAllDevicesUpdated(true, first)
        repository.markCurrent(first.targetId)
        assertNull(repository.pendingChallengeFlow.first())
        assertEquals(4, repository.versionFlow(first.targetId).first())
        detect(repository, first)
    }

    @Test fun `confirmation requires a detected challenge and the all devices declaration`() = runTest {
        val store = FaultInjectingDataStore()
        val repository = SyncProtocolUpgradeRepository(store)
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(false, first) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, store.writeAttempts)
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        assertNull(repository.pendingChallengeFlow.first())
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull() is IllegalStateException)
        detect(repository, first)
        detect(repository, second)
        assertTrue(runCatching { repository.confirmAllDevicesUpdated(true) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `pending challenge permits probing without granting migration permission`() = runTest {
        val repository = SyncProtocolUpgradeRepository(FaultInjectingDataStore())
        detect(repository, first)
        val failure = IOException("backend failed")
        assertSame(failure, repository.executeIfApproved<String> { Result.failure(failure) }.exceptionOrNull())
        detect(repository, first)
    }

    @Test fun `future approval cannot be read synced migrated or downgraded`() = runTest {
        val future = SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION + 1
        val file = File(temporary.root, "future.preferences_pb")
        withFileStore(file) { store ->
            store.edit { it[ApprovedVersion] = future }
            val repository = SyncProtocolUpgradeRepository(store)
            assertFalse(repository.approvedFlow.first())
            assertEquals(future, repository.versionFlow(first.targetId).first())
            assertTrue(repository.executeIfApproved<String> { error("must not sync") }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.requireLegacyMigration(first) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.markCurrent(first.targetId) }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
            assertTrue(runCatching { repository.pendingChallengeFlow.first() }.exceptionOrNull() is SyncProtocolUpgradeRequiredException)
        }
        withFileStore(file) { store -> assertEquals(future, store.data.first()[ApprovedVersion]) }
    }

    @Test fun `failed confirmation preserves pending state and allows retry`() = runTest {
        val store = FaultInjectingDataStore()
        val repository = SyncProtocolUpgradeRepository(store)
        detect(repository, first)
        val failure = IOException("disk full")
        store.writeFailure = failure
        assertSame(failure, runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull())
        store.writeFailure = null
        detect(repository, first)
        repository.confirmAllDevicesUpdated(true, first)
        repository.requireLegacyMigration(first)
    }

    @Test fun `failed real file replacement cannot approve from optimistic cache`() = runTest {
        val file = File(temporary.root, "blocked.preferences_pb")
        val backup = File(temporary.root, "blocked.backup")
        val blocker = File(file, "blocker")
        withFileStore(file) { store ->
            detect(SyncProtocolUpgradeRepository(store), first)
            var blockReplacement = true
            val failingStore = object : DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = store.updateData { preferences ->
                    val updated = transform(preferences)
                    if (blockReplacement && updated != preferences) {
                        assertTrue(file.renameTo(backup))
                        assertTrue(file.mkdir())
                        blocker.writeText("block replacement")
                        blockReplacement = false
                    }
                    updated
                }
            }
            val repository = SyncProtocolUpgradeRepository(failingStore)
            assertTrue(runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { repository.requireLegacyMigration(first) }.exceptionOrNull() is IOException)
            assertTrue(blocker.delete())
            assertTrue(file.delete())
            assertTrue(backup.renameTo(file))
        }
        withFileStore(file) { store ->
            val repository = SyncProtocolUpgradeRepository(store)
            detect(repository, first)
            repository.confirmAllDevicesUpdated(true, first)
            repository.requireLegacyMigration(first)
        }
    }

    @Test fun `cancelled confirmation does not authorize migration`() = runTest {
        val store = FaultInjectingDataStore()
        val repository = SyncProtocolUpgradeRepository(store)
        detect(repository, first)
        val cancellation = CancellationException("cancelled save")
        store.writeFailure = cancellation
        assertSame(cancellation, runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull())
        store.writeFailure = null
        detect(repository, first)
    }

    @Test fun `cancelling the real confirmation transaction cannot approve after reopening`() = runTest {
        val file = File(temporary.root, "cancelled.preferences_pb")
        withFileStore(file) { store ->
            detect(SyncProtocolUpgradeRepository(store), first)
            val saving = CompletableDeferred<Unit>()
            val blockedStore = object : DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = store.updateData { preferences ->
                    val updated = transform(preferences)
                    if (updated != preferences) {
                        saving.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    updated
                }
            }
            val repository = SyncProtocolUpgradeRepository(blockedStore)
            val confirmation = launch { repository.confirmAllDevicesUpdated(true, first) }
            saving.await()
            confirmation.cancelAndJoin()
            assertTrue(confirmation.isCancelled)
        }
        withFileStore(file) { store -> detect(SyncProtocolUpgradeRepository(store), first) }
    }

    @Test fun `read failure cannot invoke sync and can retry after recovery`() = runTest {
        val failure = IOException("approval unavailable")
        val store = FaultInjectingDataStore().apply { readFailure = failure }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(failure, repository.executeIfApproved<String> { error("must not sync") }.exceptionOrNull())
        assertSame(failure, runCatching { repository.confirmAllDevicesUpdated(true, first) }.exceptionOrNull())
        store.readFailure = null
        assertTrue(repository.approvedFlow.first())
    }

    @Test fun `reads thrown actions and returned action results propagate cancellation`() = runTest {
        val cancellation = CancellationException("cancelled")
        val store = FaultInjectingDataStore().apply { readFailure = cancellation }
        val repository = SyncProtocolUpgradeRepository(store)
        assertSame(cancellation, runCatching { repository.executeIfApproved<String> { error("must not sync") } }.exceptionOrNull())
        store.readFailure = null
        assertSame(cancellation, runCatching { repository.executeIfApproved<String> { throw cancellation } }.exceptionOrNull())
        assertSame(cancellation, runCatching { repository.executeIfApproved<String> { Result.failure(cancellation) } }.exceptionOrNull())
    }

    @Test fun `target identity normalization retains WebDAV account separation`() {
        assertEquals(SyncProtocolUpgradeRepository.githubTargetHash(" Owner ", " Repo "), SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo"))
        assertEquals(SyncProtocolUpgradeRepository.webDavTargetHash("https://example.test/dav/", "/music/", "user"), SyncProtocolUpgradeRepository.webDavTargetHash("https://example.test/dav", "music", "user"))
        assertFalse(SyncProtocolUpgradeRepository.webDavTargetHash("https://example.test/dav", "music", "user") == SyncProtocolUpgradeRepository.webDavTargetHash("https://example.test/dav", "music", "other"))
        assertFalse(SyncProtocolUpgradeRepository.githubTargetHash("a/b", "c") == SyncProtocolUpgradeRepository.githubTargetHash("a", "b/c"))
    }

    @Test fun `configured target reader returns exactly the persisted configured providers`() {
        val github = githubConfiguration()
        val webDav = webDavConfiguration()
        val githubId = SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")
        val webDavId = SyncProtocolUpgradeRepository.webDavTargetHash("https://example.test/dav", "music", "user")
        for ((hasGithub, hasWebDav) in listOf(false to false, true to false, false to true, true to true)) {
            val expected = buildSet {
                if (hasGithub) add(githubId)
                if (hasWebDav) add(webDavId)
            }

            assertEquals(expected, readConfiguredTargets(
                if (hasGithub) github else emptyMap(), if (hasWebDav) webDav else emptyMap()
            ))
        }
    }

    @Test fun `configured target reader keeps canonical paths and case sensitive account identities`() {
        val github = githubConfiguration() + mapOf("repo_owner" to " Owner ", "repo_name" to " Repo ")
        val webDav = webDavConfiguration() + mapOf("server_url" to " https://example.test/dav/ ", "base_path" to "/music/")
        val canonical = readConfiguredTargets(githubConfiguration(), webDavConfiguration())

        assertEquals(canonical, readConfiguredTargets(github, webDav))
        val otherAccount = readConfiguredTargets(github, webDav + ("username" to "User"))
        assertFalse(canonical == otherAccount)
        assertEquals(2, otherAccount.size)
        assertTrue(otherAccount.contains(SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")))
    }

    @Test fun `incomplete credentials are excluded from startup target registration`() {
        for (missing in listOf("github_token", "repo_owner", "repo_name")) {
            assertTrue(readConfiguredTargets(githubConfiguration() - missing, emptyMap()).isEmpty())
        }
        for (missing in listOf("server_url", "username", "password")) {
            assertTrue(readConfiguredTargets(emptyMap(), webDavConfiguration() - missing).isEmpty())
        }
    }

    @Test fun `invalid persisted webdav targets are skipped without erasing repairable fields`() {
        val githubId = SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")
        for (server in listOf("not-a-url", "https://", "https://[::1", "ftp://example.test/dav")) {
            val webDav = webDavConfiguration() + mapOf(
                "server_url" to server, "auto_sync_enabled" to false,
                "last_sync_time" to 77L, "last_remote_fingerprint" to "previous"
            )

            assertEquals(setOf(githubId), readConfiguredTargets(githubConfiguration(), webDav))
        }
    }

    private fun githubConfiguration(): Map<String, Any> = mapOf(
        "github_token" to "fixture", "repo_owner" to "owner", "repo_name" to "repo"
    )

    private fun webDavConfiguration(): Map<String, Any> = mapOf(
        "server_url" to "https://example.test/dav", "base_path" to "music", "username" to "user", "password" to "fixture"
    )

    private fun readConfiguredTargets(githubValues: Map<String, Any>, webDavValues: Map<String, Any>): Set<String> {
        val githubPreferences = readOnlyPreferences(githubValues)
        val webDavPreferences = readOnlyPreferences(webDavValues)
        // local 测试不能直接引用 sync 的 internal 构造器，只绕过平台加密入口，配置判断仍调用真实存储
        val github = SecureTokenStorage::class.java.getConstructor(
            SharedPreferences::class.java, File::class.java, Function1::class.java
        ).newInstance(githubPreferences, null, null)
        val webDav = WebDavStorage::class.java.getConstructor(SharedPreferences::class.java).newInstance(webDavPreferences)
        val originalGithub = github.snapshot()
        val originalWebDav = webDav.snapshot()
        val result = mockConstruction(SecureTokenStorage::class.java, withSettings().defaultAnswer { call ->
            call.method.invoke(github, *call.arguments)
        }).use {
            mockConstruction(WebDavStorage::class.java, withSettings().defaultAnswer { call ->
                call.method.invoke(webDav, *call.arguments)
            }).use {
                SyncProtocolUpgradeRepository.configuredTargetIds(mock(Context::class.java))
            }
        }
        assertEquals(originalGithub, github.snapshot())
        assertEquals(originalWebDav, webDav.snapshot())
        assertEquals(githubValues, githubPreferences.all)
        assertEquals(webDavValues, webDavPreferences.all)
        return result
    }

    private fun readOnlyPreferences(values: Map<String, Any>): SharedPreferences = mock(SharedPreferences::class.java) { call ->
        when (call.method.name) {
            "getString", "getLong", "getBoolean" -> values[call.arguments[0] as String] ?: call.arguments[1]
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(call.arguments[0] as String)
            "edit" -> error("Reading configured targets must not modify stored configuration")
            else -> null
        }
    }

    private suspend fun detect(repository: SyncProtocolUpgradeRepository, challenge: SyncProtocolUpgradeChallenge) {
        val failure = runCatching { repository.requireLegacyMigration(challenge) }.exceptionOrNull()
        assertTrue(failure is SyncProtocolUpgradeRequiredException)
        assertEquals(challenge, (failure as SyncProtocolUpgradeRequiredException).challenge)
        assertTrue(repository.pendingFlow.first().contains(challenge))
        assertFalse(repository.approvedFlow.first())
    }

    private suspend fun TestScope.withFileStore(file: File, action: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job), produceFile = { file })
        try { action(store) } finally { job.cancelAndJoin() }
    }

    private class FaultInjectingDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writeAttempts = 0
        override val data: Flow<Preferences> = flow { readFailure?.let { throw it }; emitAll(state) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            readFailure?.let { throw it }
            val updated = transform(state.value)
            if (updated != state.value) {
                writeAttempts++
                writeFailure?.let { throw it }
            }
            state.value = updated
            return updated
        }
    }

    companion object { private val ApprovedVersion = intPreferencesKey("approved_protocol_version") }
}
