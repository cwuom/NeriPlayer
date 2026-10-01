package moe.ouom.neriplayer.core.download.storage.root

import android.content.ContentResolver
import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadRootResolverTest {

    @Test
    fun `missing context directories use the JVM temporary directory`() {
        val context = mock(Context::class.java)

        val root = ManagedDownloadRootResolver.defaultRootDirectory(context)
        val temporaryRoot = File(
            requireNotNull(System.getProperty("java.io.tmpdir")),
            "neriplayer-jvm"
        )

        assertEquals(ROOT_DIR_NAME, root.name)
        assertEquals(File(temporaryRoot, ROOT_DIR_NAME).absolutePath, root.absolutePath)
        assertTrue(root.isAbsolute)
    }

    @Test
    fun `invalid persisted permission never falls back to private root`() {
        val context = mock(Context::class.java)
        val contentResolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(contentResolver)
        `when`(contentResolver.persistedUriPermissions).thenReturn(emptyList())
        val resolver = ManagedDownloadRootResolver(ConcurrentHashMap())
        val configuredUri =
            "content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download"
        var unavailableUri: String? = null

        assertThrows(ManagedDownloadRootUnavailableException::class.java) {
            resolver.resolveConfiguredRoot(context, configuredUri) { unavailableUri = it }
        }

        assertEquals(configuredUri, unavailableUri)
        assertNull(resolver.resolveRoot(context, configuredUri))
    }

    @Test
    fun `provider failure remains typed instead of looking like a missing directory`() {
        val providerError = IOException("provider unavailable")

        val failure = assertThrows(ManagedDownloadRootProviderException::class.java) {
            requireAccessibleManagedDownloadRoot(
                reference = "content://provider/tree/root",
                result = ManagedDownloadReferenceIo.AccessResult.ProviderFailure(providerError)
            )
        }

        assertSame(providerError, failure.cause)
    }

    @Test
    fun `null cursor failure remains a provider failure`() {
        val providerError = IllegalStateException("provider returned null document cursor")

        val failure = assertThrows(ManagedDownloadRootProviderException::class.java) {
            requireAccessibleManagedDownloadRoot(
                reference = "content://provider/tree/root",
                result = ManagedDownloadReferenceIo.AccessResult.ProviderFailure(providerError)
            )
        }

        assertSame(providerError, failure.cause)
    }

    @Test
    fun `missing and permission lost roots remain unavailable`() {
        assertTrue(
            requireAccessibleManagedDownloadRoot(
                reference = "content://provider/tree/root",
                result = ManagedDownloadReferenceIo.AccessResult.Accessible
            )
        )
        assertFalse(
            requireAccessibleManagedDownloadRoot(
                reference = "content://provider/tree/root",
                result = ManagedDownloadReferenceIo.AccessResult.Missing
            )
        )
        assertFalse(
            requireAccessibleManagedDownloadRoot(
                reference = "content://provider/tree/root",
                result = ManagedDownloadReferenceIo.AccessResult.PermissionLost
            )
        )
    }
}
