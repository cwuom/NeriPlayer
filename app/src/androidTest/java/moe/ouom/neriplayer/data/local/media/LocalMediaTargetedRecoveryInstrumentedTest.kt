package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaCompanionTransaction
import moe.ouom.neriplayer.data.local.media.metadata.LocalMetadataRecoveryRecord

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalMediaTargetedRecoveryInstrumentedTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var recoveryDirectory: File
    private val context = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = recoveryDirectory
    }
    private val audioUri = DocumentsContract.buildDocumentUri(
        Issue339LyricsTestDocumentProvider.AUTHORITY,
        Issue339LyricsTestDocumentProvider.AUDIO_ID
    )

    @Before
    fun createOwnedRecoveryDirectory() {
        recoveryDirectory = File(baseContext.cacheDir, "targeted-recovery-${UUID.randomUUID()}")
        check(recoveryDirectory.mkdirs())
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @After
    fun removeOwnedRecoveryDirectory() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        if (::recoveryDirectory.isInitialized) check(recoveryDirectory.deleteRecursively())
    }

    @Test
    fun opaqueDocumentIdentityIsSharedByTreeAndDirectReferencesOnlyWithinTheAuthority() {
        val authority = Issue339LyricsTestDocumentProvider.AUTHORITY
        val documentId = "node+${UUID.randomUUID()}/opaque%id"
        val direct = DocumentsContract.buildDocumentUri(authority, documentId)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "unrelated/tree+%id")
        val treeDocument = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val identity = LocalMediaMetadataRecoveryStore.targetIdentity(direct.toString())
        assertTrue(identity != null)
        assertEquals(identity, LocalMediaMetadataRecoveryStore.targetIdentity(treeDocument.toString()))
        assertNotEquals(identity, LocalMediaMetadataRecoveryStore.targetIdentity(
            DocumentsContract.buildDocumentUri("other.authority", documentId).toString()
        ))
        assertNotEquals(identity, LocalMediaMetadataRecoveryStore.targetIdentity(
            DocumentsContract.buildDocumentUri(authority, "$documentId/other").toString()
        ))
    }

    @Test
    fun anActiveDirectDocumentJournalBlocksItsTreeAliasAmongAllWriteCandidates() = runBlocking {
        val original = readAudio()
        val record = record(original)
        val tree = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val alias = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        try {
            var writes = 0
            val result = LocalMediaMetadataRecoveryStore.withRecoveredTargets(
                context,
                listOf("content://another.authority/media/42", alias.toString()),
                "blocked"
            ) { writes++; "written" }
            assertEquals("blocked", result)
            assertEquals(0, writes)
            assertTrue(record.journalFile.isFile)
            assertArrayEquals(original, readAudio())
        } finally {
            assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))
        }
    }

    @Test
    fun alreadyOriginalReadOnlyContentRollsBackWithoutAttemptingAWrite() {
        val original = readAudio()
        val record = LocalMediaMetadataRecoveryStore.markReplacing(record(original))
        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))
        assertArrayEquals(original, readAudio())
        assertFalse(record.journalFile.exists())
        assertFalse(record.backupFile.exists())
        assertFalse(record.updatedFile.exists())
    }

    @Test
    fun readOnlyContentWithDifferentBytesRetainsItsFailedRollbackEvidence() = runBlocking {
        val original = readAudio()
        val record = LocalMediaMetadataRecoveryStore.markReplacing(record("other original".toByteArray()))
        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))
        assertArrayEquals(original, readAudio())
        assertTrue(record.journalFile.readText().contains("ROLLBACK_FAILED"))
        assertArrayEquals("other original".toByteArray(), record.backupFile.readBytes())
        assertTrue(record.updatedFile.isFile)
        assertEquals("blocked", LocalMediaMetadataRecoveryStore.withRecoveredTargets(
            context, listOf(audioUri.toString()), "blocked"
        ) { "written" })
        assertArrayEquals(original, readAudio())
    }

    @Test
    fun directRecoveryAcceptsTheLegacyNioIdentityAndPreservesInodeAndExactTimestamp() = runBlocking {
        val original = "original audio bytes".toByteArray()
        val updated = "updated audio bytes with a longer tail".toByteArray()
        val target = File(recoveryDirectory, "legacy-direct.wav").apply { writeBytes(original) }
        val originalTime = 1_700_000_000_123L
        assertTrue(target.setLastModified(originalTime))
        val originalStat = Os.stat(target.absolutePath)
        val legacyIdentity = "file:${Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey()}"
        val record = LocalMediaMetadataRecoveryStore.markReplacing(
            record(target.absolutePath, original, updated, originalTime)
        )
        assertEquals(legacyIdentity, record.originalFileIdentity)
        record.journalFile.writeText(JSONObject(record.journalFile.readText()).apply {
            put("originalFileIdentity", legacyIdentity)
            remove("originalWritableFileIdentity")
        }.toString())
        target.writeText("up")

        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))

        assertArrayEquals(updated, target.readBytes())
        assertEquals(originalTime, target.lastModified())
        assertEquals(originalStat.st_dev, Os.stat(target.absolutePath).st_dev)
        assertEquals(originalStat.st_ino, Os.stat(target.absolutePath).st_ino)
        assertFalse(record.journalFile.exists())
        assertFalse(record.backupFile.exists())
        assertFalse(record.updatedFile.exists())
    }

    @Test
    fun verifiedDirectAudioRollsBackThroughItsOwnedDescriptorAndRestoresExactTimestamp() {
        val original = "original direct audio bytes".toByteArray()
        val updated = "updated direct audio bytes with a longer tail".toByteArray()
        val target = File(recoveryDirectory, "rollback-direct.wav").apply { writeBytes(original) }
        val originalTime = 1_700_000_000_123L
        assertTrue(target.setLastModified(originalTime))
        val originalStat = Os.stat(target.absolutePath)
        val (backup, staged) = stagingFiles(original, updated)
        val transaction = LocalMediaCompanionTransaction(context, target.absolutePath)
        val record = transaction.initialize(backup, staged, originalTime)
        transaction.prepareUpdatedAudio()
        target.writeBytes(updated)
        transaction.audioVerified()

        assertTrue(transaction.rollback())

        assertArrayEquals(original, target.readBytes())
        assertEquals(originalTime, target.lastModified())
        assertEquals(originalStat.st_dev, Os.stat(target.absolutePath).st_dev)
        assertEquals(originalStat.st_ino, Os.stat(target.absolutePath).st_ino)
        assertFalse(record.journalFile.exists())
        assertFalse(backup.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun replacementProviderObjectWithTheCompleteUpdatedHashIsNeverRolledBack() {
        val uri = StagedMetadataTestProvider.CONTENT_URI
        val original = "original provider audio".toByteArray()
        val updated = "updated provider audio".toByteArray()
        try {
            writeProviderAudio(uri, original)
            val record = LocalMediaMetadataRecoveryStore.markReplacing(record(uri.toString(), original, updated))
            assertTrue(record.originalFileIdentity != null)
            assertTrue(record.originalWritableFileIdentity != null)
            writeProviderAudio(uri, updated)
            context.contentResolver.call(uri, "replaceWithSameBytes", null, null)

            assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, record))

            assertArrayEquals(updated, readProviderAudio(uri))
            assertTrue(record.journalFile.isFile)
            assertTrue(record.journalFile.readText().contains("ROLLBACK_FAILED"))
            assertArrayEquals(original, record.backupFile.readBytes())
            assertArrayEquals(updated, record.updatedFile.readBytes())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun pipeReadProviderRestoresAnInterruptedWriteUsingItsVerifiedWritableIdentity() {
        val uri = StagedMetadataTestProvider.CONTENT_URI
        val pipeUri = uri.buildUpon().appendQueryParameter("pipe", "true").build()
        val original = "original pipe provider audio".toByteArray()
        val updated = "updated pipe provider audio".toByteArray()
        try {
            writeProviderAudio(uri, original)
            val record = LocalMediaMetadataRecoveryStore.markReplacing(record(pipeUri.toString(), original, updated))
            assertEquals(null, record.originalFileIdentity)
            assertTrue(record.originalWritableFileIdentity != null)
            writeProviderAudio(uri, "up".toByteArray())

            assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))

            assertArrayEquals(original, readProviderAudio(uri))
            assertFalse(record.journalFile.exists())
            assertFalse(record.backupFile.exists())
            assertFalse(record.updatedFile.exists())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun readAudio(): ByteArray =
        checkNotNull(context.contentResolver.openInputStream(audioUri)).use { it.readBytes() }

    private fun record(original: ByteArray): LocalMetadataRecoveryRecord {
        return record(audioUri.toString(), original, "updated bytes".toByteArray())
    }

    private fun record(
        reference: String,
        original: ByteArray,
        updated: ByteArray,
        originalTime: Long? = null
    ): LocalMetadataRecoveryRecord {
        val (backup, staged) = stagingFiles(original, updated)
        return LocalMediaMetadataRecoveryStore.begin(context, reference, backup, staged, originalTime)
    }

    private fun stagingFiles(original: ByteArray, updated: ByteArray): Pair<File, File> {
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val backup = File(directory, "metadata-source-$id.wav").apply { writeBytes(original) }
        val staged = File(directory, "metadata-updated-$id.wav").apply { writeBytes(updated) }
        return backup to staged
    }

    private fun writeProviderAudio(uri: Uri, bytes: ByteArray) =
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }

    private fun readProviderAudio(uri: Uri): ByteArray =
        checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
}
