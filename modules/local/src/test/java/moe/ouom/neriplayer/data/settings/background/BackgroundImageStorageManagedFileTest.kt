package moe.ouom.neriplayer.data.settings.background

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.ByteArrayInputStream
import java.io.File

class BackgroundImageStorageManagedFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private lateinit var context: Context
    private lateinit var managed: File

    @Before
    fun setUp() {
        context = mock(Context::class.java).also { context ->
            doReturn(resolver).`when`(context).contentResolver
            doReturn(temporaryFolder.root).`when`(context).filesDir
        }
        managed = File(temporaryFolder.root, "custom_background").apply { mkdirs() }
    }

    @Test
    fun `imported images replace every older managed background`() = runTest {
        File(managed, "background_1.jpg").writeText("stale")
        val previous = File(managed, "background_2.webp").apply { writeText("previous") }
        File(managed, "notes.txt").writeText("unrelated")
        val source = mock(Uri::class.java)
        doReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3))).`when`(resolver).openInputStream(source)
        doReturn("image/png").`when`(resolver).getType(source)

        val imported = withManagedUris {
            BackgroundImageStorage.importFromUri(context, source, previousUriString = "file://${previous.absolutePath}")
        }

        val target = File(imported.toString().removePrefix("file://"))
        assertEquals(managed.absolutePath, target.parentFile?.absolutePath)
        assertTrue(target.name, target.name.matches(Regex("""background_\d+\.png""")))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertEquals(listOf("notes.txt", target.name).sorted(), managed.list()!!.sorted())
    }

    @Test
    fun `sources without a readable stream import nothing`() = runTest {
        val source = mock(Uri::class.java)

        val imported = withManagedUris { BackgroundImageStorage.importFromUri(context, source) }

        assertNull(imported)
        assertEquals(emptyList<String>(), managed.list()!!.toList())
    }

    @Test
    fun `only existing managed files other than the kept one are deleted`() = runTest {
        val kept = File(managed, "background_3.png").apply { writeText("kept") }
        val replaced = File(managed, "background_4.png").apply { writeText("replaced") }
        val schemeless = File(managed, "background_5.png").apply { writeText("schemeless") }
        val outside = temporaryFolder.newFile("background_6.png")

        withManagedUris {
            BackgroundImageStorage.deleteManagedBackground(context, "file://${kept.absolutePath}", keepPath = kept.absolutePath)
            BackgroundImageStorage.deleteManagedBackground(context, "file://${replaced.absolutePath}", keepPath = kept.absolutePath)
            BackgroundImageStorage.deleteManagedBackground(context, schemeless.absolutePath)
            BackgroundImageStorage.deleteManagedBackground(context, "file://${outside.absolutePath}")
            BackgroundImageStorage.deleteManagedBackground(context, "content://media/external/images/media/9")
            BackgroundImageStorage.deleteManagedBackground(context, "file://${File(managed, "missing.png").absolutePath}")
            BackgroundImageStorage.deleteManagedBackground(context, "  ")
        }

        assertTrue(kept.exists())
        assertFalse(replaced.exists())
        assertFalse(schemeless.exists())
        assertTrue(outside.exists())
    }

    // Static mocks are thread-local; the storage's own IO hop then stays on this IO thread
    private suspend fun <T> withManagedUris(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { parsed(it.getArgument(0)) }
            uris.`when`<Uri> { Uri.fromFile(any(File::class.java)) }
                .thenAnswer { parsed("file://" + it.getArgument<File>(0).absolutePath) }
            block()
        }
    }

    private fun parsed(value: String): Uri = mock(Uri::class.java).also { uri ->
        val scheme = value.substringBefore("://", missingDelimiterValue = "").ifEmpty { null }
        doReturn(scheme).`when`(uri).scheme
        doReturn(if (scheme == "file") value.removePrefix("file://") else null).`when`(uri).path
        doReturn(value).`when`(uri).toString()
    }
}
