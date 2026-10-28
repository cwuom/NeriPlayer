package moe.ouom.neriplayer.core.player.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
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

    private fun withFixture(block: StartupFixture.() -> Unit) = runTest {
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
            val repositories = mock(PlayerRepositoryDependencies::class.java)
            environmentField.set(registry, null)
            PlayerDependencies.install(PlayerEnvironment(
                application, repositories, mock(PlayerDownloadAccess::class.java), FakeListenTogetherAccess(),
                presentation, { true }, { Job().apply { complete() } },
            ))
            PlayerManager.application = application
            PlayerManager.initialized = false
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
            applicationField.set(null, previousApplication)
            environmentField.set(registry, previousEnvironment)
        }
    }

    private companion object {
        fun serviceField(name: String) = AudioPlayerService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    }
}
