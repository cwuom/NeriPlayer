package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.mockStatic

class SyncWorkerCompatibilityTest {
    @Test
    fun `both worker facades retain automatic manual and default request contract`() {
        val context = mock(Context::class.java)
        val manager = mock(WorkManager::class.java)
        var configured = false
        var enabled = true
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkManagerAccessKt")).use { workManager ->
            workManager.`when`<WorkManager> { syncWorkManager(context) }.thenReturn(manager)
            mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
                `when`(storage.isConfigured()).thenAnswer { configured }
                `when`(storage.isAutoSyncEnabled()).thenAnswer { enabled }
            }.use { github ->
                mockConstruction(WebDavStorage::class.java) { storage, _ ->
                    `when`(storage.isConfigured()).thenAnswer { configured }
                    `when`(storage.isAutoSyncEnabled()).thenAnswer { enabled }
                }.use {
                    GitHubSyncWorker.scheduleDelayedSync(context, false, true, 100, false)
                    WebDavSyncWorker.scheduleDelayedSync(context, false, true, 100, false)
                    assertTrue(mockingDetails(manager).invocations.isEmpty())
                    assertEquals(2, github.constructed().sumOf { storage ->
                        mockingDetails(storage).invocations.count { it.method.name == "markSyncMutation" }
                    })
                    configured = true
                    enabled = false
                    GitHubSyncWorker.scheduleDelayedSync(context)
                    WebDavSyncWorker.scheduleDelayedSync(context)
                    assertTrue(mockingDetails(manager).invocations.isEmpty())
                    enabled = true
                    GitHubSyncWorker.scheduleDelayedSync(context)
                    WebDavSyncWorker.scheduleDelayedSync(context)
                    GitHubSyncWorker.scheduleDelayedSync(context, true, false, 60_000, true)
                    WebDavSyncWorker.scheduleDelayedSync(context, true, false, 60_000, true)
                    val invocations = mockingDetails(manager).invocations.toList()
                    assertEquals(listOf("github_sync_work", "webdav_sync_work", "github_sync_work", "webdav_sync_work"),
                        invocations.map { it.getArgument<String>(0) })
                    for ((index, invocation) in invocations.withIndex()) {
                        val request = invocation.getArgument<OneTimeWorkRequest>(2)
                        val manual = index >= 2
                        assertEquals(if (manual) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                            invocation.getArgument<ExistingWorkPolicy>(1))
                        assertEquals(if (manual) 60_000L else 5_000L, request.workSpec.initialDelay)
                        assertEquals(manual, request.workSpec.input.getBoolean("trigger_by_user_action", !manual))
                        val expectedClass = if (index % 2 == 0) "moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker"
                            else "moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker"
                        assertEquals(expectedClass, request.workSpec.workerClassName)
                    }
                }
            }
        }
    }

    @Test
    fun `legacy entrypoints retain periodic tags cancellation and immediate input`() {
        val context = mock(Context::class.java)
        val manager = mock(WorkManager::class.java)
        mockStatic(Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkManagerAccessKt")).use { workManager ->
            workManager.`when`<WorkManager> { syncWorkManager(context) }.thenReturn(manager)
            GitHubSyncWorker.schedulePeriodicSync(context)
            WebDavSyncWorker.schedulePeriodicSync(context)
            GitHubSyncWorker.cancelAllSync(context)
            WebDavSyncWorker.cancelAllSync(context)
            GitHubSyncWorker.syncNow(context)
        }
        val invocations = mockingDetails(manager).invocations.toList()
        assertEquals(listOf("github_sync_periodic", "webdav_sync_periodic", "github_sync_work", "github_sync_periodic",
            "webdav_sync_work", "webdav_sync_periodic"), invocations.dropLast(1).map { it.getArgument<String>(0) })
        for ((invocation, providerTag) in invocations.takeLast(1).zip(listOf("github_sync_work"))) {
            val now = invocation.getArgument<OneTimeWorkRequest>(0)
            assertTrue(now.workSpec.input.getBoolean("force_sync", false))
            assertTrue(now.tags.contains("sync_now"))
            assertTrue(now.tags.contains(providerTag))
        }
        assertEquals(ExistingWorkPolicy.KEEP, GitHubSyncWorker.delayedSyncWorkPolicy(false, false))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, WebDavSyncWorker.delayedSyncWorkPolicy(false, true))
        assertEquals(0L, GitHubSyncWorker.buildDelayedSyncRequest(true, -1).workSpec.initialDelay)
        assertEquals(7L, WebDavSyncWorker.buildDelayedSyncRequest(false, 7).workSpec.initialDelay)
    }
}
