package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap

class LocalMediaCompanionWriteVerificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sources = IdentityHashMap<ParcelFileDescriptor, () -> ByteArray>()
    private val sinks = IdentityHashMap<ParcelFileDescriptor, (ByteArray) -> Unit>()
    private val resolver = mock(ContentResolver::class.java)
    private val documentUri = mock(Uri::class.java)
    private val stat = mock(StructStat::class.java)
    private var document = "original lyric".toByteArray()
    private var providerDropsWrites = false
    private var regularFiles = true
    private val readDescriptor = descriptor { document }
    private val writeDescriptor = mock(ParcelFileDescriptor::class.java).also { descriptor ->
        doReturn(descriptor).`when`(descriptor).dup()
        doReturn(FileDescriptor()).`when`(descriptor).fileDescriptor
        sources[descriptor] = { document }
        sinks[descriptor] = { bytes -> if (!providerDropsWrites) document = bytes }
    }
    private lateinit var context: Context
    private lateinit var music: File

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        context = mock(Context::class.java)
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        doReturn(resolver).`when`(context).contentResolver
        doReturn(readDescriptor).`when`(resolver).openFileDescriptor(documentUri, "r")
        doReturn(writeDescriptor).`when`(resolver).openFileDescriptor(documentUri, "rw")
        music = temporaryFolder.newFolder("music")
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `file and document companions are written, read back and journaled as written`() {
        val translated = File(music, "song_trans.lrc")
        val transaction = transaction()

        val written = withDescriptors(translated) { os ->
            listOf(
                transaction.write(translated.absolutePath, "translated".toByteArray(), created = true),
                transaction.write(DOCUMENT, "updated lyric".toByteArray())
            ).also { os.verify { Os.ftruncate(any(), eq(0L)) } }
        }

        assertEquals(listOf(true, true), written)
        assertEquals("translated", translated.readText())
        assertEquals("updated lyric", String(document))
        val record = requireNotNull(transaction.record)
        assertEquals(
            listOf(
                listOf<Any?>(translated.absolutePath, "WRITTEN", true, true, null, sha("translated")),
                listOf<Any?>(DOCUMENT, "WRITTEN", true, false, sha("original lyric"), sha("updated lyric"))
            ),
            record.companions.map { entry ->
                listOf(
                    entry.reference, entry.phase, entry.writeIdentityVerified,
                    entry.createdByTransaction, entry.originalSha256, entry.expectedSha256
                )
            }
        )
        assertEquals("original lyric", requireNotNull(record.companions.last().backupFile).readText())
        val journaled = JSONObject(record.journalFile.readText()).getJSONArray("companions")
        assertEquals(listOf("WRITTEN", "WRITTEN"), (0 until journaled.length()).map { journaled.getJSONObject(it).getString("phase") })
    }

    @Test
    fun `document writes that do not read back stay journaled as write intents`() {
        providerDropsWrites = true
        val transaction = transaction()

        val error = withDescriptors { expectFailure<IOException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) } }

        assertEquals("伴随文件写入读回或身份校验失败: $DOCUMENT", error.message)
        assertEquals("original lyric", String(document))
        val entry = requireNotNull(transaction.record).companions.single()
        assertEquals(listOf<Any?>("WRITE_INTENT", false), listOf(entry.phase, entry.writeIdentityVerified))
    }

    @Test
    fun `providers without non truncating write access preserve the companion`() {
        doThrow(FileNotFoundException("no rw")).`when`(resolver).openFileDescriptor(documentUri, "rw")
        doAnswer {
            document = ByteArray(0)
            writeDescriptor
        }.`when`(resolver).openFileDescriptor(documentUri, "rwt")
        val transaction = transaction()

        withDescriptors {
            expectFailure<IOException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) }
        }

        assertEquals("original lyric", String(document))
        verify(resolver, never()).openFileDescriptor(documentUri, "rwt")
    }

    @Test
    fun `legacy companion rollback never opens a truncating stream when rw is unavailable`() {
        val transaction = transaction()
        withDescriptors {
            transaction.write(DOCUMENT, "updated lyric".toByteArray())
            val written = requireNotNull(transaction.record)
            val legacy = written.copy(companions = written.companions.map { entry ->
                entry.copy(phase = null, intendedFile = null, previousSha256 = null, previousIdentity = null, writableFileIdentity = null)
            })
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)
            assertTrue(LocalMediaMetadataRecoveryStore.targetMatches(
                context, DOCUMENT, requireNotNull(legacy.companions.single().expectedSha256)
            ))
            val backup = requireNotNull(legacy.companions.single().backupFile)
            assertEquals("original lyric", backup.readText())
            doThrow(FileNotFoundException("no rw")).`when`(resolver).openFileDescriptor(documentUri, "rw")
            doAnswer {
                document = ByteArray(0)
                ByteArrayOutputStream()
            }.`when`(resolver).openOutputStream(documentUri, "rwt")

            assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, legacy))
            assertEquals("updated lyric", String(document))
            assertEquals("original lyric", backup.readText())
            assertTrue(legacy.journalFile.exists())
            verify(resolver, never()).openOutputStream(eq(documentUri), anyString())
        }
    }

    @Test
    fun `legacy companion rollback restores verified backup through non truncating rw access`() {
        val transaction = transaction()
        withDescriptors {
            transaction.write(DOCUMENT, "updated lyric".toByteArray())
            val written = requireNotNull(transaction.record)
            val legacy = written.copy(companions = written.companions.map { entry ->
                entry.copy(phase = null, intendedFile = null, previousSha256 = null, previousIdentity = null, writableFileIdentity = null)
            })
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)
            assertEquals("original lyric", requireNotNull(legacy.companions.single().backupFile).readText())

            assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, legacy))
            assertEquals("original lyric", String(document))
            assertFalse(legacy.journalFile.exists())
            verify(resolver, never()).openOutputStream(eq(documentUri), anyString())
        }
    }

    @Test
    fun `same inode on a different device cannot authorize a document write`() {
        var remapped = "another user's lyric".toByteArray()
        val remappedDescriptor = descriptor { remapped }.also { descriptor ->
            sinks[descriptor] = { bytes -> remapped = bytes }
        }
        doReturn(remappedDescriptor).`when`(resolver).openFileDescriptor(documentUri, "rw")
        val transaction = transaction()

        withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.fstat(remappedDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 2L))

            expectFailure<Exception> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) }

            assertEquals("another user's lyric", String(remapped))
            assertEquals("original lyric", String(document))
            os.verify({ Os.ftruncate(any(), anyLong()) }, never())
        }
    }

    @Test
    fun `same inode on a different device cannot authorize legacy rollback`() {
        var remapped = "updated lyric".toByteArray()
        val remappedDescriptor = descriptor { remapped }.also { descriptor ->
            sinks[descriptor] = { bytes -> remapped = bytes }
        }
        val transaction = transaction()
        withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.fstat(writeDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            transaction.write(DOCUMENT, "updated lyric".toByteArray())
            val written = requireNotNull(transaction.record)
            val legacy = written.copy(companions = written.companions.map { entry ->
                entry.copy(phase = null, intendedFile = null, previousSha256 = null, previousIdentity = null, writableFileIdentity = null)
            })
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)
            doReturn(remappedDescriptor).`when`(resolver).openFileDescriptor(documentUri, "rw")
            os.`when`<StructStat> { Os.fstat(remappedDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 2L))
            val backup = requireNotNull(legacy.companions.single().backupFile)
            assertEquals("original lyric", backup.readText())

            assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, legacy))

            assertEquals("updated lyric", String(remapped))
            assertEquals("updated lyric", String(document))
            assertEquals("original lyric", backup.readText())
            assertTrue(legacy.journalFile.exists())
            os.verify({ Os.ftruncate(eq(remappedDescriptor.fileDescriptor), anyLong()) }, never())
        }
    }

    @Test
    fun `verified fuse read and write views support writes and rollback`() {
        val transaction = transaction()
        withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.fstat(writeDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 2L))
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)

            assertTrue(transaction.write(DOCUMENT, "updated lyric".toByteArray()))
            assertEquals("updated lyric", String(document))
            assertTrue(transaction.rollback())
            assertEquals("original lyric", String(document))
        }
    }

    @Test
    fun `atomic file publication keeps read and path identities in their own views`() {
        val lyric = File(music, "song.lrc").apply { writeText("original lyric") }
        val transaction = transaction()
        withDescriptors(lyric) { os ->
            os.`when`<StructStat> { Os.fstat(any()) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.stat(anyString()) }.thenReturn(statWithInode(7L, 2L))

            assertTrue(transaction.write(lyric.absolutePath, "updated lyric".toByteArray()))

            val entry = requireNotNull(transaction.record).companions.single()
            assertEquals("1:7", entry.fileIdentity)
            assertEquals("2:7", entry.stagedIdentity)
            assertEquals("updated lyric", lyric.readText())
            assertTrue(transaction.rollback())
            assertEquals("original lyric", lyric.readText())
        }
    }

    @Test
    fun `repeated writes cannot authorize another write view with identical content`() {
        var remapped = "updated lyric".toByteArray()
        val remappedDescriptor = descriptor { remapped }.also { descriptor ->
            sinks[descriptor] = { bytes -> remapped = bytes }
        }
        val transaction = transaction()
        withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.fstat(writeDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 2L))
            transaction.write(DOCUMENT, "updated lyric".toByteArray())
            doReturn(remappedDescriptor).`when`(resolver).openFileDescriptor(documentUri, "rw")
            os.`when`<StructStat> { Os.fstat(remappedDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 3L))

            expectFailure<Exception> { transaction.write(DOCUMENT, "second lyric".toByteArray()) }

            assertEquals("updated lyric", String(remapped))
            assertEquals("updated lyric", String(document))
            assertEquals("original lyric", requireNotNull(transaction.record).companions.single().backupFile?.readText())
            os.verify({ Os.ftruncate(eq(remappedDescriptor.fileDescriptor), anyLong()) }, never())
        }
    }

    @Test
    fun `legacy created companion cannot delete a remapped read view with identical content`() {
        val transaction = transaction()
        withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            os.`when`<StructStat> { Os.fstat(writeDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 1L))
            transaction.write(DOCUMENT, "original lyric".toByteArray(), created = true)
            val written = requireNotNull(transaction.record)
            val legacy = written.copy(companions = written.companions.map { entry ->
                entry.copy(phase = null, intendedFile = null, previousSha256 = null, previousIdentity = null, writableFileIdentity = null)
            })
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)
            os.`when`<StructStat> { Os.fstat(readDescriptor.fileDescriptor) }.thenReturn(statWithInode(7L, 2L))
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<Boolean> { DocumentsContract.deleteDocument(resolver, documentUri) }.thenReturn(true)

                assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, legacy))

                assertEquals("original lyric", String(document))
                assertTrue(legacy.journalFile.exists())
                documents.verify({ DocumentsContract.deleteDocument(resolver, documentUri) }, never())
            }
        }
    }

    @Test
    fun `legacy created companion without a read identity is retained`() {
        val transaction = transaction()
        withDescriptors {
            transaction.write(DOCUMENT, "original lyric".toByteArray(), created = true)
            val written = requireNotNull(transaction.record)
            val legacy = written.copy(companions = written.companions.map { entry ->
                entry.copy(
                    phase = null, intendedFile = null, previousSha256 = null,
                    previousIdentity = null, fileIdentity = null, writableFileIdentity = null
                )
            })
            doAnswer { ByteArrayInputStream(document) }.`when`(resolver).openInputStream(documentUri)
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<Boolean> { DocumentsContract.deleteDocument(resolver, documentUri) }.thenReturn(true)

                assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, legacy))

                assertEquals("original lyric", String(document))
                assertTrue(legacy.journalFile.exists())
                documents.verify({ DocumentsContract.deleteDocument(resolver, documentUri) }, never())
            }
        }
    }

    @Test
    fun `providers that refuse write access fail before truncating the document`() {
        doReturn(null).`when`(resolver).openFileDescriptor(documentUri, "rw")
        val transaction = transaction()

        val error = withDescriptors { os ->
            expectFailure<IOException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) }
                .also { os.verify({ Os.ftruncate(any(), anyLong()) }, never()) }
        }

        assertEquals("伴随文件不可写", error.message)
        assertEquals("original lyric", String(document))
    }

    @Test
    fun `documents replaced by a non regular object are never truncated`() {
        doAnswer {
            regularFiles = false
            writeDescriptor
        }.`when`(resolver).openFileDescriptor(documentUri, "rw")
        val transaction = transaction()

        val error = withDescriptors { os ->
            expectFailure<IllegalStateException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) }
                .also { os.verify({ Os.ftruncate(any(), anyLong()) }, never()) }
        }

        assertEquals("伴随写入对象已改变", error.message)
        assertEquals("original lyric", String(document))
    }

    @Test
    fun `documents remapped to another regular file are verified before that file can be truncated`() {
        var remapped = "another user's lyric".toByteArray()
        val remappedHandle = FileDescriptor()
        val remappedDescriptor = mock(ParcelFileDescriptor::class.java).also { descriptor ->
            doReturn(descriptor).`when`(descriptor).dup()
            doReturn(remappedHandle).`when`(descriptor).fileDescriptor
            sources[descriptor] = { remapped }
            sinks[descriptor] = { bytes -> remapped = bytes }
        }
        // provider 已把稳定 URI 指向另一个普通文件，与真实 provider 一样，带 t 的模式在打开时就截断该文件
        listOf("w", "wt", "rw", "rwt").forEach { mode ->
            doAnswer {
                if ('t' in mode) remapped = ByteArray(0)
                remappedDescriptor
            }.`when`(resolver).openFileDescriptor(documentUri, mode)
        }
        val transaction = transaction()

        val error = withDescriptors { os ->
            os.`when`<StructStat> { Os.fstat(remappedHandle) }.thenReturn(statWithInode(2L))
            expectFailure<IllegalStateException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) }
                .also { os.verify({ Os.ftruncate(any(), anyLong()) }, never()) }
        }

        assertEquals("伴随写入对象已改变", error.message)
        assertEquals("another user's lyric", String(remapped))
        assertEquals("original lyric", String(document))
    }

    @Test
    fun `documents that cannot be opened for reading are not tracked`() {
        doReturn(null).`when`(resolver).openFileDescriptor(documentUri, "r")
        val transaction = transaction()

        val error = withDescriptors { expectFailure<IOException> { transaction.write(DOCUMENT, "updated lyric".toByteArray()) } }

        assertEquals("伴随文件不可读", error.message)
        assertEquals(emptyList<LocalMediaCompanionRecoveryEntry>(), requireNotNull(transaction.record).companions)
    }

    private fun transaction() = LocalMediaCompanionTransaction(context, File(music, "song.flac").absolutePath)

    private fun <T> withDescriptors(vararg files: File, block: (MockedStatic<Os>) -> T): T {
        val fileDescriptors = files.associateWith { file -> descriptor { file.readBytes() } }.toMutableMap()
        return mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(DOCUMENT) }.thenReturn(documentUri)
            mockStatic(ParcelFileDescriptor::class.java).use { descriptors ->
                descriptors.`when`<ParcelFileDescriptor> {
                    ParcelFileDescriptor.open(any(File::class.java), eq(ParcelFileDescriptor.MODE_READ_ONLY))
                }.thenAnswer { invocation ->
                    val file = invocation.getArgument<File>(0)
                    fileDescriptors.getOrPut(file) { descriptor { file.readBytes() } }
                }
                mockStatic(Os::class.java).use { os ->
                    os.`when`<StructStat> { Os.stat(anyString()) }.thenReturn(stat)
                    os.`when`<StructStat> { Os.fstat(any()) }.thenReturn(stat)
                    mockStatic(OsConstants::class.java).use { constants ->
                        constants.`when`<Boolean> { OsConstants.S_ISREG(anyInt()) }.thenAnswer { regularFiles }
                        mockConstruction(ParcelFileDescriptor.AutoCloseInputStream::class.java) { stream, construction ->
                            serve(stream, sources.getValue(construction.arguments()[0] as ParcelFileDescriptor)())
                        }.use {
                            mockConstruction(ParcelFileDescriptor.AutoCloseOutputStream::class.java) { stream, construction ->
                                collect(stream, sinks.getValue(construction.arguments()[0] as ParcelFileDescriptor))
                            }.use { block(os) }
                        }
                    }
                }
            }
        }
    }

    private fun descriptor(source: () -> ByteArray): ParcelFileDescriptor =
        mock(ParcelFileDescriptor::class.java).also { descriptor ->
            doReturn(descriptor).`when`(descriptor).dup()
            doReturn(FileDescriptor()).`when`(descriptor).fileDescriptor
            sources[descriptor] = source
        }

    private fun statWithInode(inode: Long, device: Long = 0L): StructStat = mock(StructStat::class.java).also { remappedStat ->
        StructStat::class.java.getField("st_ino").apply { isAccessible = true }.setLong(remappedStat, inode)
        StructStat::class.java.getField("st_dev").apply { isAccessible = true }.setLong(remappedStat, device)
    }

    private fun serve(stream: InputStream, bytes: ByteArray) {
        val source = ByteArrayInputStream(bytes)
        doAnswer { source.read() }.`when`(stream).read()
        doAnswer { source.read(it.getArgument<ByteArray>(0)) }.`when`(stream).read(any())
        doAnswer { source.read(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
            .`when`(stream).read(any(), anyInt(), anyInt())
        doAnswer { source.available() }.`when`(stream).available()
    }

    private fun collect(stream: OutputStream, sink: (ByteArray) -> Unit) {
        val buffer = ByteArrayOutputStream()
        doAnswer { buffer.write(it.getArgument<ByteArray>(0)) }.`when`(stream).write(any<ByteArray>())
        doAnswer { sink(buffer.toByteArray()) }.`when`(stream).flush()
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val DOCUMENT = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.lrc"
    }
}
