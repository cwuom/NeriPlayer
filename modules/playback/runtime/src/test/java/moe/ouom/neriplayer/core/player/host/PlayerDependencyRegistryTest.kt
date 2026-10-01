package moe.ouom.neriplayer.core.player.host

import android.app.Application
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class PlayerDependencyRegistryTest {
    @Test
    fun `uninstalled environment stays unready and fails clearly on access`() {
        val registry = PlayerDependencyRegistry()
        assertFalse(registry.isReady())
        assertThrows(IllegalStateException::class.java) { registry.requireEnvironment() }
    }

    @Test
    fun `early installation does not initialize repositories and readiness remains live`() {
        val registry = PlayerDependencyRegistry()
        var ready = false
        val environment = environment(mock(Application::class.java)) { ready }
        registry.install(environment)
        assertSame(environment, registry.requireEnvironment())
        assertFalse(registry.isReady())
        ready = true
        assertTrue(registry.isReady())
        verifyNoInteractions(environment.repositories, environment.downloads, environment.listenTogether, environment.presentation)
    }

    @Test
    fun `same application can refresh bindings`() {
        val registry = PlayerDependencyRegistry()
        val application = mock(Application::class.java)
        registry.install(environment(application))
        val updated = environment(application)
        registry.install(updated)
        assertSame(updated, registry.requireEnvironment())
    }

    @Test
    fun `another application cannot replace installed bindings`() {
        val registry = PlayerDependencyRegistry()
        val original = environment(mock(Application::class.java))
        registry.install(original)
        assertThrows(IllegalStateException::class.java) {
            registry.install(environment(mock(Application::class.java)))
        }
        assertSame(original, registry.requireEnvironment())
    }

    private fun environment(application: Application, ready: () -> Boolean = { true }): PlayerEnvironment =
        PlayerEnvironment(
            application, mock(PlayerRepositoryDependencies::class.java), mock(PlayerDownloadAccess::class.java),
            mock(PlayerListenTogetherAccess::class.java), mock(PlayerPresentationHost::class.java), ready,
            launchBackgroundIo = { Job().apply { complete() } }
        )
}
