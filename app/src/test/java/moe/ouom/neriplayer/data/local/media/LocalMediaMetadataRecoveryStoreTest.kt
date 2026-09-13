package moe.ouom.neriplayer.data.local.media

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class LocalMediaMetadataRecoveryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `startup recovery completes a prepared updated copy`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("song.flac").apply { writeText("original audio") }
        val backup = File(directory, "metadata-source-test.flac").apply {
            writeText("original audio")
        }
        val updated = File(directory, "metadata-updated-test.flac").apply {
            writeText("updated audio with exact tags")
        }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = target.lastModified()
        )
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        target.writeText("truncated")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated audio with exact tags", target.readText())
        assertFalse(record.journalFile.exists())
        assertFalse(backup.exists())
        assertFalse(updated.exists())
    }

    @Test
    fun `startup recovery restores backup when updated copy is invalid`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("song.mp3").apply { writeText("original audio") }
        val backup = File(directory, "metadata-source-test.mp3").apply {
            writeText("original audio")
        }
        val updated = File(directory, "metadata-updated-test.mp3").apply {
            writeText("intended update")
        }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = target.lastModified()
        )
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        updated.writeText("corrupt update")
        target.writeText("truncated")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("original audio", target.readText())
        assertFalse(record.journalFile.exists())
    }

    @Test
    fun `same process recovery does not take over an active transaction`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("active.flac").apply { writeText("original") }
        val backup = File(directory, "metadata-source-active.flac").apply {
            writeText("original")
        }
        val updated = File(directory, "metadata-updated-active.flac").apply {
            writeText("updated")
        }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = target.lastModified()
        )
        target.writeText("active write")

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("active write", target.readText())
        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))
    }

    @Test
    fun `prepared recovery does not overwrite an externally replaced target`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("prepared.flac").apply { writeText("original") }
        val backup = File(directory, "metadata-source-prepared.flac").apply {
            writeText("original")
        }
        val updated = File(directory, "metadata-updated-prepared.flac").apply {
            writeText("updated")
        }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = target.lastModified()
        )
        target.writeText("external owner")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("external owner", target.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(backup.exists())
        assertTrue(updated.exists())
    }

    @Test
    fun `verified recovery does not overwrite a later external edit`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("verified.mp3").apply { writeText("original") }
        val backup = File(directory, "metadata-source-verified.mp3").apply {
            writeText("original")
        }
        val updated = File(directory, "metadata-updated-verified.mp3").apply {
            writeText("updated")
        }
        val prepared = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = target.lastModified()
        )
        val replacing = LocalMediaMetadataRecoveryStore.markReplacing(prepared)
        target.writeText("updated")
        val verified = LocalMediaMetadataRecoveryStore.markTargetVerified(replacing)
        target.writeText("later external edit")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("later external edit", target.readText())
        assertTrue(verified.journalFile.exists())
    }

    private fun recoveryContext(): Context = mock(Context::class.java).also { context ->
        `when`(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("no-backup"))
    }
}
