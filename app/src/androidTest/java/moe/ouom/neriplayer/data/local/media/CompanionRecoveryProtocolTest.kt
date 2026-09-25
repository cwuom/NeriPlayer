package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testing.awaitProcessDeathAtSeedCheckpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionRecoveryProtocolTest {
    private val provider = StagedMetadataTestProvider.CONTENT_URI
    private val original = "原内容 original lyric".toByteArray()
    private val intended = "新内容 intended lyric".toByteArray()

    @Test fun regularProviderRestoresEmptyUtf8PrefixAndFullWrite() = runBlocking {
        for (length in listOf(0, 1, 5, intended.size)) fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended.copyOf(length))
            recover(context, original)
        }
    }

    @Test fun restorationFailureKeepsDurablePhaseAcrossRetry() = runBlocking {
        for (prefix in listOf(-1, 0, 1, 8)) fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended.copyOf(4))
            context.contentResolver.call(provider, "failNextWrite", null, null)
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            val journal = requireNotNull(transaction.record).journalFile
            val entry = JSONObject(journal.readText()).getJSONArray("companions").getJSONObject(0)
            assertEquals("RESTORING", entry.getString("phase"))
            assertTrue(requireNotNull(transaction.record).companions.single().backupFile!!.exists())
            put(context, if (prefix < 0) intended.copyOf(4) else original.copyOf(prefix))
            recover(context, original)
            assertFalse(journal.exists())
        }
    }

    @Test fun repeatedIntentPreservesPreviousVerifiedWriteAndOriginalBackup() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.write(provider.toString(), intended)
            transaction.beforeWrite(provider.toString(), "third version".toByteArray())
            recover(context, original)
        }
    }

    @Test fun writtenThenDeferredDeleteStillRollsBackOriginal() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.write(provider.toString(), intended)
            transaction.deferDelete(provider.toString())
            recover(context, original)
        }
    }

    @Test fun createdThenRepeatedIntentRemovesOnlyTransactionObject() = runBlocking {
        fixture { context, directory ->
            val created = File(directory, "created.lrc").apply { writeBytes(byteArrayOf()) }
            val transaction = transaction(context, directory)
            transaction.created(created.absolutePath)
            transaction.write(created.absolutePath, intended, created = true)
            transaction.beforeWrite(created.absolutePath, original, created = true)
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertFalse(created.exists())
        }
    }

    @Test fun replacementInodeWithMatchingBytesAndUnknownContentArePreserved() = runBlocking {
        for (bytes in listOf(byteArrayOf(), intended.copyOf(4), intended, "external".toByteArray())) {
            fixture { context, directory ->
                val file = File(directory, "owner.lrc").apply { writeBytes(original) }
                val transaction = transaction(context, directory)
                transaction.beforeWrite(file.absolutePath, intended)
                val replacement = File(directory, "external.lrc").apply { writeBytes(bytes) }
                assertTrue(replacement.renameTo(file))
                LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
                assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
                assertArrayEquals(bytes, file.readBytes())
                assertTrue(requireNotNull(transaction.record).journalFile.isFile)
            }
        }
    }

    @Test fun repeatedAtomicIntentBeforePublicationRestoresOriginal() = runBlocking {
        fixture { context, directory ->
            val file = File(directory, "twice.lrc").apply { writeBytes(original) }
            val transaction = transaction(context, directory)
            transaction.write(file.absolutePath, intended)
            transaction.beforeWrite(file.absolutePath, "third version".toByteArray())
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun atomicPublicationFailureNeverCopiesOverChangedTarget() = runBlocking {
        fixture { context, directory ->
            val file = File(directory, "changed.lrc").apply { writeBytes(original) }
            val transaction = transaction(context, directory)
            transaction.beforeWrite(file.absolutePath, intended)
            val external = "external content".toByteArray()
            file.writeBytes(external)
            assertTrue(runCatching { transaction.publishPreparedFile(file.absolutePath) }.isFailure)
            assertArrayEquals(external, file.readBytes())
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(external, file.readBytes())
        }
    }

    @Test fun pipeSidecarFailsBeforeDestructiveOpen() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val pipe = provider.buildUpon().appendQueryParameter("pipe", "true").build()
            val result = runCatching { transaction(context, directory).write(pipe.toString(), intended) }
            assertTrue(result.isFailure)
            assertArrayEquals(original, get(context))
        }
    }

    @Test fun newlyCreatedUnknownProviderObjectKeepsRecoveryReference() = runBlocking {
        fixture { context, directory ->
            put(context, byteArrayOf())
            val pipe = provider.buildUpon().appendQueryParameter("pipe", "true").build()
            val transaction = transaction(context, directory)
            assertTrue(runCatching { transaction.created(pipe.toString()) }.isFailure)
            val record = requireNotNull(transaction.record)
            assertEquals(pipe.toString(), record.companions.single().reference)
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertTrue(record.journalFile.isFile)
            assertArrayEquals(byteArrayOf(), get(context))
        }
    }

    @Test fun legacyPartialAndCorruptIntentKeepBackup() = runBlocking {
        for (legacy in listOf(true, false)) fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended.copyOf(3))
            val record = requireNotNull(transaction.record)
            if (legacy) {
                val body = JSONObject(record.journalFile.readText())
                val entry = body.getJSONArray("companions").getJSONObject(0)
                entry.remove("phase")
                entry.remove("intendedPath")
                record.journalFile.writeText(body.toString())
            } else record.companions.single().intendedFile!!.writeText("corrupt")
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(intended.copyOf(3), get(context))
            assertTrue(record.companions.single().backupFile!!.exists())
        }
    }

    @Test fun journalPersistenceFailureLeavesOriginalUntouched() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            val record = requireNotNull(transaction.record)
            assertTrue(record.journalFile.delete())
            assertTrue(record.journalFile.mkdir())
            assertTrue(runCatching { transaction.write(provider.toString(), intended) }.isFailure)
            assertArrayEquals(original, get(context))
        }
    }

    @Test fun legacyCompleteWriteKeepsExactHashRecovery() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended)
            val record = requireNotNull(transaction.record)
            val body = JSONObject(record.journalFile.readText())
            val entry = body.getJSONArray("companions").getJSONObject(0)
            entry.remove("phase")
            entry.remove("intendedPath")
            record.journalFile.writeText(body.toString())
            recover(context, original)
        }
    }

    @Test fun sameInodeUnknownBytesRemainUnresolved() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            val external = "external content".toByteArray()
            put(context, external)
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(external, get(context))
            assertTrue(requireNotNull(transaction.record).journalFile.exists())
        }
    }

    @Test fun sameTransactionRollbackRetryRetainsRestoringEvidence() = runBlocking {
        fixture { context, directory ->
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended.copyOf(4))
            context.contentResolver.call(provider, "failNextWrite", null, null)
            assertFalse(transaction.rollback())
            put(context, original.copyOf(1))
            assertTrue(transaction.rollback())
            assertArrayEquals(original, get(context))
        }
    }

    @Test fun newlyCreatedRegularProviderWithNonEmptyBaselineAllowsFirstWrite() = runBlocking {
        fixture { context, directory ->
            val initial = "provider initial bytes".toByteArray()
            put(context, initial)
            val transaction = transaction(context, directory)
            transaction.created(provider.toString())
            assertTrue(transaction.write(provider.toString(), intended, created = true))
            assertArrayEquals(intended, get(context))
            assertEquals("WRITTEN", requireNotNull(transaction.record).companions.single().phase)
        }
    }

    @Test fun createdBaselineRecoveryDeletesOnlyCompleteInitialContent() = runBlocking {
        for (partial in listOf(false, true)) fixture { context, directory ->
            val initial = "provider initial bytes".toByteArray()
            val file = File(directory, "created-baseline.lrc").apply { writeBytes(initial) }
            val transaction = transaction(context, directory)
            transaction.created(file.absolutePath)
            if (partial) file.writeBytes(initial.copyOf(4))
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(if (partial) 0 else 1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            if (partial) {
                assertArrayEquals(initial.copyOf(4), file.readBytes())
                assertTrue(requireNotNull(transaction.record).journalFile.exists())
            } else assertFalse(file.exists())
        }
    }

    @Test fun createdBaselineRejectsReplacementAndChangedBytesBeforeFirstWrite() = runBlocking {
        for (replace in listOf(false, true)) fixture { context, directory ->
            val initial = "provider initial bytes".toByteArray()
            val file = File(directory, "created-conflict.lrc").apply { writeBytes(initial) }
            val transaction = transaction(context, directory)
            transaction.created(file.absolutePath)
            val expected = if (replace) initial else "external bytes".toByteArray()
            if (replace) {
                assertTrue(File(directory, "replacement.lrc").apply { writeBytes(expected) }.renameTo(file))
            } else file.writeBytes(expected)
            assertTrue(runCatching { transaction.write(file.absolutePath, intended, created = true) }.isFailure)
            assertArrayEquals(expected, file.readBytes())
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(expected, file.readBytes())
        }
    }

    @Test fun consumedCreatedBaselineDoesNotAuthorizeLaterExternalReversion() = runBlocking {
        fixture { context, directory ->
            val initial = "provider initial bytes".toByteArray()
            put(context, initial)
            val transaction = transaction(context, directory)
            transaction.created(provider.toString())
            assertTrue(transaction.write(provider.toString(), intended, created = true))
            transaction.created(provider.toString())
            assertEquals("WRITTEN", requireNotNull(transaction.record).companions.single().phase)
            put(context, initial)
            assertTrue(runCatching { transaction.beforeWrite(provider.toString(), original, created = true) }.isFailure)
            assertArrayEquals(initial, get(context))
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertArrayEquals(initial, get(context))
        }
    }

    @Test fun confirmedWritesNeverAuthorizeExternalTruncation() = runBlocking {
        for (created in listOf(false, true)) {
            for (length in listOf(0, 4)) fixture { context, directory ->
                put(context, "provider initial bytes".toByteArray())
                val transaction = transaction(context, directory)
                if (created) transaction.created(provider.toString())
                assertTrue(transaction.write(provider.toString(), intended, created = created))
                val external = intended.copyOf(length)
                put(context, external)
                LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
                assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
                assertArrayEquals(external, get(context))
                assertTrue(requireNotNull(transaction.record).journalFile.exists())
            }
        }
    }

    @Test fun processDeathSeedRecover() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("task4RecoveryPhase")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "task4-process-recovery")
        val context = isolatedContext(base, directory)
        val pid = File(directory, "seed-pid")
        if (phase != "recover") {
            check(!directory.exists()) { "existing Task4 recovery fixture must be recovered first" }
            check(directory.mkdirs())
            put(context, original)
            val transaction = transaction(context, directory)
            transaction.beforeWrite(provider.toString(), intended)
            put(context, intended.copyOf(5))
            pid.writeText(Process.myPid().toString())
            if (phase == "seed") {
                awaitProcessDeathAtSeedCheckpoint()
                return@runBlocking
            }
        }
        try {
            assertTrue(directory.isDirectory)
            if (phase == "recover") assertNotEquals(pid.readText(), Process.myPid().toString())
            recover(context, original)
        } finally {
            context.contentResolver.delete(provider, null, null)
            directory.deleteRecursively()
        }
    }

    private fun transaction(context: Context, directory: File): LocalMediaCompanionTransaction =
        LocalMediaCompanionTransaction(context, File(directory, "unchanged.wav").absolutePath).apply { initializeSidecarsOnly() }

    private suspend fun recover(context: Context, expected: ByteArray) {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
        assertArrayEquals(expected, get(context))
        assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
    }

    private fun put(context: Context, bytes: ByteArray) {
        context.contentResolver.openOutputStream(provider, "wt")!!.use { it.write(bytes) }
    }
    private fun get(context: Context): ByteArray = context.contentResolver.openInputStream(provider)!!.use { it.readBytes() }

    private fun isolatedContext(base: Context, directory: File): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(directory, "recovery").apply { mkdirs() }
    }
    private suspend fun fixture(block: suspend (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "protocol-${System.nanoTime()}").apply { check(mkdir()) }
        val context = isolatedContext(base, directory)
        try { block(context, directory) } finally {
            context.contentResolver.delete(provider, null, null)
            directory.deleteRecursively()
        }
    }
}
