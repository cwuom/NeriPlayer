package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class LocalPlaylistPrimaryWriteGuardTest : LocalPlaylistRepositoryTestSupport() {

    private val storage: LocalPlaylistStorage = mock(LocalPlaylistStorage::class.java)

    @Test
    fun `a corrupt primary that cannot be quarantined is never overwritten`() {
        val repository = repository()
        repository.corruptPrimaryNeedsQuarantine = true
        doAnswer { throw IOException("quarantine directory is read-only") }.`when`(storage).quarantinePrimary()

        val failure = assertThrows(IOException::class.java) {
            repository.persistToDisk(emptyList(), serialized = "[]")
        }

        assertEquals("Corrupt playlist storage could not be quarantined", failure.message)
        verify(storage, never()).commit(anyString(), anyBoolean(), anyBoolean())
        assertTrue(repository.corruptPrimaryNeedsQuarantine)
        assertTrue(repository.preserveBackupOnNextWrite)
    }

    @Test
    fun `a quarantined corrupt primary is replaced while the backup is preserved`() {
        val repository = repository()
        repository.corruptPrimaryNeedsQuarantine = true
        repository.preserveBackupOnNextWrite = true
        doReturn(File(tempFolder.root, "local_playlists.json.corrupt")).`when`(storage).quarantinePrimary()

        repository.persistToDisk(emptyList(), serialized = "[]")

        val order = inOrder(storage)
        order.verify(storage).quarantinePrimary()
        order.verify(storage).commit("[]", false, false)
        assertFalse(repository.corruptPrimaryNeedsQuarantine)
        assertFalse(repository.preserveBackupOnNextWrite)
    }

    @Test
    fun `a pending backup replacement applies to the next write only`() {
        val repository = repository()
        repository.replaceBackupOnNextWrite = true

        repository.persistToDisk(emptyList(), serialized = "[\"first\"]")
        repository.persistToDisk(emptyList(), serialized = "[\"second\"]")

        verify(storage).commit("[\"first\"]", true, true)
        verify(storage).commit("[\"second\"]", true, false)
        verify(storage, never()).quarantinePrimary()
        assertFalse(repository.replaceBackupOnNextWrite)
    }

    private fun repository() = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "primary_guard.json"),
        storage = storage
    ).also { clearInvocations(storage) }
}
