package moe.ouom.neriplayer.core.download.ownership

import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AudioDownloadRegistryLifecycleTest {
    @Test
    fun `unscoped and normalized calls are removed from both call indexes`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())
        val unscoped = mock(Call::class.java)
        val blank = mock(Call::class.java)
        val scoped = mock(Call::class.java)
        registry.registerActiveCall("song-a", unscoped, null)
        registry.registerActiveCall("song-a", blank, " ")
        registry.registerActiveCall("song-b", scoped, " operation ")
        assertEquals(setOf(unscoped, blank, scoped), registry.snapshotActiveCalls().toSet())
        assertEquals(setOf(unscoped, blank), registry.snapshotActiveCalls("song-a").toSet())
        assertTrue(registry.snapshotActiveCalls("missing").isEmpty())
        assertEquals(listOf(scoped), registry.snapshotActiveCalls(listOf("operation", " ")))

        registry.unregisterActiveCall("song-a", unscoped, null)
        registry.unregisterActiveCall("song-a", blank, " ")
        registry.unregisterActiveCall("song-b", scoped, " operation ")
        assertTrue(registry.snapshotActiveCalls().isEmpty())
        assertTrue(registry.snapshotActiveCalls(listOf("operation")).isEmpty())
    }

    @Test
    fun `inactive pause cleanup keeps active operations fenced`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())
        registry.beginSongDownloadOperation("song", "active", 1L)
        registry.markExecutionHostPaused(listOf("active", "inactive"))
        registry.clearExecutionHostPausedIfInactive(listOf("active", "inactive"))
        assertTrue(registry.isExecutionHostPaused("active"))
        assertFalse(registry.isExecutionHostPaused("inactive"))
        registry.clearInactiveExecutionHostPausesExcept(emptySet())
        assertTrue(registry.isExecutionHostPaused("active"))
        registry.clearInactiveExecutionHostPausesExcept(setOf("active"))
        assertTrue(registry.isExecutionHostPaused("active"))
        registry.endSongDownloadOperation("song", "active")
        assertFalse(registry.isExecutionHostPaused("active"))
    }

    @Test
    fun `blank references stay unscoped and revocation preserves a replacement`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin(" ", "ignored", 1L)
        ownership.begin("song", " ", 1L)
        assertFalse(ownership.allows("song", "ignored"))
        assertTrue(ownership.allows("song", null))
        assertTrue(ownership.allows("song", " "))
        ownership.begin("song", "old", 1L)
        ownership.begin("song", "replacement", 2L)
        ownership.revoke(" ", listOf("replacement"))
        ownership.revoke("song", listOf(" "))
        ownership.revoke("song", listOf(" old "))
        assertTrue(ownership.allows("song", " replacement ", 2L))
        assertFalse(ownership.allows("song", "replacement", 1L))
        ownership.revoke("song", listOf(" replacement "))
        assertFalse(ownership.allows("song", "replacement"))
    }
}
