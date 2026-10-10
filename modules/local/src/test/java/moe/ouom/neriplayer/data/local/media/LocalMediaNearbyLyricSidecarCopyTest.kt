package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.io.ByteArrayInputStream
import java.io.File

class LocalMediaNearbyLyricSidecarCopyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri("$TREE/document/song")
    private val metadataUri = uri(METADATA_REFERENCE)
    private val originalUri = uri(ORIGINAL_REFERENCE)
    private val translatedUri = uri(TRANSLATED_REFERENCE)
    private val parentChildrenUri = uri("$TREE/document/music/children")

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `document sidecars are copied beside the imported song without replacing existing lyrics`() {
        val target = targetFile()
        File(target.parentFile, "Target_trans.lrc").writeText("existing translation")
        doReturn(listingCursor(), listingCursor(), listingCursor())
            .`when`(resolver).query(same(parentChildrenUri), any(), any(), any(), any())
        doReturn(ByteArrayInputStream(METADATA_JSON.toByteArray())).`when`(resolver).openInputStream(metadataUri)
        doReturn(ByteArrayInputStream(ORIGINAL_LYRIC.toByteArray())).`when`(resolver).openInputStream(originalUri)

        withDocumentTree { LocalMediaSupport.copyNearbyLyricSidecars(context, sourceUri, "Song.flac", target) }

        assertEquals(METADATA_JSON, File(target.parentFile, "Target.flac$LOCAL_METADATA_SUFFIX").readText())
        assertEquals(ORIGINAL_LYRIC, File(target.parentFile, "Target.lrc").readText())
        assertEquals("existing translation", File(target.parentFile, "Target_trans.lrc").readText())
        assertFalse(File(target.parentFile, "Target_roma.lrc").exists())
        verify(resolver, never()).openInputStream(translatedUri)
    }

    @Test
    fun `file sources are not copied through the document provider`() {
        val target = targetFile()
        val fileUri = mock(Uri::class.java).also { doReturn("file").`when`(it).scheme }

        LocalMediaSupport.copyNearbyLyricSidecars(context, fileUri, "Song.flac", target)

        assertEquals(listOf("Target.flac"), target.parentFile!!.list()!!.sorted())
        verifyNoInteractions(resolver)
    }

    @Test
    fun `documents without a known folder copy nothing`() {
        val target = targetFile()

        LocalMediaSupport.copyNearbyLyricSidecars(context, sourceUri, "Song.flac", target)

        assertEquals(listOf("Target.flac"), target.parentFile!!.list()!!.sorted())
        verify(resolver, never()).openInputStream(any())
    }

    private fun targetFile(): File = File(temporaryFolder.newFolder("imported"), "Target.flac").apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun <T> withDocumentTree(block: () -> T): T {
        val parsed = listOf(metadataUri, originalUri, translatedUri).associateBy(Uri::toString)
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
                METADATA_ID to metadataUri,
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

    private fun listingCursor(): Cursor {
        val rows = listOf(
            Triple(SOURCE_ID, "Song.flac", "audio/flac"),
            Triple(METADATA_ID, "Song.flac$LOCAL_METADATA_SUFFIX", "application/json"),
            Triple(ORIGINAL_ID, "Song.lrc", "text/plain"),
            Triple(TRANSLATED_ID, "Song_trans.lrc", "text/plain")
        )
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
        const val ORIGINAL_ID = "primary:Music/Song.lrc"
        const val TRANSLATED_ID = "primary:Music/Song_trans.lrc"
        const val METADATA_REFERENCE = "$TREE/document/song-metadata"
        const val ORIGINAL_REFERENCE = "$TREE/document/song-lrc"
        const val TRANSLATED_REFERENCE = "$TREE/document/song-trans-lrc"
        const val METADATA_JSON = """{"name":"Song"}"""
        const val ORIGINAL_LYRIC = "[00:01.00]Hello"
    }
}
