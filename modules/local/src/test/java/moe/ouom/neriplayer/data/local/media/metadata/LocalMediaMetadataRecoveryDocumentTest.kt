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
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaMetadataRecoveryDocumentTest {
    @get:Rule
    val directory = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var audio: File
    private lateinit var provider: RecoveryDocumentProvider
    private var beforeStat: (FileDescriptor) -> Unit = {}
    private var beforeTruncate: (FileDescriptor) -> Unit = {}
    private var beforePathReplace: () -> Unit = {}
    private var beforeJournalPublish: (String) -> Unit = {}
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

    private fun withDescriptors(block: suspend () -> Unit) = withMetadataRecoveryDescriptors {
        provider.trackDescriptor = { descriptor, file, offset, inode -> track(descriptor, file, offset, inode) }
        beforeStat = { this@LocalMediaMetadataRecoveryDocumentTest.beforeStat(it) }
        beforeTruncate = { this@LocalMediaMetadataRecoveryDocumentTest.beforeTruncate(it) }
        beforePathReplace = { source, target ->
            if (target == audio.toPath()) this@LocalMediaMetadataRecoveryDocumentTest.beforePathReplace()
            if (target.fileName.toString().startsWith("recovery-") && target.fileName.toString().endsWith(".json")) {
                beforeJournalPublish(JSONObject(source.toFile().readText()).getString("stage"))
            }
        }
        block()
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
        assertEquals(listOf("rw", "rw"), provider.writeModes)
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
    fun `rollback preserves a new document instance even when it matches the updated hash`() = withDescriptors {
        val record = prepare()
        val external = directory.newFile("same-update.flac").apply { writeText("updated audio") }
        provider.audio = external

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))

        assertEquals("updated audio", external.readText())
        assertEquals("original", audio.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
    }

    @Test
    fun `legacy journal without identity cannot authorize a complete updated document rollback`() = withDescriptors {
        val prepared = prepare()
        val legacy = prepared.copy(originalFileIdentity = null, originalWritableFileIdentity = null)
        audio.writeText("updated audio")

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, legacy))

        assertEquals("updated audio", audio.readText())
        assertTrue(legacy.journalFile.exists())
        assertTrue(legacy.backupFile.exists())
    }

    @Test
    fun `direct replacement preserves the object swapped into the path at publication`() = withDescriptors {
        val record = prepareDirect()
        val external = directory.newFile("late-owner.flac").apply {
            writeText("external owner")
            assertTrue(setLastModified(1_800_000_000_000L))
        }
        val externalTime = external.lastModified()
        var swapped = false
        fun swapPath() {
            if (swapped) return
            Files.move(external.toPath(), audio.toPath(), StandardCopyOption.REPLACE_EXISTING)
            swapped = true
        }
        beforeTruncate = { swapPath() }
        beforePathReplace = { swapPath() }
        LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
            context, audio.absolutePath, record.updatedFile, record, record.originalLastModifiedMs
        )

        assertTrue("the external replacement must occur at the real mutation boundary", swapped)
        assertEquals("external owner", audio.readText())
        assertEquals(externalTime, audio.lastModified())
        assertTrue(record.journalFile.exists())
    }

    @Test
    fun `already original bytes do not authorize changing a replacement file timestamp`() = withDescriptors {
        val record = prepareDirect()
        val external = directory.newFile("original-owner.flac").apply {
            writeText("original")
            assertTrue(setLastModified(1_800_000_000_000L))
        }
        val externalTime = external.lastModified()
        Files.move(external.toPath(), audio.toPath(), StandardCopyOption.REPLACE_EXISTING)

        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))

        assertEquals("original", audio.readText())
        assertEquals(externalTime, audio.lastModified())
    }

    @Test
    fun `an original owner identity read failure retains evidence until its timestamp can be restored`() = withDescriptors {
        val originalTime = 1_700_000_000_123L
        val record = prepareDirect(originalTime)
        val changedTime = 1_800_000_000_456L
        assertTrue(audio.setLastModified(changedTime))
        var identityReads = 0
        beforeStat = {
            identityReads++
            throw IOException("temporary descriptor identity read failure")
        }

        val restored = LocalMediaMetadataRecoveryStore.rollback(context, record)

        assertTrue("the failure must occur while reading the opened descriptor identity", identityReads > 0)
        assertFalse(restored)
        assertEquals("original", audio.readText())
        assertEquals(changedTime, audio.lastModified())
        assertTrue(record.journalFile.isFile)
        assertEquals("ROLLBACK_FAILED", JSONObject(record.journalFile.readText()).getString("stage"))
        assertEquals("original", record.backupFile.readText())
        assertEquals("updated audio", record.updatedFile.readText())

        beforeStat = {}
        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))

        assertEquals("original", audio.readText())
        assertEquals(originalTime, audio.lastModified())
        assertFalse(record.journalFile.exists())
        assertFalse(record.backupFile.exists())
        assertFalse(record.updatedFile.exists())
    }

    @Test
    fun `an identityless legacy journal with original bytes cleans up without restoring an unowned timestamp`() = withDescriptors {
        val prepared = prepareDirect()
        val legacy = LocalMediaMetadataRecoveryStore.markReplacing(
            prepared.copy(originalFileIdentity = null, originalWritableFileIdentity = null)
        )
        val unchangedTime = 1_800_000_000_456L
        assertTrue(audio.setLastModified(unchangedTime))
        beforeStat = { throw IOException("legacy cleanup has no descriptor identity evidence") }

        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, legacy))

        assertEquals("original", audio.readText())
        assertEquals(unchangedTime, audio.lastModified())
        assertFalse(legacy.journalFile.exists())
        assertFalse(legacy.backupFile.exists())
        assertFalse(legacy.updatedFile.exists())
    }

    @Test
    fun `legacy direct file keys remain bound to the same descriptor instance`() = withDescriptors {
        val prepared = prepareDirect()
        val legacyIdentity = "file:${Files.readAttributes(audio.toPath(), BasicFileAttributes::class.java).fileKey()}"
        val record = LocalMediaMetadataRecoveryStore.markReplacing(
            prepared.copy(originalFileIdentity = legacyIdentity, originalWritableFileIdentity = legacyIdentity)
        )
        audio.writeText("up")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated audio", audio.readText())
        assertFalse(record.journalFile.exists())
    }

    @Test
    fun `direct recovery resumes empty partial update and partial rollback bytes`() = withDescriptors {
        for (prefix in listOf("", "up", "ori")) {
            audio.writeText("original")
            val record = prepareDirect()
            audio.writeText(prefix)
            if (prefix == "ori") record.updatedFile.writeText("corrupt update")
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals(if (prefix == "ori") "original" else "updated audio", audio.readText())
            assertFalse(record.journalFile.exists())
        }
    }

    @Test
    fun `verified audio rollback publishes its intent before a crash leaves original prefix bytes`() = withDescriptors {
        val replacing = prepareDirect()
        audio.writeText("updated audio")
        val record = LocalMediaMetadataRecoveryStore.markTargetVerified(replacing)
        var crashJournal: String? = null
        beforeTruncate = {
            crashJournal = record.journalFile.readText()
            throw IOException("simulated interruption at the first destructive operation")
        }

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))
        beforeTruncate = {}
        audio.writeText("ori")
        record.journalFile.writeText(requireNotNull(crashJournal))
        assertEquals("updated audio", record.updatedFile.readText())
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("original", audio.readText())
        assertFalse(record.journalFile.exists())
    }

    @Test
    fun `failed rollback intent publication keeps evidence and allows same process recovery`() = withDescriptors {
        val record = prepareDirect()
        val journalBeforeRollback = record.journalFile.readText()
        audio.writeText("up")
        beforeJournalPublish = { stage ->
            if (stage == "ROLLBACK_FAILED") throw IOException("simulated rollback intent storage failure")
        }

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))
        assertEquals("up", audio.readText())
        assertEquals(journalBeforeRollback, record.journalFile.readText())
        assertEquals("original", record.backupFile.readText())
        assertEquals("updated audio", record.updatedFile.readText())
        beforeJournalPublish = {}

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated audio", audio.readText())
        assertFalse(record.journalFile.exists())
        assertFalse(record.backupFile.exists())
        assertFalse(record.updatedFile.exists())
    }

    @Test
    fun `failed rolled back publication keeps evidence and allows same process cleanup`() = withDescriptors {
        val record = prepareDirect()
        audio.writeText("up")
        beforeJournalPublish = { stage ->
            if (stage == "ROLLED_BACK") throw IOException("simulated rollback completion storage failure")
        }

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))
        assertEquals("original", audio.readText())
        assertEquals("ROLLBACK_FAILED", JSONObject(record.journalFile.readText()).getString("stage"))
        assertEquals("original", record.backupFile.readText())
        assertEquals("updated audio", record.updatedFile.readText())
        beforeJournalPublish = {}

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("original", audio.readText())
        assertFalse(record.journalFile.exists())
        assertFalse(record.backupFile.exists())
        assertFalse(record.updatedFile.exists())
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

    private fun prepareDirect(originalTime: Long = 1_700_000_000_000L): LocalMetadataRecoveryRecord {
        assertTrue(audio.setLastModified(originalTime))
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        return LocalMediaMetadataRecoveryStore.markReplacing(LocalMediaMetadataRecoveryStore.begin(
            context, audio.absolutePath,
            File.createTempFile("direct-original-", ".flac", staging).apply { writeText("original") },
            File.createTempFile("direct-updated-", ".flac", staging).apply { writeText("updated audio") },
            audio.lastModified()
        ))
    }
}
private class RecoveryDocumentProvider(var audio: File) : ContentProvider() {
    var allowReadWrite = true
    var beforeWrite: () -> Unit = {}
    val writeModes = mutableListOf<String>()
    var readDeviceOffset = 0L
    var writeDeviceOffset = 0L
    var forcedInode: Long? = null
    var trackDescriptor: (ParcelFileDescriptor, File, Long, Long?) -> ParcelFileDescriptor = { descriptor, _, _, _ -> descriptor }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            writeModes += mode
            if (mode != "rw" || !allowReadWrite) throw FileNotFoundException("non truncating writes unavailable")
            beforeWrite()
        }
        val access = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE
        val offset = if (mode == "r") readDeviceOffset else writeDeviceOffset
        return trackDescriptor(ParcelFileDescriptor.open(audio, access), audio, offset, forcedInode)
    }

    override fun getType(uri: Uri): String = "audio/flac"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
