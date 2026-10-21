package moe.ouom.neriplayer.core.player.service

import android.app.Application
import android.app.Notification
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.runtime.service.PlaybackServiceIdleShutdownCoordinator
import moe.ouom.neriplayer.core.player.timer.SleepTimerManager
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.playback.RestoredPlaybackState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlayerServiceRestoreFailureTest {
    @Test
    fun `unavailable restored source removes foreground service while keeping pending progress`() = withFixture {
        idleShutdown.refresh()
        val scheduledIdleJob = idleJob()
        assertTrue(scheduledIdleJob?.isActive == true)

        stopUnavailableSource()

        assertTrue(shadow.isStoppedBySelf)
        assertEquals(StartId, shadow.stopSelfResultId)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertFalse(shadow.isLastForegroundNotificationAttached)
        assertFalse(serviceFlag("allowServiceRestart"))
        assertFalse(serviceFlag("hasReceivedStartCommand"))
        assertFalse(serviceFlag("isForegroundStarted"))
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
        assertTrue(serviceFlag("keepPlayerRuntimeAfterServiceStop"))
        assertTrue(scheduledIdleJob?.isCancelled == true)
        assertNull(idleJob())

        invokeService("releasePlayerRuntimeAfterServiceStop", false)

        assertTrue(manager.initialized)
        assertPendingProgressUnchanged()
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `a newer service start protects its foreground notification from a late restore failure`() = withFixture {
        setServiceField("latestStartId", StartId + 1)

        stopUnavailableSource()

        assertServiceStillRunning()
        assertPendingProgressUnchanged()
    }

    @Test
    fun `manual playback started during validation protects the service from the old failure`() = withFixture {
        manager._isPlayingFlow.value = true
        manager._playWhenReadyFlow.value = true

        stopUnavailableSource()

        assertServiceStillRunning()
        assertTrue(manager.isPlayingFlow.value)
        assertTrue(manager.playWhenReadyFlow.value)
        assertPendingProgressUnchanged()
    }

    @Test
    fun `an in flight manual playback request protects the service before audio starts`() = withFixture {
        manager.playJob = Job()

        stopUnavailableSource()

        assertServiceStillRunning()
        assertTrue(manager.playJob?.isActive == true)
        assertPendingProgressUnchanged()
    }

    @Test
    fun `an active listen together session protects the service from a local restore failure`() = withFixture {
        session.value = ListenTogetherSessionState(roomId = "active-room", role = "listener")

        stopUnavailableSource()

        assertServiceStillRunning()
        assertPendingProgressUnchanged()
    }

    @Test
    fun `an opened or transitioning USB session protects the service from a local restore failure`() {
        listOf(UsbExclusiveNativeState(opened = true), UsbExclusiveNativeState(transitioning = true)).forEach { state ->
            withFixture {
                nativeState.value = state

                stopUnavailableSource()

                assertServiceStillRunning()
                assertPendingProgressUnchanged()
            }
        }
    }

    @Test
    fun `an active sleep timer protects its service from a local restore failure`() = withFixture {
        manager.sleepTimerManager.startFinishCurrent()

        stopUnavailableSource()

        assertServiceStillRunning()
        assertTrue(manager.sleepTimerManager.timerState.value.isActive)
        assertPendingProgressUnchanged()
    }

    @Test
    fun `a service already returned to binding only is not stopped by a late restore failure`() = withFixture {
        setServiceField("hasReceivedStartCommand", false)

        stopUnavailableSource()

        assertServiceStillRunning(receivedStart = false)
        assertPendingProgressUnchanged()
    }

    private fun withFixture(block: RestoreFailureFixture.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            RestoreFailureFixture(this).use { fixture ->
                val persistenceClass = Class.forName(
                    "moe.ouom.neriplayer.core.player.persistence.PlayerManagerStatePersistenceExtensionsKt"
                )
                mockStatic(persistenceClass).use { persistence ->
                    persistence.`when`<Unit> {
                        fixture.manager.scheduleStatePersist(PositionMs, false, 0L)
                    }.thenAnswer {
                        fixture.persistedResumeIntent += false
                        null
                    }
                    fixture.block()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class RestoreFailureFixture(scope: CoroutineScope) : AutoCloseable {
        val manager = PlayerManager
        private val applicationField = managerField("application")
        private val previousApplication = applicationField.get(null)
        private val previousInitialized = manager.initialized
        private val previousQueue = manager.currentQueueSnapshot()
        private val previousSong = manager.currentSongFlow.value
        private val previousPosition = manager.playbackPositionFlow.value
        private val previousRestore = manager.restoredPlaybackSnapshot()
        private val previousPlaying = manager.isPlayingFlow.value
        private val previousPlayWhenReady = manager.playWhenReadyFlow.value
        private val previousPlayerState = manager.playerPlaybackStateFlow.value
        private val previousResumeRequested = manager.resumePlaybackRequested
        private val previousPlayJob = manager.playJob
        private val previousPauseJob = manager.pendingPauseJob
        private val previousSleepTimer = manager.sleepTimerManager
        private val registry = PlayerDependencies::class.java.getDeclaredField("registry").apply {
            isAccessible = true
        }.get(null)
        private val environmentField = registry.javaClass.getDeclaredField("environment").apply {
            isAccessible = true
        }
        private val previousEnvironment = environmentField.get(registry)
        private val foregroundField = serviceField("isServiceForegroundActive")
        private val previousForegroundActive = foregroundField.getBoolean(null)
        private val application: Application = RuntimeEnvironment.getApplication()
        val session = MutableStateFlow(ListenTogetherSessionState())
        val persistedResumeIntent = mutableListOf<Boolean>()
        // 只替换状态流，不打开原生 USB 会话
        @Suppress("UNCHECKED_CAST")
        val nativeState = UsbExclusiveSessionController::class.java.getDeclaredField("_state").apply {
            isAccessible = true
        }.get(null) as MutableStateFlow<UsbExclusiveNativeState>
        private val previousNativeState = nativeState.value
        val service = Robolectric.buildService(AudioPlayerService::class.java).get()
        val shadow = shadowOf(service)
        val idleShutdown = serviceField("idleShutdownCoordinator").get(service) as PlaybackServiceIdleShutdownCoordinator
        private val song = SongItem(
            id = 551L, name = "Unavailable restored source", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, localFilePath = "/missing/restored-source.mp3"
        )
        private val pending: RestoredPlaybackState

        init {
            val listenTogether = mock(PlayerListenTogetherAccess::class.java)
            doReturn(session).`when`(listenTogether).sessionState
            doReturn(MutableStateFlow<ListenTogetherRoomState?>(null)).`when`(listenTogether).roomState
            environmentField.set(registry, null)
            PlayerDependencies.install(PlayerEnvironment(
                application, mock(PlayerRepositoryDependencies::class.java), mock(PlayerDownloadAccess::class.java),
                listenTogether, mock(PlayerPresentationHost::class.java), { true }, { Job().apply { complete() } }
            ))
            manager.application = application
            manager.initialized = true
            manager.publishCurrentQueue(listOf(song), 0)
            manager._currentSongFlow.value = song
            manager._playbackPositionMs.value = PositionMs
            manager._isPlayingFlow.value = false
            manager._playWhenReadyFlow.value = false
            manager._playerPlaybackStateFlow.value = Player.STATE_IDLE
            managerField("resumePlaybackRequested").setBoolean(null, false)
            manager.playJob = null
            manager.pendingPauseJob = null
            manager.sleepTimerManager = SleepTimerManager(scope, {})
            manager.setRestoredPlayback(PositionMs, true)
            pending = manager.restoredPlaybackSnapshot()
            nativeState.value = UsbExclusiveNativeState()

            service.startForeground(1, Notification.Builder(application, "restore-test")
                .setSmallIcon(android.R.drawable.ic_media_play).build())
            setServiceField("playerRuntimeReady", true)
            setServiceField("hasReceivedStartCommand", true)
            setServiceField("isForegroundStarted", true)
            setServiceField("latestStartId", StartId)
            foregroundField.setBoolean(null, true)
        }

        fun stopUnavailableSource() = invokeService("stopServiceForUnavailableRestoredSource", StartId)

        fun invokeService(name: String, argument: Any) {
            val type = when (argument) {
                is Int -> Int::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> error("Unsupported service method argument")
            }
            AudioPlayerService::class.java.getDeclaredMethod(name, type).apply {
                isAccessible = true
            }.invoke(service, argument)
        }

        fun setServiceField(name: String, value: Any) = serviceField(name).set(service, value)

        fun serviceFlag(name: String): Boolean = serviceField(name).getBoolean(service)

        fun idleJob(): Job? = PlaybackServiceIdleShutdownCoordinator::class.java
            .getDeclaredField("shutdownJob").apply { isAccessible = true }.get(idleShutdown) as Job?

        fun assertServiceStillRunning(receivedStart: Boolean = true) {
            assertFalse(shadow.isStoppedBySelf)
            assertFalse(shadow.isForegroundStopped)
            assertTrue(shadow.isLastForegroundNotificationAttached)
            assertTrue(serviceFlag("allowServiceRestart"))
            assertEquals(receivedStart, serviceFlag("hasReceivedStartCommand"))
            assertTrue(serviceFlag("isForegroundStarted"))
            assertTrue(AudioPlayerService.isForegroundActiveForDiagnostics())
            assertFalse(serviceFlag("keepPlayerRuntimeAfterServiceStop"))
            assertTrue(persistedResumeIntent.isEmpty())
        }

        fun assertPendingProgressUnchanged() {
            assertSame(pending, manager.restoredPlaybackSnapshot())
            assertEquals(PositionMs, manager.playbackPositionFlow.value)
            assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
            assertEquals(0, manager.currentQueueSnapshot().currentIndex)
            assertSame(song, manager.currentSongFlow.value)
        }

        override fun close() {
            (serviceField("serviceScope").get(service) as CoroutineScope).cancel()
            if (manager.playJob !== previousPlayJob) manager.playJob?.cancel()
            manager.sleepTimerManager.cancel(notifyStateChanged = false)
            manager.sleepTimerManager = previousSleepTimer
            manager.playJob = previousPlayJob
            manager.pendingPauseJob = previousPauseJob
            managerField("resumePlaybackRequested").setBoolean(null, previousResumeRequested)
            manager._isPlayingFlow.value = previousPlaying
            manager._playWhenReadyFlow.value = previousPlayWhenReady
            manager._playerPlaybackStateFlow.value = previousPlayerState
            manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
            manager._currentSongFlow.value = previousSong
            manager._playbackPositionMs.value = previousPosition
            manager.initialized = previousInitialized
            applicationField.set(null, previousApplication)
            environmentField.set(registry, previousEnvironment)
            nativeState.value = previousNativeState
            foregroundField.setBoolean(null, previousForegroundActive)
            when (previousRestore) {
                RestoredPlaybackState.None -> manager.clearRestoredPlayback()
                is RestoredPlaybackState.Paused -> manager.setRestoredPlayback(previousRestore.positionMs, false)
                is RestoredPlaybackState.ResumePending -> manager.setRestoredPlayback(previousRestore.positionMs, true)
            }
        }
    }

    private companion object {
        const val StartId = 41
        const val PositionMs = 12_345L

        fun serviceField(name: String) = AudioPlayerService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }

        fun managerField(name: String) = PlayerManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    }
}
