package moe.ouom.neriplayer.data.local.media.curation

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.cacheDocumentChildren
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalCoverBaselineInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearCaches() {
        LocalMediaSupport.clearCoverLookupCache()
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `readable complete directories and a successful empty raw picture list confirm no artwork`() {
        val audio = audio()
        File(audio.parentFile, "Covers").mkdirs()
        File(audio.parentFile, "unrelated.jpg").writeText("another song")

        withRawPictures(song(audio), true) {
            assertTrue(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
        }
    }

    @Test
    fun `valid metadata without any cover reference allows confirmation`() {
        val audio = audio()
        File(audio.path + ".npmeta.json").writeText("""{"name":"Song","originalCoverUrl":""}""")

        withRawPictures(song(audio), true) {
            assertTrue(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
        }
    }

    @Test
    fun `raw picture read failure is unknown rather than no artwork`() {
        val song = song(audio())
        withRawPictures(song, null) {
            assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
        }
    }

    @Test
    fun `any raw picture including an undecodable one prevents an empty baseline`() {
        val song = song(audio())
        withRawPictures(song, false) {
            assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
        }
    }

    @Test
    fun `retained song cover references remain unknown even if the source has no artwork`() {
        val base = song(audio())
        for (song in listOf(base.copy(originalCoverUrl = "/missing/original.jpg"),
            base.copy(customCoverUrl = "/missing/custom.jpg"), base.copy(coverUrl = "https://cover.example/song.jpg"))) {
            withRawPictures(song, true) {
                assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
            }
        }
    }

    @Test
    fun `a raw inspection cancellation is propagated`() {
        val song = song(audio())
        val cancellation = CancellationException("cover baseline cancelled")
        mockStatic(Class.forName(RAW_READER_CLASS)).use { reader ->
            reader.`when`<Boolean?> { readRawLocalCoverPicturesEmpty(context, song) }.thenThrow(cancellation)

            assertSame(cancellation, runCatching {
                LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song)
            }.exceptionOrNull())
        }
    }

    @Test
    fun `a corrupt song-specific adjacent image prevents an empty baseline`() {
        val audio = audio()
        File(audio.parentFile, "Song.jpg").writeText("not an image")
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `a numbered hashed cover in a mixed-case Covers directory prevents an empty baseline`() {
        val audio = audio()
        File(audio.parentFile, "cOvErS").apply { mkdirs() }
            .resolve("Song-a123bcde (1).PNG").writeText("not an image")
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `an unreadable folder-cover candidate is not treated as absent`() {
        val audio = audio()
        File(audio.parentFile, "front.png").mkdirs()
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `malformed metadata does not confirm that its cover reference is absent`() {
        val audio = audio()
        File(audio.path + ".npmeta.json").writeText("not valid json")
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `an unreadable metadata candidate does not confirm absence`() {
        val audio = audio()
        File(audio.path + ".npmeta.json").mkdirs()
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `every persisted cover reference prevents an empty baseline even if its target is gone`() {
        val audio = audio()
        for (field in listOf("coverPath", "coverUrl", "originalCoverUrl", "customCoverUrl")) {
            File(audio.path + ".npmeta.json").writeText("""{"$field":"/missing/original.jpg"}""")
            assertFileSourceUnknown(audio)
        }
    }

    @Test
    fun `a missing audio source leaves its original artwork unknown`() {
        assertFileSourceUnknown(File(temporaryFolder.root, "gone/Song.flac"))
    }

    @Test
    @Config(sdk = [30])
    fun `scoped storage visibility cannot prove that a shared directory has no artwork`() {
        val audio = audio()
        assertFileSourceUnknown(audio)
    }

    @Test
    fun `legacy MediaStore sources use a freshly mapped readable file to confirm no artwork`() {
        withMediaStoreSource(audio(), providerFailure = null) { context, song ->
            assertTrue(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
        }
    }

    @Test
    fun `failed MediaStore queries cannot confirm absence through a retained local path`() {
        withMediaStoreSource(audio(), providerFailure = SecurityException("media permission revoked")) { context, song ->
            assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
        }
    }

    @Test
    fun `a complete fresh SAF listing with opaque ids can confirm no artwork`() = withSaf { fixture ->
        assertTrue(fixture.inspect())
        assertTrue(fixture.parentQueries > 0)
        assertTrue(fixture.coverQueries > 0)
    }

    @Test
    fun `a new SAF cover cannot be hidden by a previous complete empty cache`() = withSaf { fixture ->
        LocalMediaSupport.cacheDocumentChildren(fixture.tree, ROOT_ID, listOf(fixture.audioChild, fixture.coversChild))
        LocalMediaSupport.cacheDocumentChildren(fixture.tree, COVERS_ID, emptyList())
        fixture.coverName = "Song.jpg"

        assertFalse(fixture.inspect())
        assertTrue(fixture.coverQueries > 0)
    }

    @Test
    fun `failed SAF parent enumeration remains unknown`() = withSaf { fixture ->
        fixture.failedParentListing = true
        assertFalse(fixture.inspect())
    }

    @Test
    fun `SAF parent permission denial remains unknown`() = withSaf { fixture ->
        fixture.parentFailure = SecurityException("parent permission revoked")
        assertFalse(fixture.inspect())
    }

    @Test
    fun `SAF parent query cancellation is propagated`() = withSaf { fixture ->
        val cancellation = CancellationException("parent query cancelled")
        fixture.parentFailure = cancellation
        assertSame(cancellation, runCatching { fixture.inspect() }.exceptionOrNull())
    }

    @Test
    fun `raw source query cancellation is propagated before native inspection`() = withSaf(mockRaw = false) { fixture ->
        val cancellation = CancellationException("source query cancelled")
        fixture.sourceQueryFailure = cancellation
        assertSame(cancellation, runCatching { fixture.inspect() }.exceptionOrNull())
    }

    @Test
    fun `raw descriptor open cancellation is propagated before native inspection`() = withSaf(mockRaw = false) { fixture ->
        val cancellation = CancellationException("descriptor open cancelled")
        fixture.descriptorFailure = cancellation
        assertSame(cancellation, runCatching { fixture.inspect() }.exceptionOrNull())
    }

    @Test
    fun `failed SAF Covers enumeration remains unknown`() = withSaf { fixture ->
        fixture.failedCoverListing = true
        assertFalse(fixture.inspect())
    }

    @Test
    fun `a SAF listing still loading is not proof of an empty directory`() = withSaf { fixture ->
        fixture.parentLoading = true
        assertFalse(fixture.inspect())
    }

    @Test
    fun `a SAF row without a document id makes artwork absence unknown`() = withSaf { fixture ->
        fixture.malformedParentColumn = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        assertFalse(fixture.inspect())
    }

    @Test
    fun `a SAF row without a display name makes artwork absence unknown`() = withSaf { fixture ->
        fixture.malformedParentColumn = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        assertFalse(fixture.inspect())
    }

    @Test
    fun `a SAF row without a mime type makes artwork absence unknown`() = withSaf { fixture ->
        fixture.malformedParentColumn = DocumentsContract.Document.COLUMN_MIME_TYPE
        assertFalse(fixture.inspect())
    }

    @Test
    fun `an unreadable SAF metadata document remains unknown`() = withSaf { fixture ->
        fixture.metadata = "{}"
        fixture.metadataUnreadable = true
        assertFalse(fixture.inspect())
    }

    @Test
    fun `a malformed SAF metadata document remains unknown`() = withSaf { fixture ->
        fixture.metadata = "broken json"
        assertFalse(fixture.inspect())
    }

    private fun assertFileSourceUnknown(audio: File) {
        val song = song(audio)
        withRawPictures(song, true) {
            assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song))
        }
    }

    private fun audio() = temporaryFolder.newFile("Song.flac").apply { writeText("audio") }

    private fun song(audio: File) = SongItem(
        1L, "Song", "Artist", "__local_files__", 0L, 1000L, null,
        mediaUri = Uri.fromFile(audio).toString(), localFilePath = audio.path, localFileName = audio.name
    )

    private fun <T> withRawPictures(song: SongItem, empty: Boolean?, block: () -> T): T =
        mockStatic(Class.forName(RAW_READER_CLASS)).use { reader ->
            reader.`when`<Boolean?> { readRawLocalCoverPicturesEmpty(context, song) }.thenReturn(empty)
            block()
        }

    private fun withMediaStoreSource(audio: File, providerFailure: RuntimeException?, block: (Context, SongItem) -> Unit) {
        val resolver = mock(ContentResolver::class.java)
        val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
        val song = song(audio).copy(
            mediaUri = "content://media/external/audio/media/41",
            localFilePath = if (providerFailure == null) "/stale/old-song.flac" else audio.path
        )
        var queries = 0
        doAnswer {
            queries++
            providerFailure?.let { throw it }
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, MEDIA_STORE_DATA_COLUMN, MediaStore.MediaColumns.MIME_TYPE)).apply {
                addRow(arrayOf(audio.name, audio.absolutePath, "audio/flac"))
            }
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        mockStatic(Class.forName(RAW_READER_CLASS)).use { raw ->
            raw.`when`<Boolean?> { readRawLocalCoverPicturesEmpty(context, song) }.thenReturn(true)
            block(context, song)
            assertTrue(queries > 0)
        }
    }

    private fun withSaf(mockRaw: Boolean = true, block: (SafFixture) -> Unit) {
        val fixture = SafFixture()
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }.thenReturn(ROOT_ID)
            documents.`when`<String> { DocumentsContract.getDocumentId(any()) }.thenAnswer { invocation ->
                fixture.children.firstOrNull { it.uri == invocation.getArgument<Uri>(0).toString() }?.documentId
            }
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(any()) }.thenReturn(true)
            documents.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID) }.thenReturn(fixture.tree)
            documents.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), anyString()) }
                .thenAnswer { fixture.documentUri(it.getArgument(1)) }
            documents.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), anyString()) }
                .thenAnswer { fixture.childrenUri(it.getArgument(1)) }
            documents.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(any(), any()) }
                .thenAnswer { mock(DocumentsContract.Path::class.java).also { path ->
                    doReturn(listOf(ROOT_ID, AUDIO_ID)).`when`(path).path
                } }
            mockStatic(DocumentFile::class.java).use {
                if (mockRaw) mockStatic(Class.forName(RAW_READER_CLASS)).use { raw ->
                    raw.`when`<Boolean?> { readRawLocalCoverPicturesEmpty(fixture.context, fixture.song) }.thenReturn(true)
                    block(fixture)
                } else block(fixture)
            }
        }
    }

    private class SafFixture {
        val tree: Uri = Uri.parse("content://$AUTHORITY/tree/${Uri.encode(ROOT_ID)}")
        val context = mock(Context::class.java)
        private val resolver = mock(ContentResolver::class.java)
        val audioChild = child(AUDIO_ID, "Song.flac")
        val coversChild = child(COVERS_ID, "Covers", directory = true)
        val song = SongItem(2L, "Song", "Artist", "__local_files__", 0L, 1000L, null,
            mediaUri = audioChild.uri, localFileName = "Song.flac")
        var coverName: String? = null
        var metadata: String? = null
        var metadataUnreadable = false
        var failedParentListing = false
        var failedCoverListing = false
        var parentLoading = false
        var malformedParentColumn: String? = null
        var parentFailure: RuntimeException? = null
        var sourceQueryFailure: RuntimeException? = null
        var descriptorFailure: RuntimeException? = null
        var parentQueries = 0
        var coverQueries = 0
        val children: List<LocalMediaSupport.DocumentChild>
            get() = listOf(audioChild, coversChild) + listOfNotNull(
                coverName?.let { child("opaque-image", it) },
                metadata?.let { child("opaque-metadata", "Song.flac.npmeta.json") }
            )

        init {
            doReturn(resolver).`when`(context).contentResolver
            doAnswer { invocation ->
                when (invocation.getArgument<Uri>(0).toString()) {
                    childrenUri(ROOT_ID).toString() -> {
                        parentQueries++
                        parentFailure?.let { throw it }
                        if (failedParentListing) null else cursor(
                            children.filter { it.documentId != "opaque-image" }, parentLoading, malformedParentColumn
                        )
                    }
                    childrenUri(COVERS_ID).toString() -> {
                        coverQueries++
                        if (failedCoverListing) null else cursor(children.filter { it.documentId == "opaque-image" })
                    }
                    else -> {
                        sourceQueryFailure?.let { throw it }
                        cursor(children.filter { it.uri == invocation.getArgument<Uri>(0).toString() })
                    }
                }
            }.`when`(resolver).query(any(), any(), any(), any(), any())
            doAnswer {
                if (metadataUnreadable) throw FileNotFoundException("metadata unreadable")
                ByteArrayInputStream(metadata.orEmpty().toByteArray())
            }.`when`(resolver).openInputStream(any())
            doAnswer { descriptorFailure?.let { throw it } }
                .`when`(resolver).openFileDescriptor(any(), anyString())
        }

        fun inspect() = LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song)

        fun documentUri(id: String): Uri = Uri.parse("$tree/document/${Uri.encode(id)}")

        fun childrenUri(id: String): Uri = Uri.parse("${documentUri(id)}/children")

        private fun child(id: String, name: String, directory: Boolean = false) =
            LocalMediaSupport.DocumentChild(id, name, directory, documentUri(id).toString())

        private fun cursor(rows: List<LocalMediaSupport.DocumentChild>, loading: Boolean = false, malformedColumn: String? = null) =
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
                rows.forEach { row -> addRow(arrayOf(row.documentId, row.displayName,
                    if (row.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream")) }
                malformedColumn?.let { column ->
                    val malformed = arrayOf<Any?>("opaque-unclassified", "unclassified-entry", "application/octet-stream")
                    malformed[getColumnIndex(column)] = null
                    addRow(malformed)
                }
                if (loading) extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
    }

    private companion object {
        const val RAW_READER_CLASS = "moe.ouom.neriplayer.data.local.media.curation.LocalCoverBaselineInspectorKt"
        const val AUTHORITY = "test.local.cover-baseline"
        const val ROOT_ID = "opaque-root@1"
        const val AUDIO_ID = "opaque-track@7"
        const val COVERS_ID = "opaque-folder@9"
        const val MEDIA_STORE_DATA_COLUMN = "_data"
    }
}
