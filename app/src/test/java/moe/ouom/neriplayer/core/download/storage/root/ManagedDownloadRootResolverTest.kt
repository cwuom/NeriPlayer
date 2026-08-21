package moe.ouom.neriplayer.core.download.storage.root

import android.content.ContentResolver
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadRootResolverTest {

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
}
