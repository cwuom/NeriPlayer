package moe.ouom.neriplayer.core.player.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import kotlinx.coroutines.CancellationException
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
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.service.car.CarPlaybackBinder
import moe.ouom.neriplayer.core.player.service.lifecycle.ScreenInteractiveMonitor
import moe.ouom.neriplayer.core.player.service.presentation.AndroidPlaybackServicePresentationPort
import moe.ouom.neriplayer.core.player.testing.FakeListenTogetherAccess
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PlaybackPreferenceFailureApplication::class)
class AudioPlayerServiceStartupTest {
    @Test
    fun `creation defers the session artwork and player runtime until a command or binding`() = withFixture {
        create()

        assertDeferredRuntime()
        assertFalse(shadow.isLastForegroundNotificationAttached)
    }

    @Test
    fun `the first playback command enters foreground before initializing its session`() = withFixture {
        observeSessionInitialization {
            assertTrue("foreground must be attached before session initialization", shadow.isLastForegroundNotificationAttached)
            assertNotNull(shadow.lastForegroundNotification)
        }
        create()

        start(1)

        assertNotNull(session())
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertFalse(serviceFlag("playerRuntimeReady"))
        assertNotNull(serviceField("playerInitializationJob").get(service))
    }

    @Test
    fun `car browsing creates a session without starting foreground playback`() = withFixture {
        create()

        val binding = bind()

        assertNotNull(binding.sessionToken)
        assertEquals(session()?.sessionToken, binding.sessionToken)
        assertFalse(binding.runtimeReady.value)
        assertFalse(shadow.isLastForegroundNotificationAttached)
        assertFalse(serviceFlag("hasReceivedStartCommand"))
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @Test
    fun `an unsupported binding does not initialize playback resources`() = withFixture {
        create()

        assertNull(service.onBind(Intent(service, AudioPlayerService::class.java).setAction("unsupported_bind")))

        assertDeferredRuntime()
        assertFalse(shadow.isLastForegroundNotificationAttached)
    }

    @Test
    fun `repeated playback starts and car rebinding retain the original session token`() = withFixture {
        create()
        start(1)
        val token = requireNotNull(session()).sessionToken

        start(2)
        val binding = bind()
        service.onUnbind(carIntent())
        service.onRebind(carIntent())

        assertEquals(token, session()?.sessionToken)
        assertEquals(token, binding.sessionToken)
        assertTrue(shadow.isLastForegroundNotificationAttached)
    }

    @Test
    fun `a browsing session keeps its token when playback starts`() = withFixture {
        create()
        val binding = bind()
        val token = requireNotNull(binding.sessionToken)

        start(1)
        start(2)

        assertEquals(token, session()?.sessionToken)
        assertEquals(token, binding.sessionToken)
        assertTrue(shadow.isLastForegroundNotificationAttached)
    }

    @Test
    fun `safe mode rejects browsing and stops startup without creating playback resources`() = withFixture {
        safeMode = true
        create()
        assertNull(service.onBind(carIntent()))

        start(1)

        assertDeferredRuntime()
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(shadow.isLastForegroundNotificationAttached)
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @Test
    fun `preference initialization failure stops an unbound service and clears pending playback`() = withFixture {
        failPreferencesWith(IllegalStateException("Playback preferences unavailable"))
        create()
        start(1)
        var pendingActionExecuted = false
        queuePlayerAction { pendingActionExecuted = true }
        assertEquals(1, queuedCount("pendingStartCommands"))
        assertTrue(shadow.isLastForegroundNotificationAttached)

        awaitInitialization()

        assertTrue(shadow.isStoppedBySelf)
        assertFalse(shadow.isLastForegroundNotificationAttached)
        assertFalse(serviceFlag("allowServiceRestart"))
        assertFalse(serviceFlag("hasReceivedStartCommand"))
        assertFalse(AudioPlayerService.isInstanceActiveForDiagnostics())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
        assertEquals(0, queuedCount("pendingStartCommands"))
        assertEquals(0, queuedCount("pendingPlayerActions"))
        assertFalse(pendingActionExecuted)
        assertFalse(PlayerManager.initialized)
    }

    @Test
    fun `preference initialization failure keeps a bound session available for a fresh playback start`() = withFixture {
        failPreferencesWith(IllegalStateException("Playback preferences unavailable"))
        create()
        val binding = bind()
        val token = requireNotNull(binding.sessionToken)
        start(1)
        var pendingActionExecuted = false
        queuePlayerAction { pendingActionExecuted = true }

        val failedInitialization = awaitInitialization()

        assertTrue(shadow.isStoppedBySelf)
        assertFalse(shadow.isLastForegroundNotificationAttached)
        assertFalse(serviceFlag("allowServiceRestart"))
        assertFalse(serviceFlag("hasReceivedStartCommand"))
        assertTrue(AudioPlayerService.isInstanceActiveForDiagnostics())
        assertFalse(binding.runtimeReady.value)
        assertEquals(token, session()?.sessionToken)
        assertEquals(token, binding.sessionToken)
        assertEquals(0, queuedCount("pendingStartCommands"))
        assertEquals(0, queuedCount("pendingPlayerActions"))
        assertFalse(pendingActionExecuted)

        failPreferencesWith(null)
        start(2)

        val retryInitialization = requireNotNull(initializationJob())
        assertNotSame(failedInitialization, retryInitialization)
        assertTrue(retryInitialization.isActive)
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertTrue(serviceFlag("allowServiceRestart"))
        assertTrue(serviceFlag("hasReceivedStartCommand"))
        assertEquals(token, binding.sessionToken)
        assertFalse(binding.runtimeReady.value)
        assertEquals(1, queuedCount("pendingStartCommands"))
    }

    @Test
    fun `cancelled preference initialization does not run foreground failure cleanup`() = withFixture {
        failPreferencesWith(CancellationException("Playback initialization cancelled"))
        create()
        start(1)
        var pendingActionExecuted = false
        queuePlayerAction { pendingActionExecuted = true }

        val cancelledInitialization = awaitInitialization()

        assertTrue(cancelledInitialization.isCancelled)
        assertFalse(shadow.isStoppedBySelf)
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertTrue(serviceFlag("allowServiceRestart"))
        assertTrue(serviceFlag("hasReceivedStartCommand"))
        assertTrue(AudioPlayerService.isForegroundActiveForDiagnostics())
        assertEquals(1, queuedCount("pendingStartCommands"))
        assertEquals(1, queuedCount("pendingPlayerActions"))
        assertFalse(pendingActionExecuted)
        assertFalse(PlayerManager.initialized)
    }

    private fun withFixture(block: suspend StartupFixture.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            StartupFixture().use { it.block() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class StartupFixture : AutoCloseable {
        private val application: Application = RuntimeEnvironment.getApplication()
        private val registry = PlayerDependencies::class.java.getDeclaredField("registry").apply {
            isAccessible = true
        }.get(null)
        private val environmentField = registry.javaClass.getDeclaredField("environment").apply {
            isAccessible = true
        }
        private val previousEnvironment = environmentField.get(registry)
        private val applicationField = PlayerManager::class.java.getDeclaredField("application").apply {
            isAccessible = true
        }
        private val previousApplication = applicationField.get(null)
        private val previousInitialized = PlayerManager.initialized
        private val previousInitializationInProgress = PlayerManager.initializationInProgress
        private val companionFields = listOf("isServiceInstanceActive", "isServiceForegroundActive", "activeServiceInstance")
            .associateWith { serviceField(it).get(null) }
        private val controller: org.robolectric.android.controller.ServiceController<AudioPlayerService>
        val service: AudioPlayerService
        val shadow get() = shadowOf(service)
        var safeMode = false

        init {
            shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            val presentation = mock(PlayerPresentationHost::class.java)
            doAnswer { safeMode }.`when`(presentation).shouldEnterSafeMode(application)
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.bluetoothMetadataModeFlow).thenReturn(MutableStateFlow(BluetoothMetadataMode.SongAndLyrics))
            val repositories = mock(PlayerRepositoryDependencies::class.java)
            `when`(repositories.settingsRepo).thenReturn(settings)
            environmentField.set(registry, null)
            PlayerDependencies.install(PlayerEnvironment(
                application, repositories, mock(PlayerDownloadAccess::class.java), FakeListenTogetherAccess(),
                presentation, { true }, { Job().apply { complete() } },
            ))
            PlayerManager.application = application
            PlayerManager.initialized = false
            PlayerManager.initializationInProgress = false
            controller = Robolectric.buildService(AudioPlayerService::class.java)
            service = controller.get()
            doAnswer { safeMode }.`when`(presentation).shouldEnterSafeMode(service)
            doAnswer { Intent(Intent.ACTION_MAIN).setPackage(application.packageName) }
                .`when`(presentation).mainActivityIntent(service)
        }

        fun create() { controller.create() }

        fun start(startId: Int) = service.onStartCommand(
            AudioPlayerService.createSyncIntent(service, "startup_test"), 0, startId,
        )

        fun carIntent() = Intent(service, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_BIND_CAR)

        fun bind() = requireNotNull(service.onBind(carIntent()) as? CarPlaybackBinder)

        fun failPreferencesWith(error: RuntimeException?) {
            (application as PlaybackPreferenceFailureApplication).preferenceFailure = error
        }

        fun initializationJob() = serviceField("playerInitializationJob").get(service) as Job?

        suspend fun awaitInitialization(): Job {
            val job = requireNotNull(initializationJob())
            job.join()
            return job
        }

        fun queuePlayerAction(action: () -> Unit) {
            val queue = serviceField("pendingPlayerActions").get(service)
            queue.javaClass.getMethod("addLast", Any::class.java).invoke(queue, action)
        }

        fun queuedCount(name: String) = (serviceField(name).get(service) as Collection<*>).size

        fun session(): MediaSession? {
            val port = serviceField("presentationPortDelegate").get(service) as Lazy<*>
            return if (port.isInitialized()) (port.value as AndroidPlaybackServicePresentationPort).sessionOrNull() else null
        }

        fun observeSessionInitialization(onInitialize: () -> Unit) {
            val port = spy(AndroidPlaybackServicePresentationPort(service))
            val callback = serviceField("mediaSessionCallback").get(service) as MediaSession.Callback
            doAnswer { invocation ->
                onInitialize()
                invocation.callRealMethod()
            }.`when`(port).initializeSession(callback)
            serviceField("presentationPortDelegate").set(service, lazy { port })
        }

        fun assertDeferredRuntime() {
            assertNull(session())
            assertFalse((serviceField("presentationOwnerDelegate").get(service) as Lazy<*>).isInitialized())
            assertFalse((serviceField("artworkOwnerDelegate").get(service) as Lazy<*>).isInitialized())
            assertFalse(serviceFlag("playerRuntimeReady"))
            assertNull(serviceField("playerInitializationJob").get(service))
            assertFalse(PlayerManager.initialized)
        }

        fun serviceFlag(name: String) = serviceField(name).getBoolean(service)

        override fun close() {
            (serviceField("serviceScope").get(service) as CoroutineScope).cancel()
            (serviceField("screenInteractiveMonitor").get(service) as ScreenInteractiveMonitor).stop()
            session()?.release()
            if (shadow.isLastForegroundNotificationAttached) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            companionFields.forEach { (name, value) -> serviceField(name).set(null, value) }
            PlayerManager.initialized = previousInitialized
            PlayerManager.initializationInProgress = previousInitializationInProgress
            applicationField.set(null, previousApplication)
            environmentField.set(registry, previousEnvironment)
            failPreferencesWith(null)
        }
    }

    private companion object {
        fun serviceField(name: String) = AudioPlayerService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    }
}

class PlaybackPreferenceFailureApplication : Application() {
    @Volatile
    var preferenceFailure: RuntimeException? = null

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        if (name == "playback_snapshot_cache") preferenceFailure?.let { throw it }
        return super.getSharedPreferences(name, mode)
    }
}
