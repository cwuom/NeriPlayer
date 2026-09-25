package moe.ouom.neriplayer.data.local.media

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class LocalMediaTargetedRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("no-backup"))
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `editing one target does not recover unrelated audio and global recovery remains available`() = runBlocking {
        val unrelated = temporaryFolder.newFile("unrelated.mp3").apply { writeText("original") }
        val record = record(unrelated)
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        unrelated.writeText("interrupted")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        val target = temporaryFolder.newFile("selected.mp3")

        assertEquals("written", write(target) { "written" })
        assertEquals("interrupted", unrelated.readText())
        assertTrue(record.journalFile.exists())
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated", unrelated.readText())
    }

    @Test
    fun `unresolved same target prevents a newer edit and keeps recovery copies`() = runBlocking {
        val target = temporaryFolder.newFile("blocked.mp3").apply { writeText("original") }
        val record = record(target)
        target.writeText("external replacement")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        var writes = 0

        repeat(2) {
            assertEquals("blocked", write(target) { writes++; "written" })
        }
        assertEquals(0, writes)
        assertEquals("external replacement", target.readText())
        assertTrue(record.journalFile.exists())
        assertTrue(record.backupFile.isFile)
        assertTrue(record.updatedFile.isFile)
    }

    @Test
    fun `active same target blocks a second edit`() = runBlocking {
        val target = temporaryFolder.newFile("active.mp3").apply { writeText("original") }
        val record = record(target)
        assertEquals("blocked", write(target) { "written" })
        assertTrue(record.journalFile.exists())
        assertEquals("original", target.readText())
    }

    @Test
    fun `unknown malformed journal cannot be ignored before writing`() = runBlocking {
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val journal = File(directory, "recovery-broken.json").apply { writeText("{") }
        val target = temporaryFolder.newFile("selected.mp3")
        assertEquals("blocked", write(target) { "written" })
        assertEquals("{", journal.readText())
    }

    @Test
    fun `unknown journal prevents replaying a known older record before blocking the edit`() = runBlocking {
        val target = temporaryFolder.newFile("unknown-successor.mp3").apply { writeText("original") }
        val record = LocalMediaMetadataRecoveryStore.markReplacing(record(target))
        val knownJournal = record.journalFile.readText()
        val unknownJournal = File(record.journalFile.parentFile, "recovery-unknown.json")
            .apply { writeText("{") }
        target.writeText("newer bytes")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals("blocked", write(target) { "written" })
        assertEquals("newer bytes", target.readText())
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("newer bytes", target.readText())
        assertEquals(knownJournal, record.journalFile.readText())
        assertEquals("{", unknownJournal.readText())
        assertEquals("original", record.backupFile.readText())
        assertEquals("updated", record.updatedFile.readText())
    }

    @Test
    fun `target reservation spans the complete write but does not block another target`() = runBlocking {
        val target = temporaryFolder.newFile("selected.mp3")
        val other = temporaryFolder.newFile("other.mp3")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondRequested = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = async {
            write(target) {
                order += "first start"
                entered.complete(Unit)
                release.await()
                order += "first end"
                "first written"
            }
        }
        entered.await()
        val second = async {
            secondRequested.complete(Unit)
            write(target) { order += "second"; "second written" }
        }
        secondRequested.await()
        try {
            assertEquals("other written", withTimeout(5_000L) { write(other) { "other written" } })
        } finally {
            release.complete(Unit)
        }
        assertEquals("first written", first.await())
        assertEquals("second written", second.await())
        assertEquals(listOf("first start", "first end", "second"), order)
    }

    @Test
    fun `cancelled writer releases its target reservation`() = runBlocking {
        val target = temporaryFolder.newFile("cancelled.mp3")
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            write(target) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals("written", write(target) { "written" })
    }

    @Test
    fun `same target recovery finishes before new edit and cannot overwrite it later`() = runBlocking {
        val target = temporaryFolder.newFile("recoverable.mp3").apply { writeText("original") }
        val record = record(target)
        LocalMediaMetadataRecoveryStore.markReplacing(record)
        target.writeText("interrupted")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        assertEquals("written", write(target) {
            assertEquals("updated", target.readText())
            target.writeText("new edit")
            "written"
        })
        assertFalse(record.journalFile.exists())
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("new edit", target.readText())
    }

    @Test
    fun `one hundred unrelated journals stay untouched during a selected write`() = runBlocking {
        val originals = (1..100).map { index ->
            val target = temporaryFolder.newFile("unrelated-$index.mp3").apply { writeText("original") }
            val record = record(target)
            LocalMediaMetadataRecoveryStore.markReplacing(record)
            target.writeText("interrupted-$index")
            Triple(target, target.readText(), record)
        }
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        val target = temporaryFolder.newFile("selected.mp3")
        assertEquals("written", write(target) { "written" })
        originals.forEach { (file, original, record) ->
            assertEquals(original, file.readText())
            assertTrue(record.journalFile.isFile)
            assertEquals("original", record.backupFile.readText())
            assertEquals("updated", record.updatedFile.readText())
        }
    }

    @Test
    fun `file URI and canonical path aliases cannot bypass unresolved journal`() = runBlocking {
        val target = temporaryFolder.newFile("literal + space.mp3").apply { writeText("original") }
        record(target)
        target.writeText("external replacement")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        assertEquals("blocked", LocalMediaMetadataRecoveryStore.withRecoveredTargets(
            context, listOf(target.toURI().toString()), "blocked"
        ) { "written" })
        assertEquals("blocked", write(File(target.parentFile, "./${target.name}")) { "written" })
        assertEquals("external replacement", target.readText())
    }

    @Test
    fun `failed rollback blocks new edit until original target can be restored`() = runBlocking {
        val target = temporaryFolder.newFile("rollback.mp3").apply { writeText("original") }
        val record = record(target)
        val replacing = LocalMediaMetadataRecoveryStore.markReplacing(record)
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        val obstruction = File(target, "keep").apply { writeText("blocked") }
        assertFalse(LocalMediaMetadataRecoveryStore.rollback(context, replacing))
        assertTrue(record.journalFile.readText().contains("ROLLBACK_FAILED"))
        assertEquals("blocked", write(target) { "written" })
        assertTrue(record.backupFile.exists())
        assertTrue(obstruction.exists())
        assertTrue(obstruction.delete())
        assertTrue(target.delete())
        target.writeText("interrupted")
        assertEquals("written", write(target) {
            assertEquals("original", target.readText())
            target.writeText("new edit")
            "written"
        })
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("new edit", target.readText())
    }

    @Test
    fun `conflicting old journals cannot be replayed in directory order`() = runBlocking {
        val target = temporaryFolder.newFile("conflicted.mp3").apply { writeText("original") }
        val first = record(target)
        val second = record(target)
        LocalMediaMetadataRecoveryStore.markReplacing(first)
        LocalMediaMetadataRecoveryStore.markReplacing(second)
        target.writeText("newer external bytes")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        assertEquals("blocked", write(target) { "written" })
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("newer external bytes", target.readText())
        listOf(first, second).forEach { record ->
            assertTrue(record.journalFile.isFile)
            assertEquals("original", record.backupFile.readText())
            assertEquals("updated", record.updatedFile.readText())
        }
        val other = temporaryFolder.newFile("other.mp3")
        assertEquals("written", write(other) { "written" })
    }

    @Test
    fun `startup recovery cannot take over a journal begun by the reserved writer`() = runBlocking {
        val target = temporaryFolder.newFile("active.mp3").apply { writeText("original") }
        assertEquals("written", write(target) {
            val record = record(target)
            LocalMediaMetadataRecoveryStore.markReplacing(record)
            target.writeText("active partial write")
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals("active partial write", target.readText())
            target.writeText("updated")
            LocalMediaMetadataRecoveryStore.complete(LocalMediaMetadataRecoveryStore.markTargetVerified(record))
            "written"
        })
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated", target.readText())
    }

    @Test
    fun `cancelled waiter cannot release the active writer reservation`() = runBlocking {
        val target = temporaryFolder.newFile("waiter.mp3")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { write(target) { entered.complete(Unit); release.await(); "first" } }
        entered.await()
        val requested = CompletableDeferred<Unit>()
        val waiter = launch { requested.complete(Unit); write(target) { "cancelled write" } }
        requested.await()
        waiter.cancelAndJoin()
        val third = async { write(target) { assertTrue(release.isCompleted); "third" } }
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("third", third.await())
    }

    @Test
    fun `already restored bytes still restore the original file modification time`() {
        val target = temporaryFolder.newFile("timestamp.mp3").apply { writeText("original") }
        val originalTime = 1_600_000_000_000L
        assertTrue(target.setLastModified(originalTime))
        val record = record(target).copy(originalLastModifiedMs = originalTime)
        assertTrue(target.setLastModified(originalTime + 60_000L))
        assertTrue(LocalMediaMetadataRecoveryStore.rollback(context, record))
        assertEquals(originalTime, target.lastModified())
        assertEquals("original", target.readText())
        assertFalse(record.journalFile.exists())
    }

    @Test
    fun `completed recovery cache belongs to its own staging directory`() = runBlocking {
        val otherContext = mock(Context::class.java)
        `when`(otherContext.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("other-no-backup"))
        val firstTarget = temporaryFolder.newFile("first-context.mp3").apply { writeText("original") }
        val secondTarget = temporaryFolder.newFile("second-context.mp3").apply { writeText("original") }
        val first = LocalMediaMetadataRecoveryStore.markReplacing(record(firstTarget))
        val second = LocalMediaMetadataRecoveryStore.markReplacing(record(secondTarget, otherContext))
        firstTarget.writeText("first interrupted")
        secondTarget.writeText("second interrupted")
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertEquals("updated", firstTarget.readText())
        assertEquals("second interrupted", secondTarget.readText())
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(otherContext))
        assertEquals("updated", secondTarget.readText())
        assertFalse(first.journalFile.exists())
        assertFalse(second.journalFile.exists())
    }

    private suspend fun write(target: File, block: suspend () -> String): String =
        LocalMediaMetadataRecoveryStore.withRecoveredTargets(
            context, listOf(target.absolutePath), "blocked", block
        )

    private fun record(target: File, recoveryContext: Context = context): LocalMetadataRecoveryRecord {
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(recoveryContext).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val backup = File(directory, "metadata-source-$id.mp3").apply { writeText("original") }
        val updated = File(directory, "metadata-updated-$id.mp3").apply { writeText("updated") }
        return LocalMediaMetadataRecoveryStore.begin(recoveryContext, target.absolutePath, backup, updated, null)
    }
}
