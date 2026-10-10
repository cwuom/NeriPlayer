package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File
import java.io.IOException

class LocalMediaCompanionTransactionLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var music: File
    private val staging: File get() = LocalMediaMetadataRecoveryStore.stagingDirectory(context)

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        context = mock(Context::class.java)
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        music = temporaryFolder.newFolder("music")
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `sidecar-only transactions start once and roll back without leftovers`() {
        val audio = File(music, "song.flac").absolutePath
        val first = LocalMediaCompanionTransaction(context, audio)
        val second = LocalMediaCompanionTransaction(context, audio)

        first.initializeSidecarsOnly()
        val started = requireNotNull(first.record)
        first.initializeSidecarsOnly()
        second.initializeSidecarsOnly()

        assertSame(started, first.record)
        assertEquals(
            listOf<Any>(true, true, LocalMetadataRecoveryStage.PREPARED),
            listOf(started.companionTransaction, started.audioUnchanged, started.stage)
        )
        assertNotEquals(started.id, requireNotNull(second.record).id)
        assertEquals(2, journals().size)

        assertTrue(first.rollback())
        assertNull(first.record)
        assertEquals(listOf(requireNotNull(second.record).journalFile.name), journals())

        assertTrue(second.rollback())
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `audio transactions commit only after the rewritten audio is verified`() {
        val target = File(music, "song.flac").apply { writeText("updated audio") }
        val transaction = LocalMediaCompanionTransaction(context, target.absolutePath)
        val record = transaction.initialize(
            stagedCopy("source", "original audio"),
            stagedCopy("updated", "updated audio"),
            originalTime = null
        )

        assertEquals(listOf(true, false), listOf(record.companionTransaction, record.audioUnchanged))
        expectFailure<IllegalStateException> { transaction.initialize(record.backupFile, record.updatedFile, null) }

        transaction.commit()

        val committed = requireNotNull(transaction.record)
        assertEquals(
            listOf<Any>(true, LocalMetadataRecoveryStage.TARGET_VERIFIED),
            listOf(committed.companionCommitted, committed.stage)
        )
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `a failed audio verification keeps the journal so rollback can restore the original`() {
        val target = File(music, "song.flac").apply { writeText("original audio") }
        val transaction = LocalMediaCompanionTransaction(context, target.absolutePath)
        transaction.initialize(stagedCopy("source", "original audio"), stagedCopy("updated", "updated audio"), MODIFIED_AT)
        target.writeText("externally changed")

        val error = expectFailure<IOException> { transaction.commit() }
        assertEquals("伴随事务音频完整性未确认", error.message)
        assertEquals(1, journals().size)

        assertTrue(transaction.rollback())
        assertNull(transaction.record)
        assertEquals("original audio", target.readText())
        assertEquals(MODIFIED_AT, target.lastModified())
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `a creation that cannot be proven is journaled as an unresolved write intent`() {
        val lyrics = File(music, "song.lrc").absolutePath
        val transaction = LocalMediaCompanionTransaction(context, File(music, "song.flac").absolutePath)

        expectFailure<IllegalArgumentException> { transaction.created(lyrics) }

        val record = requireNotNull(transaction.record)
        val entry = record.companions.single()
        assertEquals(
            listOf<Any?>(lyrics, null, null, EMPTY_SHA256, true, "WRITE_INTENT"),
            listOf(
                entry.reference, entry.backupFile, entry.originalSha256,
                entry.expectedSha256, entry.createdByTransaction, entry.phase
            )
        )
        assertEquals(0L, requireNotNull(entry.intendedFile).length())
        val journaled = JSONObject(record.journalFile.readText()).getJSONArray("companions").getJSONObject(0)
        assertEquals(listOf(lyrics, "WRITE_INTENT"), listOf(journaled.getString("reference"), journaled.getString("phase")))

        assertTrue(transaction.rollback())
        assertNull(transaction.record)
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `deferred deletes of companions that are already gone finish on commit`() {
        val lyrics = File(music, "song.lrc").absolutePath
        val transaction = LocalMediaCompanionTransaction(context, File(music, "song.flac").absolutePath)

        transaction.deferDelete(lyrics)
        val entry = requireNotNull(transaction.record).companions.single()
        assertEquals(
            listOf<Any?>(lyrics, true, true, null),
            listOf(entry.reference, entry.deferredDelete, entry.createdByTransaction, entry.phase)
        )

        transaction.commit()

        assertTrue(requireNotNull(transaction.record).companionCommitted)
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    private fun stagedCopy(name: String, text: String): File =
        File(staging.apply { mkdirs() }, "metadata-$name.flac").apply { writeText(text) }

    private fun journals(): List<String> =
        staging.list()!!.filter { it.startsWith("recovery-") && it.endsWith(".json") }.sorted()

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val MODIFIED_AT = 1_700_000_000_000L
    }
}
