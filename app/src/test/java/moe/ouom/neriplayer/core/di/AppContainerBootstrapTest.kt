package moe.ouom.neriplayer.core.di

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
            loadDirectoryLabel = { "SD Card" }
        )

        assertEquals("content://downloads/tree/neri", resolved.directoryUri)
        assertEquals("SD Card", resolved.directoryLabel)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings normalizes blanks to null`() {
        val resolved = resolveInitialManagedDownloadSettings(
            loadDirectoryUri = { " " },
            loadDirectoryLabel = { "" }
        )

        assertNull(resolved.directoryUri)
        assertNull(resolved.directoryLabel)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings falls back to current values when loading fails`() {
        val resolved = resolveInitialManagedDownloadSettings(
            currentDirectoryUri = "content://downloads/tree/current",
            currentDirectoryLabel = "Current",
            loadDirectoryUri = { error("boom-uri") },
            loadDirectoryLabel = { error("boom-label") }
        )

        assertEquals("content://downloads/tree/current", resolved.directoryUri)
        assertEquals("Current", resolved.directoryLabel)
    }

    @Test
    fun `resolveInitialManagedDownloadSettings preserves healthy field when sibling load fails`() {
        val resolved = resolveInitialManagedDownloadSettings(
            currentDirectoryUri = "content://downloads/tree/current",
            currentDirectoryLabel = "Current",
            loadDirectoryUri = { error("boom-uri") },
            loadDirectoryLabel = { "USB Music" }
        )

        assertEquals("content://downloads/tree/current", resolved.directoryUri)
        assertEquals("USB Music", resolved.directoryLabel)
    }
}
