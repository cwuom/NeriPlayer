package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.github.GitHubSyncManager
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncManager
import moe.ouom.neriplayer.data.sync.work.createGitHubWorkerHost
import moe.ouom.neriplayer.data.sync.work.createWebDavWorkerHost
import moe.ouom.neriplayer.data.sync.work.syncWorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import java.util.Locale

class SyncStartupContinuationBindingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `GitHub backend follow up preserves startup origin and ordinary requests retain their chain`() {
        withSchedulingFixture { context, manager ->
            mockConstruction(SecureTokenStorage::class.java) { storage, _ -> configureGitHub(storage) }.use {
                mockConstruction(SyncProtocolUpgradeRepository::class.java).use {
                    mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                        clients.`when`<GitHubApiClient> { createGitHubSyncClient(context, "test-token") }
                            .thenReturn(mock(GitHubApiClient::class.java))
                        for (startup in listOf(true, false)) {
                            val storage = SecureTokenStorage(context)
                            val backend = if (startup) createGitHubSyncBackend(context, storage, triggerByAppStartup = true)
                                else createGitHubSyncBackend(context, storage)
                            backend.scheduleFollowUp()
                        }
                    }
                }
            }
            assertContinuationRequests(manager, "github_sync_work")
        }
    }

    @Test
    fun `WebDAV backend follow up preserves startup origin and ordinary requests retain their chain`() {
        withSchedulingFixture { context, manager ->
            mockConstruction(WebDavStorage::class.java) { storage, _ -> configureWebDav(storage) }.use {
                mockConstruction(SecureTokenStorage::class.java).use {
                    mockConstruction(SyncProtocolUpgradeRepository::class.java).use {
                        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                            clients.`when`<WebDavApiClient> { createWebDavSyncClient(context, "user", "test-password") }
                                .thenReturn(mock(WebDavApiClient::class.java))
                            for (startup in listOf(true, false)) {
                                val backend = if (startup) createWebDavSyncBackend(context, triggerByAppStartup = true)
                                    else createWebDavSyncBackend(context)
                                backend.scheduleFollowUp()
                            }
                        }
                    }
                }
            }
            assertContinuationRequests(manager, "webdav_sync_work")
        }
    }

    @Test
    fun `GitHub worker host passes startup origin to the manager and preserves the ordinary entry`() = runTest {
        val context = context()
        val result = Result.success(SyncResult(true, "synced"))
        withFreshManager(GitHubSyncManager::class.java) {
            mockConstruction(GitHubSyncManager::class.java) { manager, _ ->
                runBlocking {
                    `when`(manager.performSync()).thenReturn(result)
                    `when`(manager.performSync(anyBoolean())).thenReturn(result)
                }
            }.use { managers ->
                assertEquals(result, createGitHubWorkerHost(context, triggerByAppStartup = true).synchronize())
                assertEquals(result, createGitHubWorkerHost(context).synchronize())
                val manager = managers.constructed().single()
                verify(manager).performSync(true)
                verify(manager).performSync()
            }
        }
    }

    @Test
    fun `WebDAV worker host passes startup origin to the manager and preserves the ordinary entry`() = runTest {
        val context = context()
        val result = Result.success(SyncResult(true, "synced"))
        withFreshManager(WebDavSyncManager::class.java) {
            mockConstruction(WebDavSyncManager::class.java) { manager, _ ->
                runBlocking {
                    `when`(manager.performSync()).thenReturn(result)
                    `when`(manager.performSync(anyBoolean())).thenReturn(result)
                }
            }.use { managers ->
                assertEquals(result, createWebDavWorkerHost(context, triggerByAppStartup = true).synchronize())
                assertEquals(result, createWebDavWorkerHost(context).synchronize())
                val manager = managers.constructed().single()
                verify(manager).performSync(true)
                verify(manager).performSync()
            }
        }
    }

    private fun withSchedulingFixture(action: (Context, WorkManager) -> Unit) {
        val context = context()
        val manager = mock(WorkManager::class.java)
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkManagerAccessKt")).use { work ->
            work.`when`<WorkManager> { syncWorkManager(context) }.thenReturn(manager)
            action(context, manager)
        }
    }

    private fun context(): Context {
        val context = mock(Context::class.java)
        val application = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        `when`(context.applicationContext).thenReturn(application)
        `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(context.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales.size()).thenReturn(1)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        `when`(context.cacheDir).thenReturn(temporary.root)
        `when`(context.getString(anyInt())).thenReturn("sync fixture")
        return context
    }

    private fun configureGitHub(storage: SecureTokenStorage) {
        `when`(storage.isConfigured()).thenReturn(true)
        `when`(storage.isAutoSyncEnabled()).thenReturn(true)
        `when`(storage.getToken()).thenReturn("test-token")
        `when`(storage.getRepoOwner()).thenReturn("owner")
        `when`(storage.getRepoName()).thenReturn("repo")
        `when`(storage.captureSyncMetadataGuard()).thenReturn { write -> write(); true }
    }

    private fun configureWebDav(storage: WebDavStorage) {
        `when`(storage.isConfigured()).thenReturn(true)
        `when`(storage.isAutoSyncEnabled()).thenReturn(true)
        `when`(storage.getServerUrl()).thenReturn("https://dav.test/")
        `when`(storage.getBasePath()).thenReturn("sync")
        `when`(storage.getUsername()).thenReturn("user")
        `when`(storage.getPassword()).thenReturn("test-password")
        `when`(storage.captureSyncMetadataGuard()).thenReturn { write -> write(); true }
    }

    private fun assertContinuationRequests(manager: WorkManager, providerName: String) {
        val requests = mockingDetails(manager).invocations.toList()
        assertEquals(listOf("${providerName}_startup", providerName), requests.map { it.getArgument<String>(0) })
        requests.forEachIndexed { index, invocation ->
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, invocation.getArgument<ExistingWorkPolicy>(1))
            val request = invocation.getArgument<OneTimeWorkRequest>(2)
            assertEquals(index == 0, request.workSpec.input.getBoolean("trigger_by_app_startup", false))
            assertFalse(request.workSpec.input.getBoolean("trigger_by_user_action", true))
            assertFalse(request.workSpec.input.getBoolean("force_sync", false))
            assertTrue(request.tags.contains(providerName))
        }
    }

    private suspend fun <T> withFreshManager(type: Class<*>, action: suspend () -> T): T {
        val holder = type.getDeclaredField("instance").also { it.isAccessible = true }.get(null)
        val field = holder.javaClass.getDeclaredField("instance").also { it.isAccessible = true }
        val previous = field.get(holder)
        field.set(holder, null)
        return try { action() } finally { field.set(holder, previous) }
    }
}
