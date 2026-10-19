package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentLyricReferenceResolution
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import moe.ouom.neriplayer.data.local.media.metadata.ensureDocumentLyricReferences
import moe.ouom.neriplayer.data.local.media.metadata.writeLocalLyricsSidecars
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.same
import org.mockito.MockedStatic
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaDocumentLyricReferenceEnsureTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val treeUri = uri(TREE)
    private val sourceUri = uri("$TREE/document/song")
    private val metadataUri = uri("$TREE/document/metadata")
    private val lyricsDirectoryUri = uri("$TREE/document/lyrics")
    private val indexedDirectoryUri = uri("$TREE/document/indexed-lyrics")
    private val originalUri = uri("$TREE/document/song-lrc")
    private val replacementUri = uri("$TREE/document/replacement-lrc")
    private val translatedUri = uri("$TREE/document/song-trans-lrc")
    private val parentChildrenUri = uri("$TREE/document/music/children")
    private val lyricsChildrenUri = uri("$TREE/document/lyrics/children")
    private val source = DocumentChild(SOURCE_ID, "Song.flac", isDirectory = false, uri = "$TREE/document/song")
    private val lyricsDirectory = DocumentChild(LYRICS_ID, "Lyrics", isDirectory = true, uri = "$TREE/document/lyrics")
    private val existing = NearbyLyricReferences(original = null, translated = null, romanized = null)
    private val unchanged = DocumentLyricReferenceResolution(existing, emptyMap())
    private val unresolved = DocumentLyricReferenceResolution(existing, emptyMap(), lookupSucceeded = false)

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
    fun `media store songs without a document location refuse mutation`() {
        val mediaUri = uri("content://media/external/audio/media/5", authority = "media")

        assertEquals(unresolved, ensure(mediaUri, setOf(LyricKind.ORIGINAL)))
    }

    @Test
    fun `documents without a known parent refuse mutation`() {
        assertEquals(unresolved, ensure(sourceUri, setOf(LyricKind.ORIGINAL)))
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
    fun `adjacent lyrics are reused without creating a Lyrics folder`() {
        val adjacent = DocumentChild("adjacent-lrc", "Song.lrc", false, originalUri.toString())

        val resolution = withDocumentTree {
            ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, adjacent))
        }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `provider numbered and text suffixes are reused across repeated writes`() {
        stubListing(lyricsChildrenUri, Triple(ORIGINAL_ID, "Song (2).lrc.txt", "text/plain"))

        val resolutions = withDocumentTree {
            List(2) {
                ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, lyricsDirectory))
            }
        }

        resolutions.forEach { resolution ->
            assertEquals(originalUri.toString(), resolution.references.original)
            assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
        }
    }

    @Test
    fun `existing alternate lyric extensions are reused`() {
        stubListing(lyricsChildrenUri, Triple(ORIGINAL_ID, "Song.txt", "text/plain"))

        val resolution = withDocumentTree {
            ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, lyricsDirectory))
        }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `a lyric appearing during refresh is reused without creation ownership`() {
        stubListing(
            parentChildrenUri,
            Triple(SOURCE_ID, "Song.flac", "audio/flac"),
            Triple(LYRICS_ID, "Lyrics", DocumentsContract.Document.MIME_TYPE_DIR)
        )
        val row = Triple(ORIGINAL_ID, "Song.lrc", "text/plain")
        doReturn(listingCursor(emptyList()), listingCursor(listOf(row)))
            .`when`(resolver).query(same(lyricsChildrenUri), any(), any(), any(), any())

        val resolution = withDocumentTree {
            ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, lyricsDirectory))
        }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `empty document variants need no sidecar directory`() {
        val written = withDocumentTree {
            LocalMediaSupport.writeLocalLyricsSidecars(
                context = context,
                sourceUri = sourceUri,
                file = null,
                displayName = "Song.flac",
                song = SongItem(
                    id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
                    durationMs = 1000L, coverUrl = null, matchedLyric = "",
                    matchedTranslatedLyric = "  \n", matchedRomanizedLyric = ""
                ),
                knownReferences = existing,
                parentChildrenForMutation = listOf(source)
            )
        }

        assertTrue(written)
    }

    @Test
    fun `cached lyric writes preserve adjacent lyrics from a complete parent listing`() {
        val adjacent = DocumentChild(ORIGINAL_ID, "Song.lrc", false, originalUri.toString())
        stubListing(lyricsChildrenUri)

        val resolution = withCachedLyricsAccess {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory, adjacent))
            val cached = requireNotNull(LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac"))
            LocalMediaSupport.ensureDocumentLyricReferences(
                context, sourceUri, "Song.flac", setOf(LyricKind.ORIGINAL),
                cached.lyricReferences, cached.mutationParentChildren
            )
        }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `incomplete parent caches fall back before deciding adjacent lyrics are absent`() {
        val cached = withCachedLyricsAccess {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory), isComplete = false)
            LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac")
        }

        assertNull(cached)
    }

    @Test
    fun `cached Lyrics folders outside the audio parent are rejected`() {
        val cached = withCachedLyricsAccess(directoryParentId = "other-parent") {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory))
            LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac")
        }

        assertNull(cached)
    }

    @Test
    fun `a renamed cached folder cannot borrow validation from another indexed Lyrics folder`() {
        val cached = withCachedLyricsAccess(cachedDirectoryName = "Covers", indexedLyrics = true) {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory))
            LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac")
        }

        assertNull(cached)
    }

    @Test
    fun `a renamed adjacent lyric falls back to the replacement document`() {
        val adjacent = DocumentChild(ORIGINAL_ID, "Song.lrc", false, originalUri.toString())
        stubListing(
            parentChildrenUri,
            Triple(SOURCE_ID, "Song.flac", "audio/flac"),
            Triple(LYRICS_ID, "Lyrics", DocumentsContract.Document.MIME_TYPE_DIR),
            Triple(ORIGINAL_ID, "Other.lrc", "text/plain"),
            Triple("replacement-lrc", "Song.lrc", "text/plain")
        )
        stubListing(lyricsChildrenUri)

        val resolution = withCachedLyricsAccess(cachedOriginalName = "Other.lrc") {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory, adjacent))
            val references = LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac")
                ?: LocalMediaSupport.resolveContentSidecarReferences(context, sourceUri, "Song.flac", forMutation = true)
            LocalMediaSupport.ensureDocumentLyricReferences(
                context, sourceUri, "Song.flac", setOf(LyricKind.ORIGINAL),
                references.lyricReferences, references.mutationParentChildren
            )
        }

        assertEquals(replacementUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
    }

    @Test
    fun `a newly added adjacent lyric is refreshed before creating a nested duplicate`() {
        stubListing(
            parentChildrenUri,
            Triple(SOURCE_ID, "Song.flac", "audio/flac"),
            Triple(LYRICS_ID, "Lyrics", DocumentsContract.Document.MIME_TYPE_DIR),
            Triple(ORIGINAL_ID, "Song.lrc", "text/plain")
        )
        stubListing(lyricsChildrenUri)

        val resolution = withCachedLyricsAccess {
            LocalMediaSupport.cacheDocumentChildren(treeUri, TREE_ID, listOf(source, lyricsDirectory))
            val cached = requireNotNull(LocalMediaSupport.resolveCachedEditableLyricsReferences(context, sourceUri, "Song.flac"))
            LocalMediaSupport.ensureDocumentLyricReferences(
                context, sourceUri, "Song.flac", setOf(LyricKind.ORIGINAL),
                cached.lyricReferences, cached.mutationParentChildren
            )
        }

        assertEquals(originalUri.toString(), resolution.references.original)
        assertEquals(emptyMap<String, DocumentChild>(), resolution.createdReferences)
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
    fun `documents missing from the parent listing refuse mutation`() {
        assertEquals(
            unresolved,
            withDocumentTree {
                ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(lyricsDirectory))
            }
        )
    }

    @Test
    fun `lyrics folders that cannot be listed refuse mutation`() {
        assertEquals(
            unresolved,
            withDocumentTree {
                ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source, lyricsDirectory))
            }
        )
    }

    @Test
    fun `missing lyrics folders refuse mutation when the parent cannot be refreshed`() {
        assertEquals(
            unresolved,
            withDocumentTree { ensure(sourceUri, setOf(LyricKind.ORIGINAL), parentChildren = listOf(source)) }
        )
    }

    @Test
    fun `parents that cannot be listed refuse mutation`() {
        assertEquals(unresolved, withDocumentTree { ensure(sourceUri, setOf(LyricKind.ORIGINAL)) })
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

    private fun <T> withDocumentTree(block: (MockedStatic<DocumentsContract>) -> T): T {
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
                "replacement-lrc" to replacementUri,
                TRANSLATED_ID to translatedUri
            ).forEach { (documentId, documentUri) ->
                contract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }.thenReturn(documentUri)
            }
            mockStatic(DocumentFile::class.java).use { block(contract) }
        }
    }

    private fun <T> withCachedLyricsAccess(
        directoryParentId: String = TREE_ID,
        cachedDirectoryName: String = "Lyrics",
        cachedOriginalName: String = "Song.lrc",
        indexedLyrics: Boolean = false,
        block: () -> T
    ): T {
        val snapshot = mock(DownloadLibrarySnapshot::class.java)
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(true).`when`(snapshot).rootEntriesComplete
        doReturn(mapOf(sourceUri.toString() to entry("Song.flac", sourceUri))).`when`(snapshot).audioEntriesByLookupKey
        doReturn(mapOf("Song.flac" to entry("Song.flac$LOCAL_METADATA_SUFFIX", metadataUri)))
            .`when`(snapshot).metadataEntriesByAudioName
        doReturn(if (indexedLyrics) mapOf("Song.lrc" to entry("Song.lrc", originalUri)) else emptyMap())
            .`when`(snapshot).lyricEntriesByName
        doReturn(snapshot).`when`(downloads).cachedDownloadLibrarySnapshot(context, restorePersisted = false)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
        listOf(
            sourceUri to "Song.flac", metadataUri to "Song.flac$LOCAL_METADATA_SUFFIX",
            lyricsDirectoryUri to cachedDirectoryName, indexedDirectoryUri to "Lyrics",
            originalUri to cachedOriginalName
        )
            .forEach { (uri, name) ->
                doAnswer { nameCursor(name) }.`when`(resolver).query(same(uri), any(), any(), any(), any())
            }
        return withDocumentTree { contract ->
            listOf(
                sourceUri to SOURCE_ID, metadataUri to "metadata", lyricsDirectoryUri to LYRICS_ID,
                indexedDirectoryUri to "indexed-lyrics", originalUri to ORIGINAL_ID
            )
                .forEach { (uri, id) ->
                    val path = mock(DocumentsContract.Path::class.java)
                    val parent = when (uri) {
                        lyricsDirectoryUri -> directoryParentId
                        originalUri -> if (indexedLyrics) "indexed-lyrics" else TREE_ID
                        else -> TREE_ID
                    }
                    doReturn(listOf(parent, id)).`when`(path).path
                    contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(resolver, uri) }.thenReturn(path)
                    contract.`when`<String> { DocumentsContract.getDocumentId(uri) }.thenReturn(id)
                }
            contract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(treeUri, "indexed-lyrics") }
                .thenReturn(indexedDirectoryUri)
            val parsed = listOf(sourceUri, metadataUri, lyricsDirectoryUri, indexedDirectoryUri, originalUri, replacementUri)
                .associateBy(Uri::toString)
            mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
                uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { parsed[it.getArgument<String>(0)] }
                block()
            }
        }
    }

    private fun entry(name: String, uri: Uri): DownloadLibraryEntry = mock(DownloadLibraryEntry::class.java).also {
        doReturn(name).`when`(it).name
        doReturn(uri.toString()).`when`(it).reference
        doReturn(uri.toString()).`when`(it).mediaUri
    }

    private fun nameCursor(name: String): Cursor = mock(Cursor::class.java).also {
        doReturn(true).`when`(it).moveToFirst()
        doReturn(0).`when`(it).getColumnIndex(OpenableColumns.DISPLAY_NAME)
        doReturn(name).`when`(it).getString(0)
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
