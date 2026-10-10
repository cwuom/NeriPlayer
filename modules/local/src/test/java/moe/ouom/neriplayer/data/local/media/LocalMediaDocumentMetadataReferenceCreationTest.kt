package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaCompanionTransaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaDocumentMetadataReferenceCreationTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri("$TREE/document/song")
    private val parentUri = uri("$TREE/document/music")
    private val parentChildrenUri = uri("$TREE/document/music/children")
    private val metadataUri = uri("$TREE/document/song-meta")
    private val otherUri = uri("$TREE/document/other")

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `existing metadata sidecars next to the document are reused`() {
        stubParentListing(SOURCE_ROW, METADATA_ROW)

        val reference = withDocumentTree { contract ->
            create().also {
                contract.verify({ DocumentsContract.createDocument(any(), any(), any(), any()) }, never())
            }
        }

        assertEquals(metadataUri.toString(), reference)
    }

    @Test
    fun `missing metadata sidecars are created and recorded in the companion transaction`() {
        stubParentListing(SOURCE_ROW)
        stubDisplayName(metadataUri, METADATA_NAME)
        val transaction = mock(LocalMediaCompanionTransaction::class.java)

        val reference = withDocumentTree { contract ->
            contract.`when`<Uri> {
                DocumentsContract.createDocument(same(resolver), same(parentUri), anyString(), eq(METADATA_NAME))
            }.thenReturn(metadataUri)
            contract.`when`<String> { DocumentsContract.getDocumentId(metadataUri) }.thenReturn(METADATA_ID)
            create(transaction)
        }

        assertEquals(metadataUri.toString(), reference)
        verify(transaction).created(metadataUri.toString())
    }

    @Test
    fun `metadata sidecars the provider refuses to create give no reference`() {
        stubParentListing(SOURCE_ROW)
        val transaction = mock(LocalMediaCompanionTransaction::class.java)

        assertNull(withDocumentTree { create(transaction) })
        verifyNoInteractions(transaction)
    }

    @Test
    fun `documents missing from their parent listing get no metadata reference`() {
        stubParentListing(OTHER_ROW)

        val reference = withDocumentTree { contract ->
            create().also {
                contract.verify({ DocumentsContract.createDocument(any(), any(), any(), any()) }, never())
            }
        }

        assertNull(reference)
    }

    @Test
    fun `parents that cannot be listed get no metadata reference`() {
        assertNull(withDocumentTree { create() })
    }

    private fun create(transaction: LocalMediaCompanionTransaction? = null): String? =
        LocalMediaSupport.createLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = null,
            displayName = "Song.flac",
            companionTransaction = transaction
        )

    private fun <T> withDocumentTree(block: (MockedStatic<DocumentsContract>) -> T): T {
        return mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(sourceUri) }.thenReturn(TREE_ID)
            contract.`when`<String> { DocumentsContract.getDocumentId(sourceUri) }.thenReturn(SOURCE_ID)
            contract.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID) }.thenReturn(treeUri)
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, TREE_ID)
            }.thenReturn(parentChildrenUri)
            mapOf(
                TREE_ID to parentUri,
                SOURCE_ID to sourceUri,
                METADATA_ID to metadataUri,
                OTHER_ID to otherUri
            ).forEach { (documentId, documentUri) ->
                contract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }.thenReturn(documentUri)
            }
            mockStatic(DocumentFile::class.java).use { block(contract) }
        }
    }

    private fun stubParentListing(vararg rows: Triple<String, String, String>) {
        doReturn(listingCursor(rows.toList()), listingCursor(rows.toList()), listingCursor(rows.toList()))
            .`when`(resolver).query(same(parentChildrenUri), any(), any(), any(), any())
    }

    private fun stubDisplayName(documentUri: Uri, name: String) {
        val cursor = mock(Cursor::class.java)
        doReturn(0).`when`(cursor).getColumnIndex(OpenableColumns.DISPLAY_NAME)
        doReturn(true).`when`(cursor).moveToFirst()
        doReturn(name).`when`(cursor).getString(0)
        doReturn(cursor).`when`(resolver).query(same(documentUri), any(), any(), any(), any())
    }

    private fun listingCursor(rows: List<Triple<String, String, String>>): Cursor {
        val cursor = mock(Cursor::class.java)
        doReturn(0).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        doReturn(1).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        doReturn(2).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        var position = -1
        doAnswer { ++position < rows.size }.`when`(cursor).moveToNext()
        doAnswer { rows[position].first }.`when`(cursor).getString(0)
        doAnswer { rows[position].second }.`when`(cursor).getString(1)
        doAnswer { rows[position].third }.`when`(cursor).getString(2)
        return cursor
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
        const val TREE_ID = "primary:Music"
        const val SOURCE_ID = "primary:Music/Song.flac"
        const val METADATA_ID = "primary:Music/Song.flac.npmeta.json"
        const val OTHER_ID = "primary:Music/Other.flac"
        const val METADATA_NAME = "Song.flac$LOCAL_METADATA_SUFFIX"
        val SOURCE_ROW = Triple(SOURCE_ID, "Song.flac", "audio/flac")
        val METADATA_ROW = Triple(METADATA_ID, METADATA_NAME, "application/json")
        val OTHER_ROW = Triple(OTHER_ID, "Other.flac", "audio/flac")
    }
}
