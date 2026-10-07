package moe.ouom.neriplayer.core.download.storage.root

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class ManagedDownloadRootPermissionFallbackTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val musicTree =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Test
    fun `default root prefers the absolute external music directory`() {
        val external = tempFolder.newFolder("external-music")
        val context = directoryContext(externalFilesDir = external, filesDir = tempFolder.newFolder("files"))

        assertEquals(
            File(external, ROOT_DIR_NAME),
            ManagedDownloadRootResolver.defaultRootDirectory(context)
        )
    }

    @Test
    fun `default root skips a relative external directory for absolute app files`() {
        val files = tempFolder.newFolder("files")
        val context = directoryContext(externalFilesDir = File("relative-music"), filesDir = files)

        assertEquals(
            File(files, ROOT_DIR_NAME),
            ManagedDownloadRootResolver.defaultRootDirectory(context)
        )
    }

    @Test
    fun `default root uses the JVM temp directory when context directories fail or are relative`() {
        val jvmRoot = File(
            File(requireNotNull(System.getProperty("java.io.tmpdir")), "neriplayer-jvm"),
            ROOT_DIR_NAME
        )
        val failingContext = mock(Context::class.java)
        doThrow(SecurityException("storage unavailable")).`when`(failingContext).getExternalFilesDir(any())
        doThrow(IllegalStateException("user locked")).`when`(failingContext).filesDir
        val relativeContext = directoryContext(externalFilesDir = null, filesDir = File("relative-files"))

        assertEquals(jvmRoot, ManagedDownloadRootResolver.defaultRootDirectory(failingContext))
        assertEquals(jvmRoot, ManagedDownloadRootResolver.defaultRootDirectory(relativeContext))
    }

    @Test
    fun `JVM fallback refuses missing blank and relative temp directories`() {
        val context = directoryContext(externalFilesDir = null, filesDir = null)

        listOf(null, " ", "relative/tmp").forEach { temporaryDirectory ->
            val error = assertThrows(IllegalStateException::class.java) {
                withTemporaryDirectoryProperty(temporaryDirectory) {
                    ManagedDownloadRootResolver.defaultRootDirectory(context)
                }
            }
            assertEquals("java.io.tmpdir must be an absolute path", error.message)
        }
    }

    @Test
    fun `persisted write permission needs read and write grants for the same tree`() {
        val resolver = ManagedDownloadRootResolver(ConcurrentHashMap())
        val otherTree = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"

        assertFalse(resolver.hasPersistedWritePermission(permissionContext(grant(musicTree, write = false)), musicTree))
        assertFalse(resolver.hasPersistedWritePermission(permissionContext(grant(musicTree, read = false)), musicTree))
        assertFalse(resolver.hasPersistedWritePermission(permissionContext(grant(otherTree)), musicTree))
        assertFalse(resolver.hasPersistedWritePermission(permissionContext(), musicTree))
        assertTrue(
            resolver.hasPersistedWritePermission(
                permissionContext(grant(otherTree), grant("$musicTree/")),
                "$musicTree?mode=write"
            )
        )
        assertTrue(
            resolver.hasPersistedWritePermission(
                permissionContext(grant("$musicTree/document/primary%3AMusic")),
                musicTree
            )
        )
    }

    @Test
    fun `unreadable persisted permissions are treated as missing`() {
        val contentResolver = mock(ContentResolver::class.java)
        doThrow(SecurityException("binder died")).`when`(contentResolver).persistedUriPermissions
        val context = mock(Context::class.java)
        doReturn(contentResolver).`when`(context).contentResolver

        assertFalse(ManagedDownloadRootResolver(ConcurrentHashMap()).hasPersistedWritePermission(context, musicTree))
    }

    @Test
    fun `blank directory configuration never consults persisted permissions`() {
        val context = mock(Context::class.java)

        assertFalse(ManagedDownloadRootResolver(ConcurrentHashMap()).hasPersistedWritePermission(context, " "))
        verifyNoInteractions(context)
    }

    @Test
    fun `probe without a configured tree is served by the private root`() {
        val context = mock(Context::class.java)

        assertSame(
            ManagedDownloadRootProbeResult.Accessible,
            ManagedDownloadRootResolver(ConcurrentHashMap()).probeTreeRoot(context, "  ")
        )
        verifyNoInteractions(context)
    }

    @Test
    fun `probe of a tree that cannot be opened reports it unavailable`() {
        val resolver = ManagedDownloadRootResolver(ConcurrentHashMap())

        assertSame(
            ManagedDownloadRootProbeResult.Unavailable,
            resolver.probeTreeRoot(permissionContext(grant(musicTree)), musicTree)
        )
    }

    @Test
    fun `probe wraps unexpected resolution failures as typed provider failures`() {
        val lockFailure = IllegalStateException("lock table closed")
        val locks = object : ConcurrentHashMap<String, Any>() {
            override fun computeIfAbsent(key: String, mappingFunction: Function<in String, out Any>): Any {
                throw lockFailure
            }
        }

        val result = ManagedDownloadRootResolver(locks)
            .probeTreeRoot(permissionContext(grant(musicTree)), musicTree)

        assertTrue(result is ManagedDownloadRootProbeResult.ProviderFailure)
        val error = (result as ManagedDownloadRootProbeResult.ProviderFailure).error
        assertSame(lockFailure, error.cause)
        assertEquals("DocumentsProvider 暂时无法检查下载目录: configured-root", error.message)
    }

    private fun directoryContext(externalFilesDir: File?, filesDir: File?): Context {
        val context = mock(Context::class.java)
        doReturn(externalFilesDir).`when`(context).getExternalFilesDir(any())
        doReturn(filesDir).`when`(context).filesDir
        return context
    }

    private fun permissionContext(vararg permissions: UriPermission): Context {
        val contentResolver = mock(ContentResolver::class.java)
        doReturn(permissions.toList()).`when`(contentResolver).persistedUriPermissions
        val context = mock(Context::class.java)
        doReturn(contentResolver).`when`(context).contentResolver
        return context
    }

    private fun grant(uri: String, read: Boolean = true, write: Boolean = true): UriPermission {
        val permissionUri = mock(Uri::class.java)
        doReturn(uri).`when`(permissionUri).toString()
        val permission = mock(UriPermission::class.java)
        doReturn(permissionUri).`when`(permission).uri
        doReturn(read).`when`(permission).isReadPermission
        doReturn(write).`when`(permission).isWritePermission
        return permission
    }

    private fun <T> withTemporaryDirectoryProperty(value: String?, block: () -> T): T {
        val original = System.getProperty("java.io.tmpdir")
        try {
            if (value == null) {
                System.clearProperty("java.io.tmpdir")
            } else {
                System.setProperty("java.io.tmpdir", value)
            }
            return block()
        } finally {
            System.setProperty("java.io.tmpdir", original)
        }
    }
}
