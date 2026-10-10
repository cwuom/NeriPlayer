package moe.ouom.neriplayer.core.startup.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.mockStatic

@OptIn(ExperimentalCoroutinesApi::class)
class StartupSyncSchedulerFlowTest {

    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @Test
    fun `opening the app queues both providers before any startup delay`() = runTest {
        val run = runScheduler(SyncSettings(gitHubReady = true, webDavReady = true))

        assertEquals(0L, currentTime)
        assertEquals(listOf("github@0", "webdav@0"), run.scheduled)
        assertEquals(listOf(0L, 10_000L), run.initialDelays)
        assertEquals(2, run.storagesOpened)
    }

    @Test
    fun `only GitHub is scheduled when WebDAV auto sync is off`() = runTest {
        val run = runScheduler(SyncSettings(gitHubReady = true, webDavConfigured = true, webDavAutoSync = false))

        assertEquals(listOf("github@0"), run.scheduled)
        assertEquals(listOf(0L), run.initialDelays)
        assertEquals(2, run.storagesOpened)
    }

    @Test
    fun `WebDAV alone is queued when the app opens`() = runTest {
        val run = runScheduler(SyncSettings(gitHubConfigured = true, gitHubAutoSync = false, webDavReady = true))

        assertEquals(listOf("webdav@0"), run.scheduled)
        assertEquals(listOf(0L), run.initialDelays)
    }

    @Test
    fun `default startup callbacks enqueue persistent work with startup input and no mutation`() = runTest {
        val manager = mock(WorkManager::class.java)
        val access = Class.forName("moe.ouom.neriplayer.data.sync.work.SyncWorkManagerAccessKt")
        val getManager = access.getDeclaredMethod("syncWorkManager", Context::class.java)
        mockStatic(access).use { workManager ->
            workManager.`when`<WorkManager> { getManager.invoke(null, context) }.thenReturn(manager)
            mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
                `when`(storage.isConfigured()).thenReturn(true)
                `when`(storage.isAutoSyncEnabled()).thenReturn(true)
            }.use { gitHubStorages ->
                mockConstruction(WebDavStorage::class.java) { storage, _ ->
                    `when`(storage.isConfigured()).thenReturn(true)
                    `when`(storage.isAutoSyncEnabled()).thenReturn(true)
                }.use {
                    StartupSyncScheduler(context, StandardTestDispatcher(testScheduler)).scheduleIfNeeded()
                    assertEquals(0L, currentTime)
                    assertEquals(0, gitHubStorages.constructed().sumOf { storage ->
                        mockingDetails(storage).invocations.count { it.method.name == "markSyncMutation" }
                    })
                }
            }
        }
        val work = mockingDetails(manager).invocations.toList()
        assertEquals(listOf("github_sync_work_startup", "webdav_sync_work_startup"),
            work.map { it.getArgument<String>(0) })
        work.forEachIndexed { index, invocation ->
            assertEquals(ExistingWorkPolicy.KEEP, invocation.getArgument<ExistingWorkPolicy>(1))
            val request = invocation.getArgument<OneTimeWorkRequest>(2)
            assertEquals(if (index == 0) 0L else 10_000L, request.workSpec.initialDelay)
            assertEquals(true, request.workSpec.input.getBoolean("trigger_by_app_startup", false))
            assertEquals(false, request.workSpec.input.getBoolean("trigger_by_user_action", true))
            assertEquals(false, request.workSpec.input.getBoolean("force_sync", false))
        }
    }

    @Test
    fun `nothing is scheduled when neither target is configured`() = runTest {
        val run = runScheduler(SyncSettings(gitHubAutoSync = true, webDavAutoSync = true))

        assertEquals(emptyList<String>(), run.scheduled)
        assertEquals(2, run.storagesOpened)
    }

    private class SyncSettings(
        gitHubReady: Boolean = false,
        webDavReady: Boolean = false,
        val gitHubConfigured: Boolean = gitHubReady,
        val gitHubAutoSync: Boolean = gitHubReady,
        val webDavConfigured: Boolean = webDavReady,
        val webDavAutoSync: Boolean = webDavReady
    )

    private class SchedulerRun(val scheduled: List<String>, val initialDelays: List<Long>, val storagesOpened: Int)

    private suspend fun TestScope.runScheduler(
        settings: SyncSettings
    ): SchedulerRun {
        val scheduled = mutableListOf<String>()
        val initialDelays = mutableListOf<Long>()
        return mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.isConfigured()).thenReturn(settings.gitHubConfigured)
            `when`(storage.isAutoSyncEnabled()).thenReturn(settings.gitHubAutoSync)
        }.use { gitHubStorages ->
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                `when`(storage.isConfigured()).thenReturn(settings.webDavConfigured)
                `when`(storage.isAutoSyncEnabled()).thenReturn(settings.webDavAutoSync)
            }.use { webDavStorages ->
                StartupSyncScheduler(
                    context = context,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    scheduleGitHubSync = { target, delay ->
                        assertSame(context, target)
                        scheduled += "github@$currentTime"
                        initialDelays += delay
                    },
                    scheduleWebDavSync = { target, delay ->
                        assertSame(context, target)
                        scheduled += "webdav@$currentTime"
                        initialDelays += delay
                    }
                ).scheduleIfNeeded()
                SchedulerRun(
                    scheduled = scheduled,
                    initialDelays = initialDelays,
                    storagesOpened = gitHubStorages.constructed().size + webDavStorages.constructed().size
                )
            }
        }
    }
}
