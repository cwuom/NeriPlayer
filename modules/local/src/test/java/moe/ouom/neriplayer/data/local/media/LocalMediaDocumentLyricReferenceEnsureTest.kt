package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentLyricReferenceResolution
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import moe.ouom.neriplayer.data.local.media.metadata.ensureDocumentLyricReferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaDocumentLyricReferenceEnsureTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri("$TREE/document/song")
    private val lyricsDirectoryUri = uri("$TREE/document/lyrics")
    private val originalUri = uri("$TREE/document/song-lrc")
    private val translatedUri = uri("$TREE/document/song-trans-lrc")
    private val parentChildrenUri = uri("$TREE/document/music/children")
    private val lyricsChildrenUri = uri("$TREE/document/lyrics/children")
    private val source = DocumentChild(SOURCE_ID, "Song.flac", isDirectory = false, uri = "$TREE/document/song")
    private val lyricsDirectory = DocumentChild(LYRICS_ID, "Lyrics", isDirectory = true, uri = "$TREE/document/lyrics")
    private val existing = NearbyLyricReferences(original = null, translated = null, romanized = null)
    private val unchanged = DocumentLyricReferenceResolution(existing, emptyMap())

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `non document sources and empty requests keep the existing references`() {
        val fileUri = mock(Uri::class.java).also { doReturn("file").`when`(it).scheme }

        assertEquals(unchanged, ensure(fileUri, setOf(LyricKind.ORIGINAL)))
        assertEquals(unchanged, ensure(sourceUri, emptySet()))
    }

    @Test
    fun `media store songs without a document location keep the existing references`() {
        val mediaUri = uri("content://media/external/audio/media/5", authority = "media")

        assertEquals(unchanged, ensure(mediaUri, setOf(LyricKind.ORIGINAL)))
    }

    @Test
    fun `documents without a known parent keep the existing references`() {
        assertEquals(unchanged, ensure(sourceUri, setOf(LyricKind.ORIGINAL)))
    }

    @Test
    fun `lyric sidecars already in the managed lyrics folder are reused`() {
        stubLyricsListing()

        val resolution = withDocumentTree {
            ensure(
                sourceUri,
                setOf(LyricKind.ORIGINAL, LyricKind.TRANSLATED),
                parentChildren = listOf(source, lyricsDirectory)
            )
        }

        assertEquals(
            DocumentLyricReferenceResolution(
                NearbyLyricReferences(
                    original = originalUri.toString(),
                    translated = translatedUri.toString(),
                    romanized = null
                ),
                emptyMap()
            ),
            resolution
        )
    }

    @Test
    fun `parent listings are read from the provider when none is supplied`() {
        stubListing(
            parentChildrenUri,
            Triple(SOURCE_ID, "Song.flac", "audio/flac"),
            Triple(LYRICS_ID, "Lyrics", DocumentsContract.Document.MIME_TYPE_DIR)
        )
        stubLyricsListing()

        val resolution = withDocumentTree { ensure(sourceUri, setOf(LyricKind.ORIGINAL)) }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertNull(resolution.references.translated)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `documents missing from the parent listing keep the existing references`() {
        assertEquals(
            unchanged,
            withDocumentTree {
                ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(lyricsDirectory))
            }
        )
    }

    @Test
    fun `lyrics folders that cannot be listed keep the existing references`() {
        assertEquals(
            unchanged,
            withDocumentTree {
                ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, lyricsDirectory))
            }
        )
    }

    @Test
    fun `missing lyrics folders keep the existing references when the parent cannot be refreshed`() {
        assertEquals(
            unchanged,
            withDocumentTree { ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source)) }
        )
    }

    @Test
    fun `parents that cannot be listed keep the existing references`() {
        assertEquals(unchanged, withDocumentTree { ensure(sourceUri, setOf(LyricKind.ORIGINAL)) })
    }

    private fun ensure(
        uri: Uri,
        kinds: Set<LyricKind>,
        parentChildren: List<DocumentChild>? = null
    ): DocumentLyricReferenceResolution = LocalMediaSupport.ensureDocumentLyricReferences(
        context = context,
        uri = uri,
        displayName = "Song.flac",
        requiredKinds = kinds,
        existing = existing,
        parentChildrenForMutation = parentChildren
    )

    private fun <T> withDocumentTree(block: () -> T): T {
        return mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(sourceUri) }.thenReturn(TREE_ID)
            contract.`when`<String> { DocumentsContract.getDocumentId(sourceUri) }.thenReturn(SOURCE_ID)
            contract.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID) }.thenReturn(treeUri)
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, TREE_ID)
            }.thenReturn(parentChildrenUri)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, LYRICS_ID)
            }.thenReturn(lyricsChildrenUri)
            mapOf(
                SOURCE_ID to sourceUri,
                LYRICS_ID to lyricsDirectoryUri,
                ORIGINAL_ID to originalUri,
                TRANSLATED_ID to translatedUri
            ).forEach { (documentId, documentUri) ->
                contract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }.thenReturn(documentUri)
            }
            mockStatic(DocumentFile::class.java).use { block() }
        }
    }

    private fun stubLyricsListing() {
        stubListing(
            lyricsChildrenUri,
            Triple(ORIGINAL_ID, "Song.lrc", "text/plain"),
            Triple(TRANSLATED_ID, "Song_trans.lrc", "text/plain")
        )
    }

    private fun stubListing(childrenUri: Uri, vararg rows: Triple<String, String, String>) {
        doReturn(listingCursor(rows.toList()), listingCursor(rows.toList()), listingCursor(rows.toList()))
            .`when`(resolver).query(same(childrenUri), any(), any(), any(), any())
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

    private fun uri(value: String, authority: String = AUTHORITY): Uri {
        val uri = mock(Uri::class.java)
        doReturn("content").`when`(uri).scheme
        doReturn(authority).`when`(uri).authority
        doReturn(value).`when`(uri).toString()
        return uri
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val TREE = "content://$AUTHORITY/tree/primary%3AMusic"
        const val TREE_ID = "primary:Music"
        const val SOURCE_ID = "primary:Music/Song.flac"
        const val LYRICS_ID = "primary:Music/Lyrics"
        const val ORIGINAL_ID = "primary:Music/Lyrics/Song.lrc"
        const val TRANSLATED_ID = "primary:Music/Lyrics/Song_trans.lrc"
    }
}
