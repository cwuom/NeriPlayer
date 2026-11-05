package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.transport.GitHubRepositoryInfo
import moe.ouom.neriplayer.data.sync.host.createGitHubSyncClient
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import moe.ouom.neriplayer.ui.i18n.UiText
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubSyncCredentialRecoveryTest {
    private val viewModels = mutableListOf<GitHubSyncViewModel>()
    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a rejected token save never reports validation success or leaves validation busy`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        doAnswer { throw SyncStorageUnavailableException(IOException("local credential write failed")) }.`when`(storage).saveToken("fresh-token")
        val viewModel = viewModel(storage)
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))

        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "fresh-token") }.thenReturn(api)
            viewModel.validateToken(context, "fresh-token")
            testScheduler.runCurrent()

            assertFalse(viewModel.uiState.value.isValidating)
            assertFalse(viewModel.uiState.value.tokenValid)
            assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
            assertEquals(null, viewModel.uiState.value.successMessage)
            assertEquals("existing-token", storage.getToken())
        }
    }

    @Test
    fun `token validation before initialization persists the credential before reporting success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = viewModel()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))
        var savedToken: String? = null

        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.isPersistent).thenReturn(true)
            `when`(storage.getToken()).thenAnswer { savedToken }
            doAnswer { savedToken = it.getArgument(0); null }.`when`(storage).saveToken("fresh-token")
        }.use {
            mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "fresh-token") }.thenReturn(api)
                viewModel.validateToken(context, "fresh-token")
                testScheduler.runCurrent()

                assertEquals("fresh-token", savedToken)
                assertTrue(viewModel.uiState.value.tokenValid)
                assertFalse(viewModel.uiState.value.isValidating)
            }
        }
    }

    @Test
    fun `initialization storage failure reports a recoverable error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        doAnswer { throw SyncStorageUnavailableException(IOException("local secure storage unavailable")) }.`when`(storage).isConfigured()
        val viewModel = viewModel(storage)

        viewModel.initialize(context)
        testScheduler.runCurrent()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isValidating)
    }

    @Test
    fun `validation retries secure storage after initialization failed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        doAnswer { throw SyncStorageUnavailableException(IOException("temporarily unavailable")) }.`when`(storage).isConfigured()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.errorMessage)
        doReturn(true).`when`(storage).isConfigured()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))

        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "fresh-token") }.thenReturn(api)
            viewModel.validateToken(context, "fresh-token")
            testScheduler.runCurrent()

            assertTrue(viewModel.uiState.value.tokenValid)
            assertTrue(viewModel.uiState.value.isConfigured)
            assertEquals("octocat", viewModel.uiState.value.repoOwner)
            assertEquals("backup", viewModel.uiState.value.repoName)
            assertEquals(123L, viewModel.uiState.value.lastSyncTime)
            assertEquals(null, viewModel.uiState.value.errorMessage)
            verify(storage).saveToken("fresh-token")
        }
    }

    @Test
    fun `a failed storage constructor can recover on the next validation attempt`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = viewModel()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))
        var attempts = 0
        var savedToken: String? = null
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            if (++attempts == 1) throw IllegalStateException("temporary secure storage failure")
            `when`(storage.isPersistent).thenReturn(true)
            doAnswer { savedToken = it.getArgument(0); null }.`when`(storage).saveToken("fresh-token")
        }.use {
            viewModel.initialize(context)
            testScheduler.runCurrent()
            assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
            mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "fresh-token") }.thenReturn(api)
                viewModel.validateToken(context, "fresh-token")
                testScheduler.runCurrent()

                assertEquals("fresh-token", savedToken)
                assertTrue(viewModel.uiState.value.tokenValid)
                assertFalse(viewModel.uiState.value.isValidating)
                assertEquals(null, viewModel.uiState.value.errorMessage)
            }
        }
    }

    @Test
    fun `volatile storage cannot report a successfully saved token`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        `when`(storage.isPersistent).thenReturn(false)
        val viewModel = viewModel(storage)

        viewModel.validateToken(context, "fresh-token")
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.tokenValid)
        assertFalse(viewModel.uiState.value.isValidating)
        assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
        verify(storage, never()).saveToken("fresh-token")
    }

    @Test
    fun `a late validation response cannot save a token after its draft was reset`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"login":"octocat"}""".toResponseBody()).build()
        }.build()
        val storage = storage()
        val viewModel = viewModel(storage)
        val api = GitHubApiClient("old-token", client, "expired")
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "old-token") }.thenReturn(api)
            try {
                viewModel.validateToken(context, "old-token")
                testScheduler.runCurrent()
                withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
                val pending = GitHubSyncViewModel::class.java.getDeclaredField("configurationJob")
                    .apply { isAccessible = true }.get(viewModel) as Job

                viewModel.resetTokenValidation()
                release.countDown()
                pending.join()
                testScheduler.runCurrent()

                assertFalse(viewModel.uiState.value.tokenValid)
                assertFalse(viewModel.uiState.value.isValidating)
                assertEquals(null, viewModel.uiState.value.successMessage)
                verify(storage, never()).saveToken("old-token")
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `authentication failure retains repository and metadata while stopping automatic sync`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        viewModel.syncOperation = { Result.failure<SyncResult>(TokenExpiredException("expired")) }

        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            viewModel.performSync(context)
            testScheduler.runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.isConfigured)
            assertEquals("octocat", state.repoOwner)
            assertEquals("backup", state.repoName)
            assertEquals(123L, state.lastSyncTime)
            assertFalse(state.autoSyncEnabled)
            assertFalse(state.isSyncing)
            assertNotNull(state.errorMessage)
            verify(storage, never()).clearAll()
            verify(storage, never()).clearToken()
        }
    }

    @Test
    fun `a rejected repository save keeps previous metadata and leaves configuration idle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        doAnswer { throw SyncStorageUnavailableException(IOException("local repository write failed")) }.`when`(storage).saveRepository("octocat", "replacement")
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.checkRepository("octocat", "replacement")).thenReturn(Result.success(
            GitHubRepositoryInfo(1, "replacement", "octocat/replacement", true, "main")
        ))

        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "existing-token") }.thenReturn(api)
            viewModel.useExistingRepository(context, "octocat/replacement")
            testScheduler.runCurrent()

            assertFalse(viewModel.uiState.value.isCheckingRepo)
            assertEquals("backup", viewModel.uiState.value.repoName)
            assertEquals(123L, viewModel.uiState.value.lastSyncTime)
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertEquals(null, viewModel.uiState.value.successMessage)
        }
    }

    @Test
    fun `a failed auto sync preference write keeps the previous setting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).setAutoSyncEnabled(false)

        viewModel.toggleAutoSync(context, false)
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.autoSyncEnabled)
        assertTrue(viewModel.uiState.value.isConfigured)
        assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a failed configuration clear keeps repository credentials and metadata visible`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).clearAll()

        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use {
            viewModel.clearConfiguration(context)
            testScheduler.runCurrent()

            assertTrue(viewModel.uiState.value.isConfigured)
            assertEquals("octocat", viewModel.uiState.value.repoOwner)
            assertEquals("backup", viewModel.uiState.value.repoName)
            assertEquals(123L, viewModel.uiState.value.lastSyncTime)
            assertEquals("existing-token", storage.getToken())
            assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun `a sync completion storage read failure does not report success or lose the previous time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).getLastCompletedSyncTime()
        viewModel.syncOperation = { Result.success(SyncResult(true, "completed")) }

        viewModel.performSync(context)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isSyncing)
        assertEquals(123L, viewModel.uiState.value.lastSyncTime)
        assertEquals(null, viewModel.uiState.value.successMessage)
        assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a secure storage exception thrown before sync returns becomes local feedback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = viewModel(storage())
        viewModel.syncOperation = { throw SyncStorageUnavailableException() }

        viewModel.performSync(context)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isSyncing)
        assertEquals(null, viewModel.uiState.value.successMessage)
        assertEquals(UiText.Resource(CoreCommonR.string.sync_secure_storage_unavailable), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `an old sync authentication failure cannot overwrite newly verified credentials`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        val complete = CompletableDeferred<Unit>()
        viewModel.syncOperation = { complete.await(); Result.failure(TokenExpiredException("old credential rejected")) }
        viewModel.performSync(context)
        testScheduler.runCurrent()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))

        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
            clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "replacement-token") }.thenReturn(api)
            try {
                viewModel.validateToken(context, "replacement-token")
                testScheduler.runCurrent()
                assertTrue(viewModel.uiState.value.tokenValid)

                complete.complete(Unit)
                testScheduler.runCurrent()

                assertTrue(viewModel.uiState.value.tokenValid)
                assertTrue(viewModel.uiState.value.autoSyncEnabled)
                assertEquals(null, viewModel.uiState.value.errorMessage)
                assertFalse(viewModel.uiState.value.isSyncing)
                verify(storage, never()).setAutoSyncEnabled(false)
                verify(storage, never()).clearToken()
            } finally {
                complete.complete(Unit)
            }
        }
    }

    @Test
    fun `authentication storage work released after token replacement cannot disable the new configuration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        val pendingStorage = mutableListOf<Runnable>()
        val holdingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pendingStorage += block
            }
        }
        viewModel.syncOperation = {
            viewModel.ioDispatcher = holdingDispatcher
            Result.failure(TokenExpiredException("old credential rejected"))
        }
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))

        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use { schedulers ->
            mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "replacement-token") }.thenReturn(api)
                viewModel.performSync(context)
                testScheduler.runCurrent()
                assertEquals(1, pendingStorage.size)
                viewModel.ioDispatcher = Dispatchers.Main
                viewModel.validateToken(context, "replacement-token")
                testScheduler.runCurrent()
                assertTrue(viewModel.uiState.value.tokenValid)

                pendingStorage.removeAt(0).run()
                testScheduler.runCurrent()

                assertTrue(viewModel.uiState.value.tokenValid)
                assertTrue(viewModel.uiState.value.autoSyncEnabled)
                assertEquals(null, viewModel.uiState.value.errorMessage)
                assertFalse(viewModel.uiState.value.isSyncing)
                verify(storage, never()).setAutoSyncEnabled(false)
                assertTrue(schedulers.constructed().flatMap { mockingDetails(it).invocations }.isEmpty())
            }
        }
    }

    @Test
    fun `a sync cannot disable credentials replaced through another view model`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        var storedToken = "existing-token"
        `when`(storage.getToken()).thenAnswer { storedToken }
        doAnswer { storedToken = it.getArgument(0); null }.`when`(storage).saveToken("replacement-token")
        `when`(storage.captureSyncMetadataGuard()).thenAnswer {
            val expected = storedToken
            val guard: (() -> Unit) -> Boolean = { write ->
                if (storedToken == expected) {
                    write()
                    true
                } else false
            }
            guard
        }
        val first = viewModel(storage)
        val second = viewModel(storage)
        first.initialize(context)
        testScheduler.runCurrent()
        val complete = CompletableDeferred<Unit>()
        first.syncOperation = { complete.await(); Result.failure(TokenExpiredException("old credential rejected")) }
        first.performSync(context)
        testScheduler.runCurrent()
        val api = mock(GitHubApiClient::class.java)
        `when`(api.validateToken()).thenReturn(Result.success("octocat"))

        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use { schedulers ->
            mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "replacement-token") }.thenReturn(api)
                try {
                    second.validateToken(context, "replacement-token")
                    testScheduler.runCurrent()
                    assertTrue(second.uiState.value.tokenValid)
                    complete.complete(Unit)
                    testScheduler.runCurrent()

                    assertEquals("replacement-token", storedToken)
                    assertTrue(first.uiState.value.autoSyncEnabled)
                    assertEquals(null, first.uiState.value.errorMessage)
                    assertTrue(second.uiState.value.tokenValid)
                    verify(storage, never()).setAutoSyncEnabled(false)
                    assertTrue(schedulers.constructed().flatMap { mockingDetails(it).invocations }.isEmpty())
                } finally {
                    complete.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `authentication handling cannot cancel sync scheduled after another configuration is saved`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = storage()
        var storedToken = "existing-token"
        var autoSyncEnabled = true
        doAnswer { storedToken = it.getArgument(0); null }.`when`(storage).saveToken("replacement-token")
        doAnswer { autoSyncEnabled = it.getArgument(0); null }.`when`(storage).setAutoSyncEnabled(org.mockito.ArgumentMatchers.anyBoolean())
        `when`(storage.captureSyncMetadataGuard()).thenAnswer {
            val expected = storedToken
            val guard: (() -> Unit) -> Boolean = { write ->
                if (storedToken == expected) {
                    write()
                    true
                } else false
            }
            guard
        }
        val viewModel = viewModel(storage)
        viewModel.initialize(context)
        testScheduler.runCurrent()
        val pendingStorage = mutableListOf<Runnable>()
        val holdingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pendingStorage += block
            }
        }
        viewModel.syncOperation = {
            viewModel.ioDispatcher = holdingDispatcher
            Result.failure(TokenExpiredException("old credential rejected"))
        }
        mockConstruction(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler")).use { schedulers ->
            viewModel.performSync(context)
            testScheduler.runCurrent()
            assertEquals(1, pendingStorage.size)
            pendingStorage.removeAt(0).run()
            assertFalse(autoSyncEnabled)

            storage.saveToken("replacement-token")
            storage.setAutoSyncEnabled(true)
            moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker.schedulePeriodicSync(context)
            testScheduler.runCurrent()

            val scheduledActions = schedulers.constructed().flatMap { scheduler ->
                mockingDetails(scheduler).invocations.map { it.method.name }
            }
            assertEquals("replacement-token", storedToken)
            assertTrue(autoSyncEnabled)
            assertEquals("schedulePeriodic", scheduledActions.last())
        }
    }

    private fun viewModel(storage: SecureTokenStorage? = null): GitHubSyncViewModel =
        GitHubSyncViewModel().also { viewModel ->
            viewModels += viewModel
            viewModel.ioDispatcher = Dispatchers.Main
            if (storage != null) {
                GitHubSyncViewModel::class.java.getDeclaredField("storage")
                    .apply { isAccessible = true }.set(viewModel, storage)
            }
        }

    private fun storage(): SecureTokenStorage = mock(SecureTokenStorage::class.java).also {
        `when`(it.isPersistent).thenReturn(true)
        `when`(it.isConfigured()).thenReturn(true)
        `when`(it.getToken()).thenReturn("existing-token")
        `when`(it.getRepoOwner()).thenReturn("octocat")
        `when`(it.getRepoName()).thenReturn("backup")
        `when`(it.isAutoSyncEnabled()).thenReturn(true)
        `when`(it.getLastCompletedSyncTime()).thenReturn(123L)
        `when`(it.observeLastCompletedSyncTime()).thenReturn(MutableStateFlow(123L))
    }
}
