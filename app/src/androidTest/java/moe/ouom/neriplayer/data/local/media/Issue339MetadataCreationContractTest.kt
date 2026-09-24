package moe.ouom.neriplayer.data.local.media

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Issue339MetadataCreationContractTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val parent = DocumentsContract.buildDocumentUri(
        Issue339LyricsTestDocumentProvider.AUTHORITY, Issue339LyricsTestDocumentProvider.MUSIC_ID
    )

    @Before fun reset() {
        resolver.call(parent, Issue339LyricsTestDocumentProvider.RESET_LYRICS, null, null)
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @After fun cleanup() = reset()

    @Test fun createdMetadataIsImmediatelyReadableRegularAndListed() {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/json", Issue339LyricsTestDocumentProvider.METADATA_NAME))
        resolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            assertTrue(OsConstants.S_ISREG(stat.st_mode))
            assertEquals(0L, stat.st_size)
            assertArrayEquals(byteArrayOf(), ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { it.readBytes() })
        }
        val children = DocumentsContract.buildChildDocumentsUri(Issue339LyricsTestDocumentProvider.AUTHORITY, Issue339LyricsTestDocumentProvider.MUSIC_ID)
        val ids = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(ids.contains(Issue339LyricsTestDocumentProvider.METADATA_ID))
    }

    @Test fun repeatedMetadataCreationDoesNotTruncateExistingContents() {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/json", Issue339LyricsTestDocumentProvider.METADATA_NAME))
        val sentinel = "existing metadata".toByteArray()
        resolver.openOutputStream(uri, "wt")!!.use { it.write(sentinel) }
        val repeated = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/json", Issue339LyricsTestDocumentProvider.METADATA_NAME))
        assertEquals(uri, repeated)
        assertArrayEquals(sentinel, resolver.openInputStream(uri)!!.use { it.readBytes() })
        resolver.openFileDescriptor(uri, "rw")!!.close()
        assertArrayEquals(byteArrayOf(), resolver.openInputStream(uri)!!.use { it.readBytes() })
    }
}
