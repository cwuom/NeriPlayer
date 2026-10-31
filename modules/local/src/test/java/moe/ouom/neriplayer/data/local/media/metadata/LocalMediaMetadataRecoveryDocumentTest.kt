package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mockStatic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaMetadataRecoveryDocumentTest {
    @get:Rule
    val directory = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var audio: File
    private lateinit var provider: RecoveryDocumentProvider
    private val channels = IdentityHashMap<FileDescriptor, FileChannel>()
    private val borrowedStreams = mutableListOf<Closeable>()
    private val reference = "content://metadata.recovery.test/audio"

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        ShadowLog.stream = System.out
        val stagingParent = directory.newFolder("no-backup")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = stagingParent
            override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
        }
        audio = directory.newFile("song.flac").apply { writeText("original") }
        provider = RecoveryDocumentProvider(audio)
        provider.attachInfo(context, ProviderInfo().apply { authority = "metadata.recovery.test" })
        ShadowContentResolver.registerProviderInternal("metadata.recovery.test", provider)
    }

    private fun withDescriptors(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) {
        // Robolectric 的 fstat(FileDescriptor) 丢失文件身份，桥接这三个系统调用到同一个真实文件描述符
        mockStatic(Os::class.java, CALLS_REAL_METHODS).use { descriptorOs ->
            descriptorOs.`when`<StructStat> { Os.fstat(any()) }.thenAnswer { call ->
                val view = provider.files.getValue(call.getArgument(0))
                val file = view.file
                StructStat(
                    view.device, view.inode,
                    (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt(),
                    1, 0, 0, 0, file.length(), 0, file.lastModified() / 1_000, 0, 4_096, 0
                )
            }
            descriptorOs.`when`<Long> { Os.lseek(any(), anyLong(), eq(OsConstants.SEEK_SET)) }.thenAnswer { call ->
                val descriptor = call.getArgument<FileDescriptor>(0)
                val channel = channels.getOrPut(descriptor) {
                    FileInputStream(descriptor).also { borrowedStreams += it }.channel
                }
                channel.position(call.getArgument<Long>(1)).position()
            }
            descriptorOs.`when`<Unit> { Os.ftruncate(any(), anyLong()) }.thenAnswer { call ->
                val descriptor = call.getArgument<FileDescriptor>(0)
                FileOutputStream(descriptor).also { borrowedStreams += it }.channel.truncate(call.getArgument(1))
                null
            }
            try {
                block()
            } finally {
                borrowedStreams.asReversed().forEach { runCatching(it::close) }
                channels.clear()
                borrowedStreams.clear()
            }
        }
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        ShadowLog.stream = null
    }

    @Test
    fun `document recovery completes an interrupted write in the original instance`() = withDescriptors {
        val record = prepare()
        audio.writeText("up")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated audio", audio.readText())
        assertFalse(record.journalFile.exists())
        assertEquals(listOf("rw"), provider.writeModes)
    }

    @Test
    fun `a document remapped when opened for writing is preserved before truncation`() = withDescriptors {
        val record = prepare()
        val external = directory.newFile("external.flac").apply { writeText("up") }
        provider.beforeWrite = { provider.audio = external }
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("up", external.readText())
        assertEquals("original", audio.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
        assertEquals(listOf("rw"), provider.writeModes)
    }

    @Test
    fun `a provider without rw keeps original bytes and recovery copies`() = withDescriptors {
        val record = prepare()
        provider.allowReadWrite = false

        assertFalse(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
            context, reference, record.updatedFile, recoveryRecord = record
        ))
        assertEquals("original", audio.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
        assertEquals(listOf("rw"), provider.writeModes)
    }

    @Test
    fun `recorded read and write filesystem views can recover the same interrupted audio`() = withDescriptors {
        provider.readDeviceOffset = 1
        provider.writeDeviceOffset = 2
        val record = prepare()
        assertTrue(record.originalFileIdentity != record.originalWritableFileIdentity)
        audio.writeText("up")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated audio", audio.readText())
        assertFalse(record.journalFile.exists())
    }

    @Test
    fun `another filesystem with a colliding inode cannot claim the recorded audio`() = withDescriptors {
        val record = prepare()
        val originalInode = (Files.getAttribute(audio.toPath(), "unix:ino") as Number).toLong()
        val external = directory.newFile("another-volume.flac").apply { writeText("up") }
        provider.beforeWrite = {
            provider.audio = external
            provider.readDeviceOffset = 1
            provider.writeDeviceOffset = 1
            provider.forcedInode = originalInode
        }
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("up", external.readText())
        assertEquals("original", audio.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
        assertEquals(listOf("rw"), provider.writeModes)
    }

    @Test
    fun `sidecar writes and reloaded rollback preserve exact bytes across verified filesystem views`() = withDescriptors {
        provider.readDeviceOffset = 1
        provider.writeDeviceOffset = 2
        val original = audio.readBytes()
        val updated = "updated sidecar contents".toByteArray()
        val transaction = LocalMediaCompanionTransaction(context, directory.newFile("source.flac").absolutePath)

        assertTrue(transaction.write(reference, updated))
        assertArrayEquals(updated, audio.readBytes())
        val record = requireNotNull(transaction.record)
        val entry = record.companions.single()
        val device = (Files.getAttribute(audio.toPath(), "unix:dev") as Number).toLong()
        val inode = (Files.getAttribute(audio.toPath(), "unix:ino") as Number).toLong()
        assertEquals("${device + 1}:$inode", entry.fileIdentity)
        assertEquals("${device + 2}:$inode", entry.writableFileIdentity)
        val persisted = JSONObject(record.journalFile.readText()).getJSONArray("companions").getJSONObject(0)
        assertEquals(entry.writableFileIdentity, persisted.getString("writableFileIdentity"))

        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertArrayEquals(original, audio.readBytes())
        assertFalse(record.journalFile.exists())
    }

    private fun prepare(): LocalMetadataRecoveryRecord {
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        context.contentResolver.openFileDescriptor(Uri.parse(reference), "r")!!.use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            assertTrue("provider descriptor must be a regular file: mode=${stat.st_mode}", OsConstants.S_ISREG(stat.st_mode))
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            assertEquals("original", ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { String(it.readBytes()) })
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            assertEquals(
                LocalMediaMetadataRecoveryStore.sha256(audio),
                ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use(LocalMediaMetadataRecoveryStore::sha256)
            )
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        }
        val record = LocalMediaMetadataRecoveryStore.markReplacing(
            LocalMediaMetadataRecoveryStore.begin(
                context, reference,
                File(staging, "backup.flac").apply { writeText("original") },
                File(staging, "updated.flac").apply { writeText("updated audio") },
                null
            )
        )
        assertNotNull("provider original instance identity must be available before fault injection", record.originalFileIdentity)
        assertNotNull("provider writable instance identity must be verified before fault injection", record.originalWritableFileIdentity)
        provider.writeModes.clear()
        return record
    }
}

private class RecoveryDocumentProvider(var audio: File) : ContentProvider() {
    var allowReadWrite = true
    var beforeWrite: () -> Unit = {}
    val writeModes = mutableListOf<String>()
    var readDeviceOffset = 0L
    var writeDeviceOffset = 0L
    var forcedInode: Long? = null
    val files = IdentityHashMap<FileDescriptor, RecoveryDescriptorView>()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            writeModes += mode
            if (mode != "rw" || !allowReadWrite) throw FileNotFoundException("non truncating writes unavailable")
            beforeWrite()
        }
        val access = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE
        val device = (Files.getAttribute(audio.toPath(), "unix:dev") as Number).toLong() +
            if (mode == "r") readDeviceOffset else writeDeviceOffset
        val inode = forcedInode ?: (Files.getAttribute(audio.toPath(), "unix:ino") as Number).toLong()
        return ParcelFileDescriptor.open(audio, access).also {
            files[it.fileDescriptor] = RecoveryDescriptorView(audio, device, inode)
        }
    }

    override fun getType(uri: Uri): String = "audio/flac"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private data class RecoveryDescriptorView(val file: File, val device: Long, val inode: Long)
