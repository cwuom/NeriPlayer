package moe.ouom.neriplayer.core.startup.sync

import android.content.Context
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

@OptIn(ExperimentalCoroutinesApi::class)
class StartupSyncSchedulerFlowTest {

    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @Test
    fun `stopping during the initial delay skips reading sync settings`() = runTest {
        val run = runScheduler(SyncSettings(gitHubReady = true, webDavReady = true), isStarted = { false })

        assertEquals(StartupSyncPlanner.STARTUP_SYNC_SCHEDULE_DELAY_MS, currentTime)
        assertEquals(emptyList<String>(), run.scheduled)
        assertEquals(0, run.storagesOpened)
    }

    @Test
    fun `only GitHub is scheduled when WebDAV auto sync is off`() = runTest {
        val run = runScheduler(SyncSettings(gitHubReady = true, webDavConfigured = true, webDavAutoSync = false))

        assertEquals(listOf("github@20000"), run.scheduled)
        assertEquals(2, run.storagesOpened)
    }

    @Test
    fun `WebDAV alone is scheduled right after the initial delay`() = runTest {
        val run = runScheduler(SyncSettings(gitHubConfigured = true, gitHubAutoSync = false, webDavReady = true))

        assertEquals(listOf("webdav@20000"), run.scheduled)
    }

    @Test
    fun `WebDAV is staggered behind GitHub when both are enabled`() = runTest {
        val run = runScheduler(SyncSettings(gitHubReady = true, webDavReady = true))

        assertEquals(listOf("github@20000", "webdav@30000"), run.scheduled)
    }

    @Test
    fun `WebDAV is dropped when startup stops during the stagger`() = runTest {
        var started = true

        val run = runScheduler(
            SyncSettings(gitHubReady = true, webDavReady = true),
            isStarted = { started },
            afterGitHubScheduled = { started = false }
        )

        assertEquals(listOf("github@20000"), run.scheduled)
        assertEquals(30_000L, currentTime)
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

    private class SchedulerRun(val scheduled: List<String>, val storagesOpened: Int)

    private suspend fun TestScope.runScheduler(
        settings: SyncSettings,
        isStarted: () -> Boolean = { true },
        afterGitHubScheduled: () -> Unit = {}
    ): SchedulerRun {
        val scheduled = mutableListOf<String>()
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
                    isStarted = isStarted,
                    scheduleGitHubSync = { target ->
                        assertSame(context, target)
                        scheduled += "github@$currentTime"
                        afterGitHubScheduled()
                    },
                    scheduleWebDavSync = { target ->
                        assertSame(context, target)
                        scheduled += "webdav@$currentTime"
                    }
                ).scheduleIfNeeded()
                SchedulerRun(
                    scheduled = scheduled,
                    storagesOpened = gitHubStorages.constructed().size + webDavStorages.constructed().size
                )
            }
        }
    }
}
