package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaDocumentChildrenCachingTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val lyric = DocumentChild("doc-1", "Song.lrc", isDirectory = false, uri = "content://provider/doc-1")
    private val cover = DocumentChild("doc-2", "Song.jpg", isDirectory = false, uri = "content://provider/doc-2")
    private val song = DocumentChild("song", "Song.flac", isDirectory = false, uri = "$TREE/document/song")

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `listings without a usable parent id are not cached`() {
        LocalMediaSupport.cacheDocumentChildren(treeUri, null, listOf(lyric))
        LocalMediaSupport.cacheDocumentChildren(treeUri, "  ", listOf(lyric))

        synchronized(LocalMediaSupport.documentChildrenCache) {
            assertTrue(LocalMediaSupport.documentChildrenCache.isEmpty())
        }
    }

    @Test
    fun `complete listings replace the cached children`() {
        seed("parent", listOf(lyric), isComplete = false)

        LocalMediaSupport.cacheDocumentChildren(treeUri, "parent", listOf(cover), isComplete = true)

        val entry = entry("parent")!!
        assertEquals(listOf(cover), entry.children)
        assertTrue(entry.isComplete)
    }

    @Test
    fun `incomplete listings are merged by uri into the cached children`() {
        val renamedLyric = lyric.copy(displayName = "Song (1).lrc")
        seed("parent", listOf(lyric), isComplete = true)

        LocalMediaSupport.cacheDocumentChildren(treeUri, "parent", listOf(cover, renamedLyric), isComplete = false)
        LocalMediaSupport.cacheDocumentChildren(treeUri, "new-parent", listOf(cover), isComplete = false)

        val merged = entry("parent")!!
        assertEquals(listOf(renamedLyric, cover), merged.children)
        assertFalse(merged.isComplete)
        assertEquals(listOf(cover), entry("new-parent")!!.children)
    }

    @Test
    fun `oversized listings drop the cached children instead of being stored`() {
        seed("parent", listOf(lyric), isComplete = true)
        val oversized = List(DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN + 1) { index ->
            DocumentChild("doc-$index", "Track $index.flac", isDirectory = false, uri = "content://provider/doc-$index")
        }

        LocalMediaSupport.cacheDocumentChildren(treeUri, "parent", oversized, isComplete = true)

        assertNull(entry("parent"))
    }

    @Test
    fun `blank parent ids list nothing without querying the provider`() {
        assertTrue(LocalMediaSupport.queryDocumentChildren(context, treeUri, null).isEmpty())
        assertTrue(LocalMediaSupport.queryDocumentChildren(context, treeUri, " ").isEmpty())

        verifyNoInteractions(resolver)
    }

    @Test
    fun `fresh cached listings are served without querying the provider`() {
        seed("parent", listOf(lyric, cover), isComplete = true, cachedAtMs = System.currentTimeMillis() + 60_000L)

        assertEquals(listOf(lyric, cover), LocalMediaSupport.queryDocumentChildren(context, treeUri, "parent"))
        verifyNoInteractions(resolver)
    }

    @Test
    fun `uncached listings are read from the provider and cached as complete`() {
        assertEquals(listOf(song), readFromProvider("parent"))

        val entry = entry("parent")!!
        assertEquals(listOf(song), entry.children)
        assertTrue(entry.isComplete)
    }

    @Test
    fun `stale cached listings are replaced by a fresh provider listing`() {
        seed("parent", listOf(lyric), isComplete = true, cachedAtMs = 0L)

        assertEquals(listOf(song), readFromProvider("parent"))
        assertEquals(listOf(song), entry("parent")!!.children)
    }

    private fun readFromProvider(parentDocumentId: String): List<DocumentChild> {
        val childrenUri = uri("$TREE/document/$parentDocumentId/children")
        val songUri = uri(song.uri)
        val cursor = mock(Cursor::class.java)
        doReturn(0).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        doReturn(1).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        doReturn(2).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        doReturn(true, false).`when`(cursor).moveToNext()
        doReturn(song.documentId).`when`(cursor).getString(0)
        doReturn(song.displayName).`when`(cursor).getString(1)
        doReturn("audio/flac").`when`(cursor).getString(2)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())

        return mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<Boolean> { DocumentsContract.isTreeUri(treeUri) }.thenReturn(true)
            contract.`when`<Uri> {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            }.thenReturn(childrenUri)
            contract.`when`<Uri> {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, song.documentId)
            }.thenReturn(songUri)
            LocalMediaSupport.queryDocumentChildren(context, treeUri, parentDocumentId)
        }
    }

    private fun entry(parentDocumentId: String): LocalMediaSupport.DocumentChildrenCacheEntry? {
        val key = LocalMediaSupport.documentParentCacheKey(treeUri, parentDocumentId)
        return synchronized(LocalMediaSupport.documentChildrenCache) {
            LocalMediaSupport.documentChildrenCache[key]
        }
    }

    private fun seed(
        parentDocumentId: String,
        children: List<DocumentChild>,
        isComplete: Boolean,
        cachedAtMs: Long = System.currentTimeMillis()
    ) {
        val key = LocalMediaSupport.documentParentCacheKey(treeUri, parentDocumentId)
        synchronized(LocalMediaSupport.documentChildrenCache) {
            LocalMediaSupport.rememberDocumentChildrenCacheEntryLocked(key, children, cachedAtMs, isComplete)
        }
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
    }
}
