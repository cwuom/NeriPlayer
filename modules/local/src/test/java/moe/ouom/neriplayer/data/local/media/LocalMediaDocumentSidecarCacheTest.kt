package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaDocumentSidecarCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val treeUri = uri(
        "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        authority = "com.android.externalstorage.documents"
    )
    private val lyric = DocumentChild("doc-1", "Song.lrc", isDirectory = false, uri = "content://provider/doc-1")
    private val cover = DocumentChild("doc-2", "Song.jpg", isDirectory = false, uri = "content://provider/doc-2")

    @Before
    @After
    fun clearSafCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `document parent cache keys are scoped by the tree document id`() {
        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(treeUri) }.thenReturn("primary:Music")

            assertEquals(
                "generation=${LocalStorageRootGeneration.current()}|" +
                    "com.android.externalstorage.documents|primary:Music|primary:Music/Album",
                LocalMediaSupport.documentParentCacheKey(treeUri, "primary:Music/Album")
            )
        }
    }

    @Test
    fun `document parent cache keys fall back to the base uri without a usable tree id`() {
        val documentUri = uri("content://provider/document/root", authority = null)
        val expected = "generation=${LocalStorageRootGeneration.current()}||content://provider/document/root|parent"

        assertEquals(expected, LocalMediaSupport.documentParentCacheKey(documentUri, "parent"))
        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(documentUri) }.thenReturn(" ")
            assertEquals(expected, LocalMediaSupport.documentParentCacheKey(documentUri, "parent"))

            contract.`when`<String> { DocumentsContract.getTreeDocumentId(documentUri) }
                .thenThrow(IllegalArgumentException("not a tree uri"))
            assertEquals(expected, LocalMediaSupport.documentParentCacheKey(documentUri, "parent"))
        }
    }

    @Test
    fun `revoked tree permissions are not hidden by the cache key`() {
        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<String> { DocumentsContract.getTreeDocumentId(treeUri) }
                .thenThrow(SecurityException("permission revoked"))

            assertThrows(SecurityException::class.java) {
                LocalMediaSupport.documentParentCacheKey(treeUri, "parent")
            }
        }
    }

    @Test
    fun `remembered children are merged by uri into an incomplete listing`() {
        val renamedLyric = lyric.copy(displayName = "Song (1).lrc")

        LocalMediaSupport.rememberDocumentChild(treeUri, "parent", lyric)
        LocalMediaSupport.rememberDocumentChild(treeUri, "parent", cover)
        LocalMediaSupport.rememberDocumentChild(treeUri, "parent", renamedLyric)

        val entry = cacheEntry("parent")!!
        assertEquals(listOf(renamedLyric, cover), entry.children)
        assertFalse(entry.isComplete)
        assertNull(cacheEntry("other-parent"))
    }

    @Test
    fun `remembering a child keeps a complete listing complete`() {
        seedListing("parent", listOf(lyric), cachedAtMs = System.currentTimeMillis(), isComplete = true)

        LocalMediaSupport.rememberDocumentChild(treeUri, "parent", cover)

        val entry = cacheEntry("parent")!!
        assertEquals(listOf(lyric, cover), entry.children)
        assertTrue(entry.isComplete)
    }

    @Test
    fun `cached children are served only while the listing is fresh`() {
        // 目录缓存的有效期只有几百毫秒, 时间戳放在当前时间之后才能保证读取时仍然新鲜
        seedListing("fresh", listOf(lyric, cover), cachedAtMs = System.currentTimeMillis() + 60_000L, isComplete = true)
        seedListing("stale", listOf(lyric), cachedAtMs = 0L, isComplete = true)

        assertEquals(listOf(lyric, cover), LocalMediaSupport.cachedDocumentChildren(treeUri, "fresh"))
        assertTrue(LocalMediaSupport.cachedDocumentChildren(treeUri, "stale").isEmpty())
        assertTrue(LocalMediaSupport.cachedDocumentChildren(treeUri, "missing").isEmpty())

        LocalMediaSupport.invalidateDocumentChildrenCache(treeUri, "fresh")
        assertTrue(LocalMediaSupport.cachedDocumentChildren(treeUri, "fresh").isEmpty())
    }

    @Test
    fun `document parent id is the second to last segment of the document path`() {
        val (context, resolver) = contextWithResolver()
        val document = uri("content://com.android.externalstorage.documents/document/song", authority = "com.android.externalstorage.documents")
        val path = mock(DocumentsContract.Path::class.java)

        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(resolver, document) }
                .thenReturn(path)

            doReturn(listOf("primary:", "primary:Music", "primary:Music/song.mp3")).`when`(path).path
            assertEquals("primary:Music", LocalMediaSupport.findDocumentParentId(context, document))

            doReturn(listOf("primary:Music/song.mp3")).`when`(path).path
            assertNull(LocalMediaSupport.findDocumentParentId(context, document))

            doReturn(listOf(" ", "primary:Music/song.mp3")).`when`(path).path
            assertNull(LocalMediaSupport.findDocumentParentId(context, document))

            doReturn(null).`when`(path).path
            assertNull(LocalMediaSupport.findDocumentParentId(context, document))
        }
    }

    @Test
    fun `document parent lookup gives up on providers without a path`() {
        val (context, resolver) = contextWithResolver()
        val document = uri("content://com.example.documents/document/song", authority = "com.example.documents")

        mockStatic(DocumentsContract::class.java).use { contract ->
            contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(resolver, document) }
                .thenReturn(null)
            assertNull(LocalMediaSupport.findDocumentParentId(context, document))

            contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(resolver, document) }
                .thenThrow(UnsupportedOperationException("no path support"))
            assertNull(LocalMediaSupport.findDocumentParentId(context, document))

            contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(resolver, document) }
                .thenThrow(SecurityException("permission revoked"))
            assertThrows(SecurityException::class.java) {
                LocalMediaSupport.findDocumentParentId(context, document)
            }
        }
    }

    @Test
    fun `media store documents have no tree parent`() {
        val (context, _) = contextWithResolver()

        assertNull(
            LocalMediaSupport.findDocumentParentId(
                context,
                uri("content://media/external/audio/media/1", authority = "media")
            )
        )
    }

    @Test
    fun `provider numbered suffixes are removed only when they carry a number`() {
        mapOf(
            "cover (2).jpg" to "cover.jpg",
            "cover.jpg" to "cover.jpg",
            "cover (x).jpg" to "cover (x).jpg",
            "cover (2.jpg" to "cover (2.jpg",
            ".jpg" to ".jpg",
            "cover." to "cover.",
            "Lyrics (3)" to "Lyrics",
            "Lyrics (x)" to "Lyrics (x)",
            "song.lrc (2)" to "song.lrc"
        ).forEach { (actualName, expected) ->
            assertEquals(actualName, expected, LocalMediaSupport.removeProviderNumberedSidecarSuffix(actualName))
        }
    }

    @Test
    fun `cover sidecars match the plain name or a short or full hash suffix`() {
        listOf(
            "Song.jpg",
            "Song-1a2b3c4d.jpg",
            "Song-${"0123456789abcdef".repeat(2)}.jpg",
            "Song-ABCDEF12 (2).jpg"
        ).forEach { name -> assertTrue(name, LocalMediaSupport.coverSidecarNameMatches(name, "Song", "jpg")) }
        listOf(
            "Song-123456789.jpg",
            "Song-xyz.jpg",
            "Song-.jpg",
            "Other-1a2b.jpg",
            "Song-1a2b.png"
        ).forEach { name -> assertFalse(name, LocalMediaSupport.coverSidecarNameMatches(name, "Song", "jpg")) }
    }

    @Test
    fun `local cover sidecar names add the plain name only for identified songs`() {
        val identified = LocalMediaSupport.localCoverSidecarName("Song", "jpg", "netease:1")

        assertEquals(listOf("Song.jpg"), LocalMediaSupport.localCoverSidecarNames("Song", "jpg", null))
        assertEquals(listOf("Song.jpg"), LocalMediaSupport.localCoverSidecarNames("Song", "jpg", "  "))
        assertEquals(
            listOf(identified, "Song.jpg"),
            LocalMediaSupport.localCoverSidecarNames("Song", "jpg", "netease:1")
        )
    }

    @Test
    fun `managed cover sidecar names exist only for a stable identity`() {
        assertTrue(LocalMediaSupport.managedCoverSidecarNames("Song", listOf("jpg"), null).isEmpty())
        assertTrue(LocalMediaSupport.managedCoverSidecarNames("Song", listOf("jpg"), " ").isEmpty())
        assertEquals(
            linkedSetOf(
                LocalMediaSupport.localCoverSidecarName("Song", "jpg", "netease:1"),
                LocalMediaSupport.localCoverSidecarName("Song", "png", "netease:1")
            ),
            LocalMediaSupport.managedCoverSidecarNames("Song", listOf("jpg", "png", "jpg"), " netease:1 ")
        )
    }

    @Test
    fun `document lyric references ignore directories and unmatched names`() {
        val references = LocalMediaSupport.resolveDocumentLyricReferences(
            children = listOf(
                lyric,
                DocumentChild("doc-3", "Song_trans.lrc", isDirectory = false, uri = "content://provider/doc-3"),
                DocumentChild("doc-4", "Song_roma.lrc", isDirectory = true, uri = "content://provider/doc-4"),
                DocumentChild("doc-5", "Other.lrc", isDirectory = false, uri = "content://provider/doc-5")
            ),
            baseName = "Song"
        )

        assertEquals(
            NearbyLyricReferences(
                original = "content://provider/doc-1",
                translated = "content://provider/doc-3",
                romanized = null
            ),
            references
        )
    }

    @Test
    fun `local metadata reference points at an existing sibling metadata file`() {
        val audio = temporaryFolder.newFile("song.mp3")

        assertNull(LocalMediaSupport.localMetadataReference(audio))
        val metadata = File(audio.parentFile, "song.mp3.npmeta.json").apply { writeText("{}") }
        assertEquals(metadata.absolutePath, LocalMediaSupport.localMetadataReference(audio))
        assertNull(LocalMediaSupport.localMetadataReference(File("song.mp3")))
        assertNull(LocalMediaSupport.localMetadataReference(null))
    }

    private fun cacheEntry(parentDocumentId: String): LocalMediaSupport.DocumentChildrenCacheEntry? {
        val key = LocalMediaSupport.documentParentCacheKey(treeUri, parentDocumentId)
        return synchronized(LocalMediaSupport.documentChildrenCache) {
            LocalMediaSupport.documentChildrenCache[key]
        }
    }

    private fun seedListing(
        parentDocumentId: String,
        children: List<DocumentChild>,
        cachedAtMs: Long,
        isComplete: Boolean
    ) {
        val key = LocalMediaSupport.documentParentCacheKey(treeUri, parentDocumentId)
        synchronized(LocalMediaSupport.documentChildrenCache) {
            LocalMediaSupport.rememberDocumentChildrenCacheEntryLocked(key, children, cachedAtMs, isComplete)
        }
    }

    private fun contextWithResolver(): Pair<Context, ContentResolver> {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver
        return context to resolver
    }

    private fun uri(value: String, authority: String?): Uri {
        val uri = mock(Uri::class.java)
        doReturn("content").`when`(uri).scheme
        doReturn(authority).`when`(uri).authority
        doReturn(value).`when`(uri).toString()
        return uri
    }
}
