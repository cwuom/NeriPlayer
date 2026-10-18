package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic

class LocalMediaLyricTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val documents = linkedMapOf<String, Document>()
    private val uris = linkedMapOf<String, Uri>()
    private val descriptors = IdentityHashMap<ParcelFileDescriptor, File>()
    private val handles = IdentityHashMap<FileDescriptor, File>()
    private val treeUri = uri("content://$AUTHORITY/tree/root")
    private val rootChildren = uri("content://$AUTHORITY/tree/root/document/root/children")
    private val lyricChildren = uri("content://$AUTHORITY/tree/root/document/lyrics/children")
    private var createFailure: Throwable? = null
    private var writeFailure: Throwable? = null
    private var failureTarget = "translated"
    private var beforeWriteFailure: (() -> Unit)? = null
    private var parentListingFails = false
    private var providerWrites = 0
    private var parentQueries = 0

    @After
    fun clearCaches() {
        LocalMediaSupport.invalidateSafReadCaches()
        LocalMediaSupport.clearLyricsLookupCache()
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `a later creation failure removes the earlier newly created lyric`() = runBlocking(Dispatchers.IO) {
        fixture {
            val denial = SecurityException("second create permission revoked")
            createFailure = denial

            val error = runCatching { LocalMediaSupport.writeLocalLyricsSidecars(context, song()) }.exceptionOrNull()

            assertPropagated(denial, error)
            assertFalse(File(temporaryFolder.root, "Lyrics/Song.lrc").exists())
        }
    }

    @Test
    fun `provider creation cancellation propagates and removes earlier creations`() = runBlocking(Dispatchers.IO) {
        fixture {
            val cancellation = CancellationException("second create cancelled")
            createFailure = cancellation

            val error = runCatching { LocalMediaSupport.writeLocalLyricsSidecars(context, song()) }.exceptionOrNull()

            assertPropagated(cancellation, error)
            assertFalse(File(temporaryFolder.root, "Lyrics/Song.lrc").exists())
        }
    }

    @Test
    fun `cancelling a translation write restores an already written adjacent original`() = runBlocking(Dispatchers.IO) {
        fixture {
            val original = add("original", "Song.lrc", "root", "old original").file!!
            val translation = add("translated", "Song_trans.lrc", "lyrics", "old translation").file!!
            val cancellation = CancellationException("translation write cancelled")
            writeFailure = cancellation

            val error = runCatching { LocalMediaSupport.writeLocalLyricsSidecars(context, song()) }.exceptionOrNull()

            assertPropagated(cancellation, error)
            assertEquals("old original", original.readText())
            assertEquals("old translation", translation.readText())
        }
    }

    @Test
    fun `rollback keeps an external rewrite after a later provider failure`() = runBlocking(Dispatchers.IO) {
        fixture {
            val original = add("original", "Song.lrc", "root", "old original").file!!
            val translation = add("translated", "Song_trans.lrc", "lyrics", "old translation").file!!
            val denial = SecurityException("translation permission revoked")
            writeFailure = denial
            beforeWriteFailure = { original.writeText("external owner") }

            val error = runCatching { LocalMediaSupport.writeLocalLyricsSidecars(context, song()) }.exceptionOrNull()

            assertPropagated(denial, error)
            assertEquals("external owner", original.readText())
            assertEquals("old translation", translation.readText())
        }
    }

    @Test
    fun `metadata failure restores all lyrics and the previous metadata`() = runBlocking(Dispatchers.IO) {
        fixture {
            val original = add("original", "Song.lrc", "root", "old original").file!!
            val translation = add("translated", "Song_trans.lrc", "lyrics", "old translation").file!!
            val metadata = add("metadata", "Song.flac.npmeta.json", "root", "{\"customName\":\"keep me\"}").file!!
            val previous = metadata.readText()
            val failure = IOException("metadata write failed")
            failureTarget = "metadata"
            writeFailure = failure

            val error = runCatching {
                LocalMediaSupport.writeLocalLyricsSidecars(context, song(), persistMetadata = true)
            }.exceptionOrNull()

            assertPropagated(failure, error)
            assertEquals("old original", original.readText())
            assertEquals("old translation", translation.readText())
            assertEquals(previous, metadata.readText())
        }
    }

    @Test
    fun `lyrics and full metadata commit together while preserving unrelated fields`() = runBlocking(Dispatchers.IO) {
        fixture {
            val original = add("original", "Song.lrc", "root", "old original").file!!
            val translation = add("translated", "Song_trans.lrc", "lyrics", "old translation").file!!
            val metadata = add("metadata", "Song.flac.npmeta.json", "root", "{\"unrelated\":17}").file!!

            assertTrue(LocalMediaSupport.writeLocalLyricsSidecars(context, song(), persistMetadata = true))

            assertEquals("new original", original.readText())
            assertEquals("new translation", translation.readText())
            val json = JSONObject(metadata.readText())
            assertEquals("Song", json.getString("name"))
            assertEquals("new original", json.getString("matchedLyric"))
            assertEquals("new translation", json.getString("matchedTranslatedLyric"))
            assertEquals(17, json.getInt("unrelated"))
        }
    }

    @Test
    fun `failed mutation enumeration never writes a renamed cached lyric`() = runBlocking(Dispatchers.IO) {
        fixture {
            val old = add("original", "Song.lrc", "root", "external original")
            add("translated", "Song_trans.lrc", "lyrics", "old translation")
            LocalMediaSupport.cacheDocumentChildren(treeUri, "root", documents.values.filter { it.parent == "root" }.map {
                LocalMediaSupport.DocumentChild(it.id, it.name, it.file == null, it.uri.toString())
            })
            val renamed = File(temporaryFolder.root, "Other.lrc")
            check(old.file!!.renameTo(renamed))
            documents["original"] = old.copy(name = "Other.lrc", file = renamed)
            parentListingFails = true

            assertFalse(LocalMediaSupport.writeLocalLyricsSidecars(context, song()))

            assertEquals("external original", renamed.readText())
            assertEquals(0, providerWrites)
        }
    }

    @Test
    fun `failed mutation enumeration cannot acknowledge a lyric clear`() = runBlocking(Dispatchers.IO) {
        fixture {
            val original = add("original", "Song.lrc", "root", "external original").file!!
            LocalMediaSupport.cacheDocumentChildren(treeUri, "root", documents.values.filter { it.parent == "root" }.map {
                LocalMediaSupport.DocumentChild(it.id, it.name, it.file == null, it.uri.toString())
            })
            parentListingFails = true

            assertFalse(LocalMediaSupport.writeLocalLyricsSidecars(
                context, song().copy(matchedLyric = "", matchedTranslatedLyric = "")
            ))

            assertEquals("external original", original.readText())
            assertEquals(0, providerWrites)
        }
    }

    @Test
    fun `fresh parent enumeration is reused when creating missing lyrics`() = runBlocking(Dispatchers.IO) {
        fixture {
            assertTrue(LocalMediaSupport.writeLocalLyricsSidecars(context, song()))

            assertEquals(1, parentQueries)
            assertEquals("new original", File(temporaryFolder.root, "Lyrics/Song.lrc").readText())
            assertEquals("new translation", File(temporaryFolder.root, "Lyrics/Song_trans.lrc").readText())
        }
    }

    private suspend fun fixture(block: suspend () -> Unit) {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        doReturn(context).`when`(context).applicationContext
        doReturn(resolver).`when`(context).contentResolver
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        add("root", "Music", null)
        add("source", "Song.flac", "root", "audio")
        add("lyrics", "Lyrics", "root")
        doAnswer { invocation ->
            val uri = invocation.getArgument<Uri>(0)
            if (uri == rootChildren) parentQueries++
            if (uri == rootChildren && parentListingFails) return@doAnswer null
            val rows = when (uri) {
                rootChildren -> documents.values.filter { it.parent == "root" && it.exists() }
                lyricChildren -> documents.values.filter { it.parent == "lyrics" && it.exists() }
                else -> documents.values.filter { it.uri == uri && it.exists() }
            }
            cursor(rows)
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        doAnswer { invocation -> document(invocation.getArgument(0)).file!!.inputStream() }
            .`when`(resolver).openInputStream(any())
        doAnswer { invocation ->
            val target = document(invocation.getArgument(0))
            failWrite(target)
            providerWrites++
            target.file!!.outputStream()
        }.`when`(resolver).openOutputStream(any(), anyString())
        doAnswer { invocation ->
            val target = document(invocation.getArgument(0))
            if (invocation.getArgument<String>(1) != "r") failWrite(target)
            val file = target.file?.takeIf(File::exists) ?: throw FileNotFoundException(target.name)
            descriptor(file)
        }.`when`(resolver).openFileDescriptor(any(), anyString())
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { parsed ->
            parsed.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { uris[it.getArgument<String>(0)] }
            mockStatic(DocumentsContract::class.java).use { contract ->
                contract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }.thenReturn("root")
                contract.`when`<String> { DocumentsContract.getDocumentId(any()) }
                    .thenAnswer { document(it.getArgument(0)).id }
                contract.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(anyString(), anyString()) }.thenReturn(treeUri)
                contract.`when`<Boolean> { DocumentsContract.isTreeUri(any()) }.thenReturn(true)
                contract.`when`<Boolean> { DocumentsContract.isDocumentUri(any(), any()) }.thenReturn(true)
                contract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), anyString()) }
                    .thenAnswer { documents.getValue(it.getArgument(1)).uri }
                contract.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), anyString()) }
                    .thenAnswer { if (it.getArgument<String>(1) == "root") rootChildren else lyricChildren }
                contract.`when`<DocumentsContract.Path> { DocumentsContract.findDocumentPath(any(), any()) }.thenAnswer {
                    val target = document(it.getArgument(1))
                    mock(DocumentsContract.Path::class.java).also { path ->
                        doReturn(listOfNotNull(target.parent, target.id)).`when`(path).path
                    }
                }
                contract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), anyString(), anyString()) }.thenAnswer {
                    val name = it.getArgument<String>(3)
                    if (name == "Song_trans.lrc") createFailure?.let { failure -> throw failure }
                    val id = when (name) {
                        "Song.lrc" -> "original"
                        "Song_trans.lrc" -> "translated"
                        else -> "metadata"
                    }
                    add(id, name, document(it.getArgument(1)).id, "").uri
                }
                contract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }.thenAnswer {
                    document(it.getArgument(1)).file!!.delete()
                }
                mockStatic(DocumentFile::class.java).use {
                    withDescriptors { block() }
                }
            }
        }
    }

    private suspend fun withDescriptors(block: suspend () -> Unit) {
        mockStatic(Os::class.java).use { os ->
            os.`when`<StructStat> { Os.fstat(any()) }.thenAnswer { stat(handles.getValue(it.getArgument(0))) }
            os.`when`<Unit> { Os.ftruncate(any(), anyLong()) }.thenAnswer {
                providerWrites++
                handles.getValue(it.getArgument(0)).writeBytes(ByteArray(0))
                null
            }
            mockStatic(OsConstants::class.java).use { constants ->
                constants.`when`<Boolean> { OsConstants.S_ISREG(anyInt()) }.thenReturn(true)
                mockConstruction(ParcelFileDescriptor.AutoCloseInputStream::class.java) { stream, construction ->
                    val source = ByteArrayInputStream(descriptors.getValue(construction.arguments()[0] as ParcelFileDescriptor).readBytes())
                    doAnswer { source.read() }.`when`(stream).read()
                    doAnswer { source.read(it.getArgument<ByteArray>(0)) }.`when`(stream).read(any())
                    doAnswer { source.read(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
                        .`when`(stream).read(any(), anyInt(), anyInt())
                    doAnswer { source.available() }.`when`(stream).available()
                }.use {
                    mockConstruction(ParcelFileDescriptor.AutoCloseOutputStream::class.java) { stream, construction ->
                        val target = descriptors.getValue(construction.arguments()[0] as ParcelFileDescriptor)
                        val buffer = ByteArrayOutputStream()
                        doAnswer { buffer.write(it.getArgument<ByteArray>(0)) }.`when`(stream).write(any<ByteArray>())
                        doAnswer { target.writeBytes(buffer.toByteArray()) }.`when`(stream).flush()
                    }.use { block() }
                }
            }
        }
    }

    private fun failWrite(target: Document) {
        if (target.id != failureTarget) return
        val failure = writeFailure ?: return
        writeFailure = null
        beforeWriteFailure?.invoke()
        throw failure
    }

    private fun assertPropagated(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    private fun descriptor(file: File): ParcelFileDescriptor = mock(ParcelFileDescriptor::class.java).also {
        val handle = FileDescriptor()
        doReturn(it).`when`(it).dup()
        doReturn(handle).`when`(it).fileDescriptor
        descriptors[it] = file
        handles[handle] = file
    }

    private fun stat(file: File): StructStat = mock(StructStat::class.java).also {
        StructStat::class.java.getField("st_ino").apply { isAccessible = true }.setLong(it, file.absolutePath.hashCode().toLong())
    }

    private fun add(id: String, name: String, parent: String?, content: String? = null): Document {
        val file = content?.let {
            val directory = if (parent == "lyrics") File(temporaryFolder.root, "Lyrics").apply { mkdirs() } else temporaryFolder.root
            File(directory, name).apply { writeText(content) }
        }
        return Document(id, name, parent, uri("content://$AUTHORITY/tree/root/document/$id"), file)
            .also { documents[id] = it }
    }

    private fun uri(value: String): Uri = mock(Uri::class.java).also {
        doReturn("content").`when`(it).scheme
        doReturn(AUTHORITY).`when`(it).authority
        doReturn(value).`when`(it).toString()
        uris[value] = it
    }

    private fun document(uri: Uri): Document = documents.values.single { it.uri == uri }

    private fun cursor(rows: List<Document>): Cursor = mock(Cursor::class.java).also { cursor ->
        var position = -1
        doAnswer { ++position < rows.size }.`when`(cursor).moveToNext()
        doAnswer { position = 0; rows.isNotEmpty() }.`when`(cursor).moveToFirst()
        doAnswer { when (it.getArgument<String>(0)) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
            DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
            else -> -1
        } }.`when`(cursor).getColumnIndex(anyString())
        doAnswer { when (it.getArgument<Int>(0)) {
            0 -> rows[position].id
            1 -> rows[position].name
            else -> if (rows[position].file == null) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
        } }.`when`(cursor).getString(anyInt())
    }

    private fun song() = SongItem(
        id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 1000L, coverUrl = null, mediaUri = documents.getValue("source").uri.toString(),
        localFileName = "Song.flac", matchedLyric = "new original", matchedTranslatedLyric = "new translation"
    )

    private data class Document(val id: String, val name: String, val parent: String?, val uri: Uri, val file: File?) {
        fun exists(): Boolean = file?.exists() ?: true
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
