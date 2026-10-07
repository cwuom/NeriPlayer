package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.ByteArrayInputStream

class LocalMediaContentLyricInspectionTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri("$TREE/document/song")
    private val originalUri = uri(ORIGINAL_REFERENCE)
    private val translatedUri = uri(TRANSLATED_REFERENCE)
    private val parentChildrenUri = uri("$TREE/document/music/children")

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `lyric sidecars beside a document song are read from the provider`() {
        stubParentListing(SOURCE_ROW, Triple(ORIGINAL_ID, "Song.lrc", "text/plain"))
        doReturn(ByteArrayInputStream(ORIGINAL_LYRIC.toByteArray())).`when`(resolver).openInputStream(originalUri)

        val inspection = withDocumentTree {
            LocalMediaSupport.inspectLyricsFromContentUri(context, sourceUri, "Song.flac")
        }

        assertEquals(
            expected(original = ORIGINAL_LYRIC, hasOriginalSidecar = true),
            inspection
        )
    }

    @Test
    fun `lyric sidecars the provider cannot open are not reported`() {
        stubParentListing(
            SOURCE_ROW,
            Triple(ORIGINAL_ID, "Song.lrc", "text/plain"),
            Triple(TRANSLATED_ID, "Song_trans.lrc", "text/plain")
        )
        doReturn(ByteArrayInputStream(TRANSLATED_LYRIC.toByteArray())).`when`(resolver).openInputStream(translatedUri)

        val inspection = withDocumentTree {
            LocalMediaSupport.inspectLyricsFromContentUri(context, sourceUri, "Song.flac")
        }

        assertEquals(
            expected(translated = TRANSLATED_LYRIC, hasTranslatedSidecar = true),
            inspection
        )
    }

    @Test
    fun `songs without lyric sidecars report nothing`() {
        stubParentListing(SOURCE_ROW)

        val inspection = withDocumentTree {
            LocalMediaSupport.inspectLyricsFromContentUri(context, sourceUri, "Song.flac")
        }

        assertEquals(expected(), inspection)
    }

    private fun expected(
        original: String? = null,
        translated: String? = null,
        hasOriginalSidecar: Boolean = false,
        hasTranslatedSidecar: Boolean = false
    ) = DirectLocalLyricsInspection(
        original = original,
        translated = translated,
        romanized = null,
        metadataOriginal = null,
        metadataTranslated = null,
        metadataRomanized = null,
        hasOriginalSidecar = hasOriginalSidecar,
        hasTranslatedSidecar = hasTranslatedSidecar,
        hasRomanizedSidecar = false
    )

    private fun <T> withDocumentTree(block: () -> T): T {
        val parsed = listOf(originalUri, translatedUri).associateBy(Uri::toString)
        return mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(sourceUri) }.thenReturn(TREE_ID)
            contract.`when`<String> { DocumentsContract.getDocumentId(sourceUri) }.thenReturn(SOURCE_ID)
            contract.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID) }.thenReturn(treeUri)
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, TREE_ID)
            }.thenReturn(parentChildrenUri)
            mapOf(
                SOURCE_ID to sourceUri,
                ORIGINAL_ID to originalUri,
                TRANSLATED_ID to translatedUri
            ).forEach { (documentId, documentUri) ->
                contract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }.thenReturn(documentUri)
            }
            mockStatic(DocumentFile::class.java).use {
                mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
                    uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { parsed[it.getArgument<String>(0)] }
                    block()
                }
            }
        }
    }

    private fun stubParentListing(vararg rows: Triple<String, String, String>) {
        doReturn(listingCursor(rows.toList()), listingCursor(rows.toList()), listingCursor(rows.toList()))
            .`when`(resolver).query(same(parentChildrenUri), any(), any(), any(), any())
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
        const val ORIGINAL_ID = "primary:Music/Song.lrc"
        const val TRANSLATED_ID = "primary:Music/Song_trans.lrc"
        const val ORIGINAL_REFERENCE = "$TREE/document/song-lrc"
        const val TRANSLATED_REFERENCE = "$TREE/document/song-trans-lrc"
        const val ORIGINAL_LYRIC = "[00:01.00]Hello"
        const val TRANSLATED_LYRIC = "[00:01.00]你好"
        val SOURCE_ROW = Triple(SOURCE_ID, "Song.flac", "audio/flac")
    }
}
