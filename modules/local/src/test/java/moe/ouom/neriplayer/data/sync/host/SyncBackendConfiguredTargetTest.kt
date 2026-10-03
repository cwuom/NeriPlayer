package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.Locale

class SyncBackendConfiguredTargetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matching and unbound WebDAV configurations use the captured directory and protocol target`() = runTest {
        val server = "https://dav.test/remote/"
        val directory = "user/sync"
        val username = "user"
        val target = SyncProtocolUpgradeRepository.webDavTargetHash(server, directory, username)
        val remoteUrl = WebDavApiClient.buildRemoteFileUrl(server, directory)
        val manifestUrl = WebDavApiClient.buildSiblingFileUrl(remoteUrl, SyncArchiveRepository.MANIFEST_FILE_NAME)
        for (expectedTarget in listOf(target, null)) {
            val context = localizedContext()
            val api = mock(WebDavApiClient::class.java)
            `when`(api.getFileContentStrict(manifestUrl))
                .thenReturn(Result.failure(WebDavFileNotFoundException("No manifest")))
            `when`(api.getFileContentStrict(remoteUrl))
                .thenReturn(Result.failure(WebDavFileNotFoundException("No legacy backup")))
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.captureSyncMetadataGuard()).thenReturn { write -> write(); true }
                `when`(storage.getServerUrl()).thenReturn(server)
                `when`(storage.getBasePath()).thenReturn(directory)
                `when`(storage.getUsername()).thenReturn(username)
                `when`(storage.getPassword()).thenReturn("test-password")
            }.use { storages ->
                mockConstruction(SecureTokenStorage::class.java).use {
                    mockConstruction(SyncProtocolUpgradeRepository::class.java).use { upgrades ->
                        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                            clients.`when`<WebDavApiClient> {
                                createWebDavSyncClient(context, username, "test-password")
                            }.thenReturn(api)

                            val backend = createWebDavSyncBackend(context, expectedTarget)
                            val snapshot = backend.fetch().getOrThrow()

                            assertNull(snapshot.dataset)
                            assertTrue(snapshot.version.createOnly)
                            assertTrue(backend.isFirstSync)
                            verify(api).getFileContentStrict(manifestUrl)
                            verify(api).getFileContentStrict(remoteUrl)
                            verify(upgrades.constructed().single()).markCurrent(target)
                            verify(storages.constructed().single()).getPassword()
                            clients.verify {
                                createWebDavSyncClient(context, username, "test-password")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `matching GitHub target continues to credential validation`() {
        val context = localizedContext()
        val storage = mock(SecureTokenStorage::class.java)
        `when`(storage.getRepoOwner()).thenReturn("owner")
        `when`(storage.getRepoName()).thenReturn("repo")
        val target = SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")

        val failure = assertThrows(IllegalStateException::class.java) {
            createGitHubSyncBackend(context, storage, target)
        }

        assertEquals(NotConfigured, failure.message)
        verify(storage).getToken()
        verify(context).getString(CoreCommonR.string.github_not_configured)
    }

    @Test
    fun `unbound GitHub with a partially removed repository reports missing configuration`() {
        for ((owner, repo) in listOf(null to "repo", "owner" to null, null to null)) {
            val context = localizedContext()
            val storage = mock(SecureTokenStorage::class.java)
            `when`(storage.getRepoOwner()).thenReturn(owner)
            `when`(storage.getRepoName()).thenReturn(repo)
            `when`(storage.getToken()).thenReturn("test-token")

            val failure = assertThrows(IllegalStateException::class.java) {
                createGitHubSyncBackend(context, storage)
            }

            assertEquals(NotConfigured, failure.message)
            verify(storage).getToken()
            verify(context).getString(CoreCommonR.string.github_not_configured)
        }
    }

    @Test
    fun `unbound incomplete WebDAV configurations report missing configuration before creating a client`() {
        for ((server, username, password) in listOf(
            Triple(null, "user", "password"),
            Triple("   ", "user", "password"),
            Triple("https://dav.test", null, "password"),
            Triple("https://dav.test", "user", null)
        )) {
            val context = localizedContext()
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.getServerUrl()).thenReturn(server)
                `when`(storage.getBasePath()).thenReturn("sync")
                `when`(storage.getUsername()).thenReturn(username)
                `when`(storage.getPassword()).thenReturn(password)
            }.use { storages ->
                mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.host.SyncApiClientsKt")).use { clients ->
                    val failure = assertThrows(IllegalStateException::class.java) {
                        createWebDavSyncBackend(context)
                    }

                    assertEquals(NotConfigured, failure.message)
                    clients.verifyNoInteractions()
                    verify(context).getString(CoreCommonR.string.webdav_not_configured)
                    val storage = storages.constructed().single()
                    if (server.isNullOrBlank() || username == null) {
                        verify(storage, never()).getPassword()
                    } else {
                        verify(storage).getPassword()
                    }
                }
            }
        }
    }

    @Test
    fun `bound WebDAV rejects blank server or missing username before localization or password access`() {
        val target = SyncProtocolUpgradeRepository.webDavTargetHash("https://dav.test", "sync", "user")
        for ((server, username) in listOf(" " to "user", "https://dav.test" to null)) {
            val context = mock(Context::class.java)
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.getServerUrl()).thenReturn(server)
                `when`(storage.getBasePath()).thenReturn("sync")
                `when`(storage.getUsername()).thenReturn(username)
            }.use { storages ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    createWebDavSyncBackend(context, target)
                }

                assertEquals("Sync target changed", failure.message)
                verifyNoInteractions(context)
                verify(storages.constructed().single(), never()).getPassword()
            }
        }
    }

    private fun localizedContext(): Context {
        val context = mock(Context::class.java)
        val applicationContext = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        `when`(context.applicationContext).thenReturn(applicationContext)
        `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(context.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales.size()).thenReturn(1)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        `when`(context.cacheDir).thenReturn(temporaryFolder.newFolder())
        `when`(context.getString(CoreCommonR.string.github_not_configured)).thenReturn(NotConfigured)
        `when`(context.getString(CoreCommonR.string.webdav_not_configured)).thenReturn(NotConfigured)
        return context
    }

    private companion object {
        const val NotConfigured = "Sync configuration is incomplete"
    }
}
