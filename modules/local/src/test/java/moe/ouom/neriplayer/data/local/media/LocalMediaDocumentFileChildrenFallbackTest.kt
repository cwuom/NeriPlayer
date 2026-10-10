package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChildrenQueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaDocumentFileChildrenFallbackTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val parentUri = uri("$TREE/document/album")
    private val songUri = uri("$TREE/document/song")
    private val lyricsUri = uri("$TREE/document/lyrics")
    private val song = DocumentChild("song", "Song.flac", isDirectory = false, uri = "$TREE/document/song")
    private val lyrics = DocumentChild("lyrics", "Lyrics", isDirectory = true, uri = "$TREE/document/lyrics")

    @Test
    fun `tree listings keep only named children with a document id`() {
        val foreignUri = uri("content://other.provider/foreign")
        val blankIdUri = uri("$TREE/document/blank-id")
        val parent = parentWith(
            documentFile("Song.flac", songUri),
            documentFile(null, uri("$TREE/document/unnamed")),
            documentFile("  ", uri("$TREE/document/blank-name")),
            documentFile("Foreign.flac", foreignUri),
            documentFile("Blank.flac", blankIdUri),
            documentFile("Lyrics", lyricsUri, isDirectory = true)
        )

        withTreeParent(parent) { contract, _ ->
            contract.`when`<String> { DocumentsContract.getDocumentId(foreignUri) }
                .thenThrow(IllegalArgumentException("Invalid URI"))
            contract.`when`<String> { DocumentsContract.getDocumentId(blankIdUri) }.thenReturn(" ")

            assertEquals(
                listOf(song, lyrics),
                LocalMediaSupport.listDocumentChildrenWithDocumentFile(context, treeUri, PARENT_ID, maxChildren = null)
            )
        }
    }

    @Test
    fun `tree listings stop at the child limit before filtering`() {
        val parent = parentWith(documentFile("Song.flac", songUri), documentFile("Lyrics", lyricsUri, isDirectory = true))

        withTreeParent(parent) { _, _ ->
            assertEquals(
                listOf(song),
                LocalMediaSupport.listDocumentChildrenWithDocumentFile(context, treeUri, PARENT_ID, maxChildren = 1)
            )
        }
    }

    @Test
    fun `single document parents without a document file list nothing`() {
        val documentBase = uri("content://$AUTHORITY/document/primary%3AMusic")
        val documentParent = uri("content://$AUTHORITY/document/album")

        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<Uri> { DocumentsContract.buildDocumentUri(AUTHORITY, PARENT_ID) }.thenReturn(documentParent)
            mockStatic(DocumentFile::class.java).use { documentFile ->
                assertTrue(LocalMediaSupport.listDocumentChildrenWithDocumentFile(context, documentBase, PARENT_ID).isEmpty())
                documentFile.verify { DocumentFile.fromSingleUri(context, documentParent) }
                documentFile.verifyNoMoreInteractions()
            }
        }
    }

    @Test
    fun `parents whose document uri cannot be built list nothing`() {
        val authorityless = mock(Uri::class.java)
        doReturn("content").`when`(authorityless).scheme
        doReturn("content:///document/root").`when`(authorityless).toString()

        mockStatic(DocumentFile::class.java).use { documentFile ->
            assertTrue(LocalMediaSupport.listDocumentChildrenWithDocumentFile(context, authorityless, "root").isEmpty())
            documentFile.verifyNoInteractions()
        }
    }

    @Test
    fun `provider listings are used without the document file fallback`() {
        val cursor = mock(Cursor::class.java)
        doReturn(0).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        doReturn(1).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        doReturn(2).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        doReturn(true, false).`when`(cursor).moveToNext()
        doReturn(song.documentId).`when`(cursor).getString(0)
        doReturn(song.displayName).`when`(cursor).getString(1)
        doReturn("audio/flac").`when`(cursor).getString(2)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())

        withTreeParent(parentWith()) { contract, documentFile ->
            contract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(treeUri, song.documentId) }.thenReturn(songUri)

            assertEquals(
                DocumentChildrenQueryResult(listOf(song), isComplete = true),
                LocalMediaSupport.queryDocumentChildrenUncached(context, treeUri, PARENT_ID)
            )
            documentFile.verifyNoInteractions()
        }
    }

    @Test
    fun `listings the provider cannot answer fall back to an incomplete document file listing`() {
        val parent = parentWith(documentFile("Song.flac", songUri))

        withTreeParent(parent) { _, _ ->
            assertEquals(
                DocumentChildrenQueryResult(listOf(song), isComplete = false),
                LocalMediaSupport.queryDocumentChildrenUncached(context, treeUri, PARENT_ID)
            )
        }
    }

    @Test
    fun `blank parent ids have no listing`() {
        assertNull(LocalMediaSupport.queryDocumentChildrenUncached(context, treeUri, null))
        assertNull(LocalMediaSupport.queryDocumentChildrenUncached(context, treeUri, " "))

        verifyNoInteractions(resolver)
    }

    private fun withTreeParent(
        parent: DocumentFile,
        block: (MockedStatic<DocumentsContract>, MockedStatic<DocumentFile>) -> Unit
    ) {
        val childrenUri = uri("$TREE/document/album/children")
        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(parentUri) }.thenReturn(true)
            contract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(treeUri, PARENT_ID) }.thenReturn(parentUri)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, PARENT_ID)
            }.thenReturn(childrenUri)
            contract.`when`<String> { DocumentsContract.getDocumentId(songUri) }.thenReturn(song.documentId)
            contract.`when`<String> { DocumentsContract.getDocumentId(lyricsUri) }.thenReturn(lyrics.documentId)
            mockStatic(DocumentFile::class.java).use { documentFile ->
                documentFile.`when`<DocumentFile> { DocumentFile.fromTreeUri(context, parentUri) }.thenReturn(parent)
                block(contract, documentFile)
            }
        }
    }

    private fun parentWith(vararg children: DocumentFile): DocumentFile =
        mock(DocumentFile::class.java).also { parent ->
            doReturn(arrayOf(*children)).`when`(parent).listFiles()
        }

    private fun documentFile(name: String?, uri: Uri, isDirectory: Boolean = false): DocumentFile =
        mock(DocumentFile::class.java).also { document ->
            doReturn(name).`when`(document).name
            doReturn(uri).`when`(document).uri
            doReturn(isDirectory).`when`(document).isDirectory
        }

    private fun uri(value: String): Uri {
        val uri = mock(Uri::class.java)
        doReturn("content").`when`(uri).scheme
        doReturn(AUTHORITY).`when`(uri).authority
        doReturn(value).`when`(uri).toString()
        return uri
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val TREE = "content://$AUTHORITY/tree/primary%3AMusic"
        const val PARENT_ID = "primary:Music/Album"
    }
}
