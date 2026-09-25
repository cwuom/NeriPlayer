package moe.ouom.neriplayer.core.download

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadMigrationProviderIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )

    @Before
    @After
    fun reset() {
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
    }

    @Test
    fun previousGenerationReferenceCannotReadNewDocumentAfterReset() {
        val (oldReference, newReference) = replaceFixtureGeneration()
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openInputStream(oldReference)?.use { it.readBytes() }
        }
        assertNewDocumentUnchanged(newReference)
    }

    @Test
    fun previousGenerationReferenceCannotOverwriteNewDocumentAfterReset() {
        val (oldReference, newReference) = replaceFixtureGeneration()
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openOutputStream(oldReference, "wt")?.use { it.write(99) }
        }
        assertNewDocumentUnchanged(newReference)
    }

    @Test
    fun previousGenerationReferenceCannotDeleteNewDocumentAfterReset() {
        val (oldReference, newReference) = replaceFixtureGeneration()
        assertEquals(0, context.contentResolver.delete(oldReference, null, null))
        DocumentsContract.deleteDocument(context.contentResolver, oldReference)
        assertNewDocumentUnchanged(newReference)
    }

    private fun replaceFixtureGeneration(): Pair<Uri, Uri> {
        val oldReference = createDocument(byteArrayOf(1, 2, 3))
        reset()
        val newReference = createDocument(NEW_CONTENT)
        return oldReference to newReference
    }

    private fun createDocument(content: ByteArray): Uri {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
        val document = requireNotNull(root.createFile("application/octet-stream", "reset-identity.bin"))
        requireNotNull(context.contentResolver.openOutputStream(document.uri, "w")).use { it.write(content) }
        return document.uri
    }

    private fun assertNewDocumentUnchanged(reference: Uri) {
        val actual = requireNotNull(context.contentResolver.openInputStream(reference)).use { it.readBytes() }
        assertArrayEquals(NEW_CONTENT, actual)
    }

    private companion object {
        val NEW_CONTENT = byteArrayOf(4, 5, 6, 7)
    }
}
