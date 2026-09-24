package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContextWrapper
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
    }

    @After
    fun removeOwnedRecoveryDirectory() {
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

    private fun readAudio(): ByteArray =
        checkNotNull(context.contentResolver.openInputStream(audioUri)).use { it.readBytes() }

    private fun record(original: ByteArray): LocalMetadataRecoveryRecord {
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val backup = File(directory, "metadata-source-$id.wav").apply { writeBytes(original) }
        val updated = File(directory, "metadata-updated-$id.wav").apply { writeText("updated bytes") }
        return LocalMediaMetadataRecoveryStore.begin(context, audioUri.toString(), backup, updated, null)
    }
}
