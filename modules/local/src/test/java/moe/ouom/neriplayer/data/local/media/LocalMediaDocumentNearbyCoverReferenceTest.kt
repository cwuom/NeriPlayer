package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.ByteArrayInputStream
import java.io.InputStream

class LocalMediaDocumentNearbyCoverReferenceTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri(SOURCE_REFERENCE)
    private val songCoverUri = uri(SONG_COVER_REFERENCE)
    private val folderCoverUri = uri(FOLDER_COVER_REFERENCE)
    private val managedCoverUri = uri(MANAGED_COVER_REFERENCE)
    private val coversChildrenUri = uri("$TREE/document/covers/children")
    private val coverUris = listOf(songCoverUri, folderCoverUri, managedCoverUri)
    private val source = DocumentChild(SOURCE_ID, "Song.flac", isDirectory = false, uri = SOURCE_REFERENCE)
    private val songCover = DocumentChild(SONG_COVER_ID, "Song.jpg", isDirectory = false, uri = SONG_COVER_REFERENCE)
    private val folderCover = DocumentChild(
        FOLDER_COVER_ID,
        "cover.png",
        isDirectory = false,
        uri = FOLDER_COVER_REFERENCE
    )
    private val coversDirectory = DocumentChild(COVERS_ID, "Covers", isDirectory = true, uri = "$TREE/document/covers")

    @Before
    @After
    fun clearLookupCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `song specific cover sidecars win over folder covers`() {
        val reference = withDocumentTree(decodable = true) { find(listOf(source, folderCover, songCover)) }

        assertEquals(SONG_COVER_REFERENCE, reference)
        verify(resolver, never()).openInputStream(folderCoverUri)
    }

    @Test
    fun `folder covers are used when the song has no cover sidecar of its own`() {
        val reference = withDocumentTree(decodable = true) { find(listOf(source, folderCover)) }

        assertEquals(FOLDER_COVER_REFERENCE, reference)
    }

    @Test
    fun `cover sidecars in the managed covers folder are found`() {
        doReturn(listingCursor(MANAGED_COVER_ID, "Song.png", "image/png"))
            .`when`(resolver).query(same(coversChildrenUri), any(), any(), any(), any())

        val reference = withDocumentTree(decodable = true) { find(listOf(source, coversDirectory)) }

        assertEquals(MANAGED_COVER_REFERENCE, reference)
    }

    @Test
    fun `cover sidecars that cannot be decoded are not offered`() {
        val reference = withDocumentTree(decodable = false) { find(listOf(source, songCover)) }

        assertNull(reference)
        verify(resolver).openInputStream(songCoverUri)
    }

    private fun find(parentChildren: List<DocumentChild>): String? = LocalMediaSupport.findNearbyCoverReference(
        context = context,
        uri = sourceUri,
        file = null,
        displayName = "Song.flac",
        parentChildrenForMutation = parentChildren
    )

    private fun <T> withDocumentTree(decodable: Boolean, block: () -> T): T {
        coverUris.forEach { cover ->
            doReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3))).`when`(resolver).openInputStream(cover)
        }
        val parsed = coverUris.associateBy(Uri::toString)
        return mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(sourceUri) }.thenReturn(TREE_ID)
            contract.`when`<String> { DocumentsContract.getDocumentId(sourceUri) }.thenReturn(SOURCE_ID)
            contract.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID) }.thenReturn(treeUri)
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, COVERS_ID)
            }.thenReturn(coversChildrenUri)
            contract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(treeUri, SOURCE_ID) }.thenReturn(sourceUri)
            contract.`when`<Uri> {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, MANAGED_COVER_ID)
            }.thenReturn(managedCoverUri)
            mockStatic(DocumentFile::class.java).use {
                mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
                    uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { parsed[it.getArgument<String>(0)] }
                    if (decodable) withDecodableImages(block) else block()
                }
            }
        }
    }

    private fun <T> withDecodableImages(block: () -> T): T {
        return mockStatic(BitmapFactory::class.java).use { decoder ->
            decoder.`when`<Any?> {
                BitmapFactory.decodeStream(any(InputStream::class.java), isNull(), any())
            }.thenAnswer { invocation ->
                invocation.getArgument<BitmapFactory.Options>(2).apply {
                    outWidth = 600
                    outHeight = 600
                }
                null
            }
            block()
        }
    }

    private fun listingCursor(documentId: String, displayName: String, mimeType: String): Cursor {
        val cursor = mock(Cursor::class.java)
        doReturn(0).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        doReturn(1).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        doReturn(2).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        var position = -1
        doAnswer { ++position < 1 }.`when`(cursor).moveToNext()
        doReturn(documentId).`when`(cursor).getString(0)
        doReturn(displayName).`when`(cursor).getString(1)
        doReturn(mimeType).`when`(cursor).getString(2)
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
        const val SONG_COVER_ID = "primary:Music/Song.jpg"
        const val FOLDER_COVER_ID = "primary:Music/cover.png"
        const val COVERS_ID = "primary:Music/Covers"
        const val MANAGED_COVER_ID = "primary:Music/Covers/Song.png"
        const val SOURCE_REFERENCE = "$TREE/document/song"
        const val SONG_COVER_REFERENCE = "$TREE/document/song-jpg"
        const val FOLDER_COVER_REFERENCE = "$TREE/document/cover-png"
        const val MANAGED_COVER_REFERENCE = "$TREE/document/covers-song-png"
    }
}
