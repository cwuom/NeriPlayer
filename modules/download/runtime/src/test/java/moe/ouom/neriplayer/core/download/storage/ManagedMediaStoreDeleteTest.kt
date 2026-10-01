package moe.ouom.neriplayer.core.download.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.AccessResult
import moe.ouom.neriplayer.core.download.storage.reference.deleteAndConfirmDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedMediaStoreDeleteTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `database acknowledgement cannot replace physical deletion`() {
        val audio = folder.newFile("audio.mp3")
        val confirmed = deleteAndConfirmDocuments(listOf(audio), delete = {}) {
            if (it.exists()) AccessResult.Accessible else AccessResult.Missing
        }
        assertTrue(confirmed.isEmpty())
        assertTrue(audio.isFile)
    }

    @Test
    fun `partial failure reports only files already removed`() {
        val first = folder.newFile("first.mp3")
        val second = folder.newFile("second.mp3")
        val confirmed = deleteAndConfirmDocuments(listOf(first, second), delete = {
            check(first.delete())
            throw IOException("second delete failed")
        }) {
            if (it.exists()) AccessResult.Accessible else AccessResult.Missing
        }
        assertEquals(setOf(first), confirmed)
        assertTrue(second.isFile)
    }

    @Test
    fun `permission and provider failures remain unconfirmed`() {
        val results = listOf(AccessResult.PermissionLost, AccessResult.ProviderFailure(IOException()), AccessResult.Missing)
        assertEquals(setOf(2), deleteAndConfirmDocuments(results.indices.toList(), delete = {}) { results[it] })
        assertTrue(deleteAndConfirmDocuments(listOf(0), delete = {}) { throw IOException() }.isEmpty())
    }

    @Test
    fun `cancelling deletion stops before probing`() {
        var probed = false
        assertThrows(CancellationException::class.java) {
            deleteAndConfirmDocuments(listOf(0), delete = { throw CancellationException("cancel") }) {
                probed = true
                AccessResult.Missing
            }
        }
        assertEquals(false, probed)
    }

    @Test
    fun `cancelling the probe propagates`() {
        assertThrows(CancellationException::class.java) {
            deleteAndConfirmDocuments(listOf(0), delete = {}) { throw CancellationException("cancel") }
        }
    }
}
