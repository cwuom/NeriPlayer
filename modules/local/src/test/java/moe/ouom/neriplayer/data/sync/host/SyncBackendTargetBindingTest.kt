package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class SyncBackendTargetBindingTest {
    @Test
    fun `GitHub target mismatch stops before creating any backend or reading the token`() {
        for ((owner, repo) in listOf("other-owner" to "repo", "owner" to "other-repo")) {
            val context = mock(Context::class.java)
            val storage = mock(SecureTokenStorage::class.java)
            `when`(storage.getRepoOwner()).thenReturn(owner)
            `when`(storage.getRepoName()).thenReturn(repo)
            val expected = SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")

            val failure = assertThrows(IllegalStateException::class.java) {
                createGitHubSyncBackend(context, storage, expected)
            }

            assertEquals("Sync target changed", failure.message)
            verifyNoInteractions(context)
            verify(storage, never()).getToken()
        }
    }

    @Test
    fun `removing GitHub configuration stops a bound synchronization before backend creation`() {
        val context = mock(Context::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        val expected = SyncProtocolUpgradeRepository.githubTargetHash("owner", "repo")

        val failure = assertThrows(IllegalStateException::class.java) {
            createGitHubSyncBackend(context, storage, expected)
        }

        assertEquals("Sync target changed", failure.message)
        verifyNoInteractions(context)
        verify(storage, never()).getToken()
    }

    @Test
    fun `WebDAV binds the captured server directory and user before backend creation`() {
        val expected = SyncProtocolUpgradeRepository.webDavTargetHash("https://dav.test", "sync", "user")
        for ((server, path, username) in listOf(
            Triple("https://other.test", "sync", "user"),
            Triple("https://dav.test", "other", "user"),
            Triple("https://dav.test", "sync", "other-user")
        )) {
            val context = mock(Context::class.java)
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.getServerUrl()).thenReturn(server)
                `when`(storage.getBasePath()).thenReturn(path)
                `when`(storage.getUsername()).thenReturn(username)
            }.use { storages ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    createWebDavSyncBackend(context, expected)
                }

                assertEquals("Sync target changed", failure.message)
                verifyNoInteractions(context)
                verify(storages.constructed().single(), never()).getPassword()
            }
        }
    }

    @Test
    fun `removing WebDAV configuration stops a bound synchronization before backend creation`() {
        val context = mock(Context::class.java)
        val expected = SyncProtocolUpgradeRepository.webDavTargetHash("https://dav.test", "sync", "user")
        mockConstruction(WebDavStorage::class.java) { storage, _ ->
            `when`(storage.getBasePath()).thenReturn("")
        }.use { storages ->
            val failure = assertThrows(IllegalStateException::class.java) {
                createWebDavSyncBackend(context, expected)
            }

            assertEquals("Sync target changed", failure.message)
            verifyNoInteractions(context)
            verify(storages.constructed().single(), never()).getPassword()
        }
    }
}
