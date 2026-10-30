package moe.ouom.neriplayer.data.sync.work

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.github.GitHubSyncBackend
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerExecution
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerFailureClassifier
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class GitHubWorkerAuthenticationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun releaseClients() {
        clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `current unauthorized worker pauses automatic sync without deleting configuration or metadata`() = runTest {
        val fixture = Fixture()
        fixture.silentFailure = true
        val saved = fixture.storage.snapshot()

        assertEquals(SyncWorkerOutcome.FAILURE, fixture.executeAutomatic())

        assertEquals(saved.copy(autoSyncEnabled = false), fixture.storage.snapshot())
        assertTrue(fixture.storage.isConfigured())
        assertEquals("previous-sha", fixture.storage.getLastRemoteSha())
        assertEquals(50L, fixture.storage.getLastSyncTime())
        assertEquals(60L, fixture.storage.getLastCompletedSyncTime())
        assertTrue(fixture.notifications.single() is TokenExpiredException)

        assertEquals(SyncWorkerOutcome.SUCCESS, fixture.executeAutomatic())
        assertEquals(1, fixture.requests)
        assertEquals(1, fixture.notifications.size)
    }

    @Test
    fun `late unauthorized worker cannot pause a token or repository replaced through another storage instance`() = runTest {
        for (replaceToken in listOf(true, false)) {
            val fixture = Fixture()
            val otherStorage = tokenStorage(fixture.preferences)
            fixture.beforeResponse = {
                if (replaceToken) otherStorage.saveToken("replacement-token")
                else otherStorage.saveRepository("replacement-owner", "replacement-repository")
                otherStorage.setAutoSyncEnabled(true)
            }

            assertEquals(SyncWorkerOutcome.FAILURE, fixture.executeAutomatic())

            assertTrue(fixture.storage.isAutoSyncEnabled())
            assertTrue(fixture.storage.isConfigured())
            assertEquals(if (replaceToken) "replacement-token" else "fixture-token", fixture.storage.getToken())
            assertEquals(if (replaceToken) "fixture-owner" else "replacement-owner", fixture.storage.getRepoOwner())
            assertEquals(if (replaceToken) "fixture-repository" else "replacement-repository", fixture.storage.getRepoName())
            assertTrue(fixture.notifications.single() is TokenExpiredException)
        }
    }

    @Test
    fun `unavailable storage while pausing preserves credentials and reports the original unauthorized failure once`() = runTest {
        for (failRead in listOf(true, false)) {
            val fixture = Fixture()
            val saved = fixture.storage.snapshot()
            fixture.beforeResponse = {
                fixture.readUnavailable = failRead
                fixture.writeError = if (failRead) null else SyncStorageUnavailableException()
            }

            assertEquals(SyncWorkerOutcome.FAILURE, fixture.executeAutomatic())
            assertTrue(fixture.result?.exceptionOrNull() is TokenExpiredException)
            assertTrue(fixture.notifications.single() is TokenExpiredException)

            fixture.readUnavailable = false
            fixture.writeError = null
            assertEquals(saved, fixture.storage.snapshot())
            assertEquals("previous-sha", fixture.storage.getLastRemoteSha())
            assertEquals(50L, fixture.storage.getLastSyncTime())
            assertEquals(60L, fixture.storage.getLastCompletedSyncTime())
        }
    }

    @Test
    fun `non authentication failures do not pause automatic sync`() = runTest {
        val fixture = Fixture()
        fixture.responseCode = 500

        assertEquals(SyncWorkerOutcome.RETRY, fixture.executeAutomatic())

        assertTrue(fixture.storage.isAutoSyncEnabled())
        assertTrue(fixture.storage.isConfigured())
        assertFalse(fixture.notifications.single() is TokenExpiredException)
    }

    @Test
    fun `unexpected pause storage failures remain visible instead of being swallowed as authentication failures`() = runTest {
        val fixture = Fixture()
        val failure = IllegalStateException("unexpected storage failure")
        fixture.beforeResponse = { fixture.writeError = failure }

        assertEquals(SyncWorkerOutcome.RETRY, fixture.executeAutomatic())

        assertSame(failure, fixture.result?.exceptionOrNull())
        assertSame(failure, fixture.notifications.single())
        fixture.writeError = null
        assertTrue(fixture.storage.isConfigured())
        assertTrue(fixture.storage.isAutoSyncEnabled())
    }

    private fun tokenStorage(preferences: SharedPreferences): SecureTokenStorage =
        SecureTokenStorage::class.java.getDeclaredConstructor(
            SharedPreferences::class.java, File::class.java, Function1::class.java
        ).newInstance(preferences, null, null)

    private inner class Fixture {
        var readUnavailable = false
        var writeError: Exception? = null
        var responseCode = 401
        var silentFailure = false
        var requests = 0
        var beforeResponse: () -> Unit = {}
        var result: Result<SyncResult>? = null
        val notifications = mutableListOf<Throwable?>()
        private val values = VolatileSharedPreferences.shared("github-worker-auth-${UUID.randomUUID()}")
        val preferences = object : SharedPreferences by values {
            override fun getLong(key: String?, defValue: Long): Long {
                if (readUnavailable) throw SyncStorageUnavailableException()
                return values.getLong(key, defValue)
            }
            override fun edit(): SharedPreferences.Editor {
                writeError?.let { throw it }
                return values.edit()
            }
        }
        val storage = tokenStorage(preferences).apply {
            saveToken("fixture-token")
            saveRepository("fixture-owner", "fixture-repository")
            setAutoSyncEnabled(true)
            saveLastRemoteSha("previous-sha")
            saveLastSyncTime(50L)
            saveLastCompletedSyncTime(60L)
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            beforeResponse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(responseCode).message("fixture").body("{}".toResponseBody()).build()
        }.build().also(clients::add)
        private val archive = SyncArchiveRepository(temporary.newFolder())
        private val backend = GitHubSyncBackend(
            storage, GitHubApiClient("fixture-token", client, "expired", "https://fixture.invalid"),
            "fixture-owner", "fixture-repository", SyncRemoteSnapshotDecoder { it },
            { IOException("invalid backup") }, {}, archive
        )
        private val local = object : SyncLocalDataStore {
            override suspend fun awaitInitialized() = true
            override fun mutationVersion() = 0L
            override suspend fun snapshot() = archive.playbackDatasets.fromLegacy(SyncData())
            override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean =
                error("unauthorized sync cannot apply remote data")
        }
        private val session = SyncSession(local, SyncDataMerger(mock(SyncMergeHost::class.java)), archive.playbackDatasets,
            "unchanged", "uploaded", { IOException("busy") }, deferredMessage = "pending")
        private val host = SyncRepositoryWorkerHost(
            SyncProvider.GITHUB, storage::isAutoSyncEnabled, storage::isConfigured,
            readProtocolUpgradeApproved = { true }, readPlayback = { false }, readNetwork = { true }, defer = {},
            sync = { session.execute { backend }.also { result = it } },
            classifier = SyncWorkerFailureClassifier(mapOf(TokenExpiredException::class.java to SyncWorkerFailureKind.AUTHENTICATION)),
            readSilentFailure = { silentFailure }, notifyFailure = { notifications += it }
        )
        suspend fun executeAutomatic() = SyncWorkerExecution(host).execute(false, false)
    }
}
