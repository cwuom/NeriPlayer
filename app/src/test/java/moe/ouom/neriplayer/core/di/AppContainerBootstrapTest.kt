package moe.ouom.neriplayer.core.di

import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerBootstrapTest {

    @Test
    fun `resolveInitialBypassProxy prefers persisted setting`() {
        val resolved = resolveInitialBypassProxy(currentValue = true) { false }

        assertFalse(resolved)
    }

    @Test
    fun `resolveInitialBypassProxy falls back to current value when loading fails`() {
        val resolved = resolveInitialBypassProxy(currentValue = true) {
            error("boom")
        }

        assertTrue(resolved)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings prefers persisted values`() {
        val resolved = resolveInitialManagedDownloadSettings(
            loadDirectoryUri = { "content://downloads/tree/neri" },
            loadDirectoryLabel = { "SD Card" },
            loadFileNameTemplate = { "%title%" }
        )

        assertEquals("content://downloads/tree/neri", resolved.directoryUri)
        assertEquals("SD Card", resolved.directoryLabel)
        assertEquals("%title%", resolved.fileNameTemplate)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings normalizes blanks to null`() {
        val resolved = resolveInitialManagedDownloadSettings(
            loadDirectoryUri = { " " },
            loadDirectoryLabel = { "" },
            loadFileNameTemplate = { " " }
        )

        assertNull(resolved.directoryUri)
        assertNull(resolved.directoryLabel)
        assertNull(resolved.fileNameTemplate)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings falls back to current values when loading fails`() {
        val resolved = resolveInitialManagedDownloadSettings(
            currentDirectoryUri = "content://downloads/tree/current",
            currentDirectoryLabel = "Current",
            currentFileNameTemplate = "%artist%",
            loadDirectoryUri = { error("boom-uri") },
            loadDirectoryLabel = { error("boom-label") },
            loadFileNameTemplate = { error("boom-template") }
        )

        assertEquals("content://downloads/tree/current", resolved.directoryUri)
        assertEquals("Current", resolved.directoryLabel)
        assertEquals("%artist%", resolved.fileNameTemplate)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings preserves healthy field when sibling load fails`() {
        val resolved = resolveInitialManagedDownloadSettings(
            currentDirectoryUri = "content://downloads/tree/current",
            currentDirectoryLabel = "Current",
            currentFileNameTemplate = "%artist%",
            loadDirectoryUri = { error("boom-uri") },
            loadDirectoryLabel = { "USB Music" },
            loadFileNameTemplate = { "%album% - %title%" }
        )

        assertEquals("content://downloads/tree/current", resolved.directoryUri)
        assertEquals("USB Music", resolved.directoryLabel)
        assertEquals("%album% - %title%", resolved.fileNameTemplate)
    }

    @Test
    fun `handleYouTubeAuthStateChanged clears caches without canceling in-flight playback`() {
        val steps = mutableListOf<String>()
        var cancelInFlightPlayableAudio: Boolean? = null

        handleYouTubeAuthStateChanged(
            bundle = YouTubeAuthBundle(cookies = mapOf("SAPISID" to "cookie")),
            clearBootstrapCache = { steps += "client" },
            clearPlaybackAuthBoundCaches = { cancelInFlight ->
                cancelInFlightPlayableAudio = cancelInFlight
                steps += "playback"
            },
            evictConnections = { steps += "connections" },
            warmBootstrapAsync = { steps += "warm" }
        )

        assertEquals(listOf("client", "playback", "connections", "warm"), steps)
        assertFalse(cancelInFlightPlayableAudio ?: true)
    }

    @Test
    fun `handleYouTubeAuthStateChanged skips warm bootstrap without login cookies`() {
        var warmCalls = 0

        handleYouTubeAuthStateChanged(
            bundle = YouTubeAuthBundle(),
            clearBootstrapCache = {},
            clearPlaybackAuthBoundCaches = {},
            evictConnections = {},
            warmBootstrapAsync = { warmCalls += 1 }
        )

        assertEquals(0, warmCalls)
    }

    @Test
    fun `warmYouTubePlaybackIfAuthorized triggers warm bootstrap when login cookies exist`() {
        var warmCalls = 0

        warmYouTubePlaybackIfAuthorized(
            bundle = YouTubeAuthBundle(cookies = mapOf("SAPISID" to "cookie")),
            warmBootstrapAsync = { warmCalls += 1 }
        )

        assertEquals(1, warmCalls)
    }

    @Test
    fun `warmYouTubePlaybackIfAuthorized skips warm bootstrap without login cookies`() {
        var warmCalls = 0

        warmYouTubePlaybackIfAuthorized(
            bundle = YouTubeAuthBundle(),
            warmBootstrapAsync = { warmCalls += 1 }
        )

        assertEquals(0, warmCalls)
    }
}
