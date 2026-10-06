package moe.ouom.neriplayer.core.download.storage.reference

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.AccessResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ManagedMediaStoreDeleteTest {

    @Test
    fun `media store deletion is skipped before Android R`() {
        val resolver = mock(ContentResolver::class.java)
        val context = mock(Context::class.java).also { `when`(it.contentResolver).thenReturn(resolver) }
        val document = mock(Uri::class.java)

        assertEquals(emptySet<Uri>(), ManagedMediaStoreDelete.deleteConfirmed(context, listOf(document)))
        verifyNoInteractions(resolver, document)
    }

    @Test
    fun `only targets observed missing after the delete are confirmed`() {
        val inspected = mutableListOf<String>()

        val confirmed = deleteAndConfirmDocuments(
            targets = listOf("gone", "kept", "broken", "denied"),
            delete = { throw IllegalStateException("partial batch") },
            inspect = { target ->
                inspected += target
                when (target) {
                    "gone" -> AccessResult.Missing
                    "kept" -> AccessResult.Accessible
                    "denied" -> AccessResult.PermissionLost
                    else -> throw IllegalStateException("provider crashed")
                }
            }
        )

        assertEquals(setOf("gone"), confirmed)
        assertEquals(listOf("gone", "kept", "broken", "denied"), inspected)
    }

    @Test(expected = CancellationException::class)
    fun `cancelled deletes are not confirmed`() {
        deleteAndConfirmDocuments(
            targets = listOf("gone"),
            delete = { throw CancellationException("stopped") },
            inspect = { AccessResult.Missing }
        )
    }
}
