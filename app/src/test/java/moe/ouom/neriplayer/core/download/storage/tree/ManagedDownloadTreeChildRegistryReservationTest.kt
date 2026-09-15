package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadTreeChildRegistryReservationTest {
    @Test
    fun `pending logical name remains reserved after tree cache refresh`() {
        val context = mock(Context::class.java)
        val parent = mock(DocumentFile::class.java)
        val parentUri = mock(Uri::class.java)
        `when`(parentUri.toString()).thenReturn("content://provider/tree/root")
        `when`(parent.uri).thenReturn(parentUri)
        val pendingUri = mock(Uri::class.java)
        val desiredName = "Artist - Song.mp3"
        val pendingName = ManagedDownloadPendingAudioWriteNames()
            .buildPendingAudioWriteName(desiredName)
        val registry = ManagedDownloadTreeChildRegistry(
            writeCacheValidateIntervalMs = 60_000L,
            treeCacheValidateIntervalMs = 60_000L,
            treeWriteCacheValidateIntervalMs = 60_000L,
            onTreeQueryFailed = {}
        )
        registry.rememberTreeChildren(
            parent = parent,
            children = listOf(
                QueriedTreeChild(
                    name = pendingName,
                    documentUri = pendingUri,
                    sizeBytes = 42L,
                    lastModifiedMs = 1L,
                    isDirectory = false
                )
            ),
            refreshedAtMs = System.currentTimeMillis(),
            isComplete = true
        )

        assertEquals(
            "Artist - Song (1).mp3",
            registry.reserveUniqueTreeChildName(context, parent, desiredName)
        )
        assertEquals(
            "Artist - Song (2).mp3",
            registry.reserveUniqueTreeChildName(context, parent, desiredName)
        )
    }
}
