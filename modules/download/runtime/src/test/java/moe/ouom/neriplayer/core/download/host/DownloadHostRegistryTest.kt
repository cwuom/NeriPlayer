package moe.ouom.neriplayer.core.download.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock

class DownloadHostRegistryTest {
    @Test
    fun missingInstallationFailsBeforeStartingWork() {
        val failure = assertThrows(IllegalStateException::class.java) {
            DownloadHostRegistry().bindings()
        }
        assertEquals("Download hosts must be installed before starting downloads", failure.message)
    }

    @Test
    fun replacementKeepsPreviouslyCapturedBindingsConsistent() {
        val registry = DownloadHostRegistry()
        val first = bindings()
        val replacement = bindings()
        registry.install(first)
        val captured = registry.bindings()
        registry.install(replacement)
        assertSame(replacement, registry.bindings())
        assertSame(first, captured)
    }

    private fun bindings() = DownloadHostBindings(
        environment = mock(DownloadEnvironment::class.java),
        sources = mock(DownloadSourceServices::class.java),
        lyrics = mock(DownloadLyricServices::class.java),
        credentials = mock(DownloadCredentials::class.java),
        playback = mock(DownloadedPlaybackHost::class.java)
    )
}
