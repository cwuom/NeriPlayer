package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadStorageRootSafetyTest {

    @Test
    fun `persisting the selected directory again preserves its rebuilt snapshot`() {
        val previousUri = ManagedDownloadStorage.configuredDirectoryUri()
        val context = mock(Context::class.java).apply {
            `when`(applicationContext).thenReturn(this)
        }
        val selectedUri = "content://provider/tree/selected"
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot()

        try {
            ManagedDownloadStorage.updateConfiguredTreeUri(selectedUri)
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(context, "tree:selected", snapshot)

            ManagedDownloadStorage.updateConfiguredTreeUri(selectedUri)
            ManagedDownloadStorage.updateCustomDirectoryUri(selectedUri)

            assertSame(snapshot, ManagedDownloadStorage.snapshotCacheStore.peekSnapshot())

            ManagedDownloadStorage.updateCustomDirectoryUri("content://provider/tree/other")
            assertNull(ManagedDownloadStorage.snapshotCacheStore.peekSnapshot())
        } finally {
            ManagedDownloadStorage.updateConfiguredTreeUri(previousUri)
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
        }
    }

    @Test
    fun `unconfigured root does not probe and stays on private root`() {
        var probed = false

        val usesTree = ManagedDownloadStorage.resolveUsesDocumentTreeSafely(null) {
            probed = true
            true
        }

        assertFalse(usesTree)
        assertFalse(probed)
    }

    @Test
    fun `resolved tree root keeps the SAF sidecar path`() {
        assertTrue(
            ManagedDownloadStorage.resolveUsesDocumentTreeSafely(
                configuredDirectoryUri = "content://provider/tree/root",
                resolveRoot = { true }
            )
        )
    }

    @Test
    fun `provider failure keeps SAF mode instead of falling back to private root`() {
        val providerFailure = ManagedDownloadRootProviderException(
            reference = "content://provider/tree/root",
            cause = IllegalStateException("provider returned null document cursor")
        )

        assertTrue(
            ManagedDownloadStorage.resolveUsesDocumentTreeSafely(
                configuredDirectoryUri = "content://provider/tree/root",
                resolveRoot = { throw providerFailure }
            )
        )
    }

    @Test
    fun `ordinary root failure keeps SAF mode instead of falling back to private root`() {
        assertTrue(
            ManagedDownloadStorage.resolveUsesDocumentTreeSafely(
                configuredDirectoryUri = "content://provider/tree/root",
                resolveRoot = { throw IllegalStateException("root unavailable") }
            )
        )
    }

    @Test
    fun `permission loss keeps SAF mode so writes do not switch roots`() {
        assertTrue(
            ManagedDownloadStorage.resolveUsesDocumentTreeSafely(
                configuredDirectoryUri = "content://provider/tree/root",
                resolveRoot = {
                    throw ManagedDownloadRootUnavailableException(
                        "content://provider/tree/root"
                    )
                }
            )
        )
    }

    @Test
    fun `cancellation is propagated to the caller`() {
        val cancellation = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            ManagedDownloadStorage.resolveUsesDocumentTreeSafely(
                configuredDirectoryUri = "content://provider/tree/root",
                resolveRoot = { throw cancellation }
            )
        }

        assertSame(cancellation, thrown)
    }
}
