package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerExecution
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction

class SyncWorkerProtocolBindingTest {
    @Test
    fun `GitHub factory checks only the currently configured repository permission`() = runTest {
        val context = mock(Context::class.java)
        var configured = true
        var owner = "owner"
        var repo = "first"
        val queried = mutableListOf<String>()
        val pending = mutableSetOf<String>()
        mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.isConfigured()).thenAnswer { configured }
            `when`(storage.isAutoSyncEnabled()).thenReturn(true)
            `when`(storage.getRepoOwner()).thenAnswer { owner }
            `when`(storage.getRepoName()).thenAnswer { repo }
        }.use {
            mockConstruction(SyncProtocolUpgradeRepository::class.java) { upgrades, _ ->
                runBlocking {
                    `when`(upgrades.canSyncTarget(anyString())).thenAnswer { invocation ->
                        val target = invocation.getArgument<String>(0)
                        queried += target
                        target !in pending
                    }
                }
            }.use {
                val host = createGitHubWorkerHost(context)
                val original = SyncProtocolUpgradeRepository.githubTargetHash(owner, repo)
                assertTrue(host.protocolUpgradeApproved())
                pending += original
                assertFalse(host.protocolUpgradeApproved())
                repo = "second"
                val otherRepository = SyncProtocolUpgradeRepository.githubTargetHash(owner, repo)
                assertTrue(host.protocolUpgradeApproved())
                owner = "other-owner"
                val otherOwner = SyncProtocolUpgradeRepository.githubTargetHash(owner, repo)
                assertTrue(host.protocolUpgradeApproved())
                assertEquals(listOf(original, original, otherRepository, otherOwner), queried)
                configured = false
                assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
                assertEquals(4, queried.size)
            }
        }
    }

    @Test
    fun `WebDAV factory binds permission to server directory and account`() = runTest {
        val context = mock(Context::class.java)
        var configured = true
        var server = "https://dav.example/remote.php/dav/files/"
        var directory = "first"
        var username = "alice"
        val queried = mutableListOf<String>()
        val pending = mutableSetOf<String>()
        mockConstruction(WebDavStorage::class.java) { storage, _ ->
            `when`(storage.isConfigured()).thenAnswer { configured }
            `when`(storage.isAutoSyncEnabled()).thenReturn(true)
            `when`(storage.getServerUrl()).thenAnswer { server }
            `when`(storage.getBasePath()).thenAnswer { directory }
            `when`(storage.getUsername()).thenAnswer { username }
        }.use {
            mockConstruction(SyncProtocolUpgradeRepository::class.java) { upgrades, _ ->
                runBlocking {
                    `when`(upgrades.canSyncTarget(anyString())).thenAnswer { invocation ->
                        val target = invocation.getArgument<String>(0)
                        queried += target
                        target !in pending
                    }
                }
            }.use {
                val host = createWebDavWorkerHost(context)
                val original = SyncProtocolUpgradeRepository.webDavTargetHash(server, directory, username)
                assertTrue(host.protocolUpgradeApproved())
                pending += original
                assertFalse(host.protocolUpgradeApproved())
                directory = "second"
                val otherDirectory = SyncProtocolUpgradeRepository.webDavTargetHash(server, directory, username)
                assertTrue(host.protocolUpgradeApproved())
                username = "bob"
                val otherAccount = SyncProtocolUpgradeRepository.webDavTargetHash(server, directory, username)
                assertTrue(host.protocolUpgradeApproved())
                server = "https://other.example/dav/"
                val otherServer = SyncProtocolUpgradeRepository.webDavTargetHash(server, directory, username)
                assertTrue(host.protocolUpgradeApproved())
                assertEquals(listOf(original, original, otherDirectory, otherAccount, otherServer), queried)
                configured = false
                assertEquals(SyncWorkerOutcome.SUCCESS, SyncWorkerExecution(host).execute(false, false))
                assertEquals(5, queried.size)
            }
        }
    }
}
