package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File

class LocalMediaMetadataRecoveryCompletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var staging: File

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        context = mock(Context::class.java)
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `completion requires a verified target before removing recovery copies`() {
        val prepared = begin("plain")

        val error = expectFailure<IllegalStateException> { LocalMediaMetadataRecoveryStore.complete(prepared) }
        assertEquals("metadata target was not verified", error.message)
        assertRecoveryFiles(prepared, present = true)

        val verified = LocalMediaMetadataRecoveryStore.markTargetVerified(
            LocalMediaMetadataRecoveryStore.markReplacing(prepared)
        )
        LocalMediaMetadataRecoveryStore.complete(verified)
        assertRecoveryFiles(verified, present = false)
    }

    @Test
    fun `companion transactions complete only after their commit was recorded`() {
        val verified = LocalMediaMetadataRecoveryStore.markTargetVerified(begin("companion", companionTransaction = true))

        expectFailure<IllegalStateException> { LocalMediaMetadataRecoveryStore.complete(verified) }
        assertRecoveryFiles(verified, present = true)

        LocalMediaMetadataRecoveryStore.complete(verified.copy(companionCommitted = true))
        assertRecoveryFiles(verified, present = false)
    }

    @Test
    fun `completion keeps recovery copies while a staged companion cannot be verified`() {
        val lyrics = File(temporaryFolder.newFolder("music"), "song.lrc")
        val staged = File(lyrics.parentFile, ".song.lrc.companion-1.tmp").apply { writeText("half written") }
        val committed = LocalMediaMetadataRecoveryStore.markTargetVerified(begin("staged", companionTransaction = true))
            .copy(
                companionCommitted = true,
                companions = listOf(
                    LocalMediaCompanionRecoveryEntry(
                        reference = lyrics.absolutePath,
                        backupFile = null,
                        originalSha256 = null,
                        expectedSha256 = null,
                        originalLastModifiedMs = null,
                        createdByTransaction = true,
                        stagedFile = staged,
                        stagedIdentity = "unverifiable"
                    )
                )
            )

        LocalMediaMetadataRecoveryStore.complete(committed)
        assertTrue(staged.exists())
        assertRecoveryFiles(committed, present = true)

        assertTrue(staged.delete())
        LocalMediaMetadataRecoveryStore.complete(committed)
        assertRecoveryFiles(committed, present = false)
    }

    private fun begin(name: String, companionTransaction: Boolean = false): LocalMetadataRecoveryRecord {
        val target = temporaryFolder.newFile("$name.flac").apply { writeText("original $name") }
        return LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = File(staging, "metadata-source-$name.flac").apply { writeText("original $name") },
            updatedFile = File(staging, "metadata-updated-$name.flac").apply { writeText("updated $name") },
            originalLastModifiedMs = null,
            companionTransaction = companionTransaction
        )
    }

    private fun assertRecoveryFiles(record: LocalMetadataRecoveryRecord, present: Boolean) {
        assertEquals(
            listOf(present, present, present),
            listOf(record.backupFile.exists(), record.updatedFile.exists(), record.journalFile.exists())
        )
    }
}
