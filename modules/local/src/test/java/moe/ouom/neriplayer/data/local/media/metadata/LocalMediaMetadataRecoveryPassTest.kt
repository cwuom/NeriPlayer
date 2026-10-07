package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
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
import java.security.MessageDigest

class LocalMediaMetadataRecoveryPassTest {
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
    fun `a completed pass is reused only for the same staging directory`() = runTest {
        val first = recoveryContext("first")
        val second = recoveryContext("second")

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(first))
        val journal = plantJournal(first, "song")

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(first))
        assertTrue(journal.exists())

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(second))
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(first))
        assertFalse(journal.exists())
    }

    @Test
    fun `a pass that ran beside an active record elsewhere is not reused`() = runTest {
        val idle = recoveryContext("idle")
        val busy = recoveryContext("busy")
        val active = beginRecord(busy, "active")

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(idle))
        val journal = plantJournal(idle, "late")

        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(idle))
        assertFalse(journal.exists())
        assertTrue(active.journalFile.exists())
    }

    @Test
    fun `a pass that ran while a target was reserved is not reused`() = runTest {
        val context = recoveryContext("reserved")
        val reservedTarget = temporaryFolder.newFile("reserved.flac").apply { writeText("original") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async {
            LocalMediaMetadataRecoveryStore.withRecoveredTargets(
                context = context,
                targetReferences = listOf(reservedTarget.absolutePath),
                blockedResult = "blocked"
            ) {
                entered.complete(Unit)
                release.await()
                "written"
            }
        }
        entered.await()

        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        release.complete(Unit)
        assertEquals("written", writer.await())

        val journal = plantJournal(context, "later")
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertFalse(journal.exists())
    }

    @Test
    fun `journals decode zero timestamps and companion entries but keep unknown versions`() = runTest {
        val context = recoveryContext("decode")
        val futureVersion = plantJournal(context, "future") { put("version", 2) }
        val zeroTimestamp = plantJournal(context, "zero") { put("originalLastModifiedMs", 0) }
        val missingLyrics = File(temporaryFolder.root, "missing.lrc")
        val sidecars = plantJournal(context, "sidecars") {
            put("companionTransaction", true)
            put("companionCommitted", false)
            put("audioUnchanged", true)
            put(
                "companions",
                JSONArray().put(
                    JSONObject()
                        .put("reference", missingLyrics.absolutePath)
                        .put("createdByTransaction", true)
                        .put("deferredDelete", true)
                )
            )
        }
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context)

        assertEquals(2, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))

        assertTrue(futureVersion.exists())
        assertFalse(zeroTimestamp.exists())
        assertFalse(sidecars.exists())
        assertEquals(
            listOf("metadata-source-future.flac", "metadata-updated-future.flac", futureVersion.name),
            staging.list()!!.sorted()
        )
    }

    private fun recoveryContext(name: String): Context = mock(Context::class.java).also { context ->
        doReturn(temporaryFolder.newFolder("no-backup-$name")).`when`(context).noBackupFilesDir
    }

    private fun beginRecord(context: Context, name: String): LocalMetadataRecoveryRecord {
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("$name.flac").apply { writeText("original $name") }
        return LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = target.absolutePath,
            backupFile = File(staging, "metadata-source-$name.flac").apply { writeText("original $name") },
            updatedFile = File(staging, "metadata-updated-$name.flac").apply { writeText("updated $name") },
            originalLastModifiedMs = null
        )
    }

    private fun plantJournal(context: Context, name: String, overrides: JSONObject.() -> Unit = {}): File {
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val target = temporaryFolder.newFile("$name.flac").apply { writeText("original $name") }
        val backup = File(staging, "metadata-source-$name.flac").apply { writeText("original $name") }
        val updated = File(staging, "metadata-updated-$name.flac").apply { writeText("updated $name") }
        val body = JSONObject()
            .put("version", 1)
            .put("id", "planted-$name")
            .put("targetReference", target.absolutePath)
            .put("backupPath", backup.absolutePath)
            .put("updatedPath", updated.absolutePath)
            .put("originalSha256", sha256(backup))
            .put("updatedSha256", sha256(updated))
            .put("originalLastModifiedMs", JSONObject.NULL)
            .put("stage", LocalMetadataRecoveryStage.PREPARED.name)
            .apply(overrides)
        return File(staging, "recovery-planted-$name.json").apply { writeText(body.toString()) }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
