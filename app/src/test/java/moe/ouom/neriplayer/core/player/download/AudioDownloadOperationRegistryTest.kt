package moe.ouom.neriplayer.core.player.download

import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AudioDownloadOperationRegistryTest {
    @Test
    fun `operation lifecycle keeps replacement active until its own end`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())

        registry.beginSongDownloadOperation("song", "old", attemptId = 1L)
        registry.beginSongDownloadOperation("song", "new", attemptId = 2L)

        assertTrue(registry.isSongDownloadActive("song"))
        assertTrue(registry.isOperationDownloadActive("old"))
        assertTrue(registry.isOperationDownloadActive("new"))
        assertEquals(setOf("old", "new"), registry.activeOperationIdsForSong("song"))

        registry.endSongDownloadOperation("song", "old")

        assertFalse(registry.isOperationDownloadActive("old"))
        assertTrue(registry.isOperationDownloadActive("new"))
        assertEquals("song", registry.songKeyForOperation("new"))
        assertTrue(registry.allowsReference("song", "new", attemptId = 2L))

        registry.endSongDownloadOperation("song", "new")

        assertFalse(registry.isSongDownloadActive("song"))
        assertEquals(emptySet<String>(), registry.activeOperationIdsForSong("song"))
    }

    @Test
    fun `operation pause only snapshots calls belonging to that operation`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())
        val oldCall = mock(Call::class.java)
        val newCall = mock(Call::class.java)
        registry.beginSongDownloadOperation("song", "old", attemptId = 1L)
        registry.beginSongDownloadOperation("song", "new", attemptId = 2L)
        registry.registerActiveCall("song", oldCall, "old")
        registry.registerActiveCall("song", newCall, "new")

        registry.markExecutionHostPaused("old")
        registry.revokeReference("song", setOf("old"))

        assertTrue(registry.isExecutionHostPaused("old"))
        assertEquals(listOf(oldCall), registry.snapshotActiveCalls(listOf("old")))
        assertEquals(listOf(newCall), registry.snapshotActiveCalls(listOf("new")))
        assertFalse(registry.allowsReference("song", "old"))
        assertTrue(registry.allowsReference("song", "new", attemptId = 2L))
    }

    @Test
    fun `network pause state is normalized by the manager boundary`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())

        registry.addNetworkPolicyPaused(setOf("song-a", "song-b"))
        assertTrue(registry.isNetworkPolicyPaused("song-a"))
        assertTrue(registry.isNetworkPolicyPaused("song-b"))

        registry.removeNetworkPolicyPaused(setOf("song-a"))
        assertFalse(registry.isNetworkPolicyPaused("song-a"))
        assertTrue(registry.isNetworkPolicyPaused("song-b"))

        registry.clearNetworkPolicyPaused()
        assertFalse(registry.isNetworkPolicyPaused("song-b"))
    }

    @Test
    fun `core committed marker survives transfer completion until finalization`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())

        registry.beginSongDownloadOperation("song", "operation", attemptId = 1L)
        assertTrue(
            registry.markCoreCommittedIfOwned(
                songKey = "song",
                operationId = "operation",
                attemptId = 1L
            )
        )
        registry.endSongDownloadOperation("song", "operation")

        assertTrue(registry.isCoreCommitted("operation"))

        registry.clearCoreCommitted("operation")

        assertFalse(registry.isCoreCommitted("operation"))
    }

    @Test
    fun `revoked operation cannot reestablish core protection`() {
        val registry = AudioDownloadOperationRegistry(AudioDownloadReferenceOwnership())

        registry.beginSongDownloadOperation("song", "operation", attemptId = 1L)
        registry.revokeReference("song", setOf("operation"))

        assertFalse(
            registry.markCoreCommittedIfOwned(
                songKey = "song",
                operationId = "operation",
                attemptId = 1L
            )
        )
        assertFalse(registry.isCoreCommitted("operation"))
    }
}
