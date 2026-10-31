package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaMetadataRecoveryStore

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
import org.json.JSONObject
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        target.writeText("updated audio")
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
        target.writeText("original")
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
        target.writeText("up")

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("up", target.readText())
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

    @Test
    fun `replacing recovery preserves an external file swapped into the same path`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("replacing.flac").apply { writeText("original") }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context, target.absolutePath,
            File(directory, "source.flac").apply { writeText("original") },
            File(directory, "updated.flac").apply { writeText("updated") },
            target.lastModified()
        )
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        val replacement = temporaryFolder.newFile("external.flac").apply { writeText("up") }
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("up", target.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
    }

    @Test
    fun `rollback preserves an external file swapped into the same path`() {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("rollback-external.flac").apply { writeText("original") }
        val prepared = LocalMediaMetadataRecoveryStore.begin(
            context, target.absolutePath,
            File(directory, "source.flac").apply { writeText("original") },
            File(directory, "updated.flac").apply { writeText("updated") },
            target.lastModified()
        )
        val replacing = LocalMediaMetadataRecoveryStore.markReplacing(prepared)
        val replacement = temporaryFolder.newFile("external.flac").apply { writeText("up") }
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, replacing))
        assertEquals("up", target.readText())
        assertTrue(replacing.journalFile.exists())
        assertTrue(replacing.backupFile.exists())
        assertTrue(replacing.updatedFile.exists())
    }

    @Test
    fun `replacing recovery preserves external edits made inside the original file instance`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("edited.flac").apply { writeText("original") }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context, target.absolutePath,
            File(directory, "source.flac").apply { writeText("original") },
            File(directory, "updated.flac").apply { writeText("updated") },
            target.lastModified()
        )
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        target.writeText("external in-place edit")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("external in-place edit", target.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
    }

    @Test
    fun `legacy replacing journals without instance identity preserve ambiguous target bytes`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("legacy-replacing.flac").apply { writeText("original") }
        val record = LocalMediaMetadataRecoveryStore.begin(
            context, target.absolutePath,
            File(directory, "source.flac").apply { writeText("original") },
            File(directory, "updated.flac").apply { writeText("updated") },
            target.lastModified()
        )
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        record.journalFile.writeText(JSONObject(record.journalFile.readText()).apply {
            remove("originalFileIdentity")
            remove("originalWritableFileIdentity")
        }.toString())
        target.writeText("ambiguous bytes")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("ambiguous bytes", target.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.exists())
        assertTrue(record.updatedFile.exists())
    }

    @Test
    fun `partial rollback cleanup never reapplies the updated copy`() = runBlocking {
        val context = recoveryContext()
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("rollback.mp3").apply { writeText("original") }
        val backup = File(directory, "metadata-source-rollback.mp3").apply {
            writeText("original")
        }
        val updated = File(directory, "metadata-updated-rollback.mp3").apply {
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
        target.writeText("ori")
        assertTrue(updated.delete())
        assertTrue(updated.mkdir())
        File(updated, "keep").writeText("block cleanup")

        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, replacing))
        assertEquals("original", target.readText())
        assertTrue(replacing.journalFile.readText().contains("ROLLED_BACK"))

        assertTrue(File(updated, "keep").delete())
        assertTrue(updated.delete())
        updated.writeText("updated")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("original", target.readText())
        assertFalse(replacing.journalFile.exists())
    }

    private fun recoveryContext(): Context = mock(Context::class.java).also { context ->
        `when`(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("no-backup"))
    }
}
