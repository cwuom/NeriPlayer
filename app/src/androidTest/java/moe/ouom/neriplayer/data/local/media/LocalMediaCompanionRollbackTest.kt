package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContextWrapper
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaCompanionRollbackTest {
    @Test
    fun privateCoverFailureRestoresAllPreviouslyWrittenCompanions() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "song.wav").apply { writeBytes(byteArrayOf(0)) }
            val lyrics = listOf("song.lrc", "song_trans.lrc", "song_roma.lrc").mapIndexed { index, name ->
                File(directory, name).apply { writeText("[00:01.00]old-$index") }
            }
            val metadata = File(directory, "song.wav.npmeta.json").apply { writeText("{\"customName\":\"original\"}") }
            val before = (lyrics + metadata + audio).associateWith(File::readBytes)

            val outcome = LocalMediaSupport.writeEditableMetadata(
                context, song(audio.toURI().toString(), audio.name, audio.absolutePath),
                coverReference = File(directory, "missing-cover.png").absolutePath,
                writeCover = true, writeLyrics = true
            )

            assertFailed(outcome)
            before.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        }
    }

    @Test
    fun privateMetadataFailureRestoresLyricsWrittenEarlierInTheAttempt() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "song.wav").apply { writeBytes(byteArrayOf(0)) }
            val lyric = File(directory, "song.lrc").apply { writeText("[00:01.00]old") }
            val metadataObstacle = File(directory, "song.wav.npmeta.json").apply { check(mkdir()) }
            val before = lyric.readBytes()

            val outcome = LocalMediaSupport.writeEditableMetadata(
                context, song(audio.toURI().toString(), audio.name, audio.absolutePath),
                writeCover = false, writeLyrics = true
            )

            assertFailed(outcome)
            assertArrayEquals(before, lyric.readBytes())
            assertFalse(File(directory, "song_trans.lrc").exists())
            assertFalse(File(directory, "song_roma.lrc").exists())
            assertTrue(metadataObstacle.isDirectory)
        }
    }

    @Test
    fun safCoverFailureRestoresAllPreviouslyWrittenCompanions() = runBlocking {
        withFixture { context, directory ->
            val provider = DocumentsContract.buildDocumentUri(
                Issue339LyricsTestDocumentProvider.AUTHORITY, Issue339LyricsTestDocumentProvider.ROOT_ID
            )
            context.contentResolver.call(provider, Issue339LyricsTestDocumentProvider.RESET_LYRICS, null, null)
            context.contentResolver.call(provider, Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA, null, null)
            LocalMediaSupport.clearLyricsLookupCache()
            val ids = listOf(
                Issue339LyricsTestDocumentProvider.ORIGINAL_ID,
                Issue339LyricsTestDocumentProvider.TRANSLATED_ID,
                Issue339LyricsTestDocumentProvider.ROMANIZED_ID,
                Issue339LyricsTestDocumentProvider.METADATA_ID
            )
            val references = ids.map { DocumentsContract.buildDocumentUri(Issue339LyricsTestDocumentProvider.AUTHORITY, it).toString() }
            val before = references.associateWith { requireNotNull(LocalMediaSupport.readTextContent(context, it)) }
            val audioUri = DocumentsContract.buildDocumentUri(Issue339LyricsTestDocumentProvider.AUTHORITY, Issue339LyricsTestDocumentProvider.AUDIO_ID)
            try {
                val outcome = LocalMediaSupport.writeEditableMetadata(
                    context, song(audioUri.toString(), Issue339LyricsTestDocumentProvider.AUDIO_NAME),
                    coverReference = File(directory, "missing-cover.png").absolutePath,
                    writeCover = true, writeLyrics = true
                )

                assertFailed(outcome)
                before.forEach { (reference, content) ->
                    assertEquals(reference, content, LocalMediaSupport.readTextContent(context, reference))
                }
            } finally {
                context.contentResolver.call(provider, Issue339LyricsTestDocumentProvider.RESET_LYRICS, null, null)
                LocalMediaSupport.clearLyricsLookupCache()
            }
        }
    }

    @Test
    fun committedCleanupConflictIsRecoveredWithoutFailingTheCommittedEdit() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "committed.wav").apply { writeBytes(byteArrayOf(0)) }
            val obsolete = File(directory, "committed-old.lrc").apply { writeText("old") }
            val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
            transaction.initializeSidecarsOnly()
            transaction.deferDelete(obsolete.absolutePath)

            obsolete.writeText("external owner")
            transaction.commit()

            val record = requireNotNull(transaction.record)
            assertEquals("external owner", obsolete.readText())
            assertTrue(record.journalFile.exists())

            obsolete.writeText("old")
            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertFalse(obsolete.exists())
            assertFalse(record.journalFile.exists())
        }
    }

    @Test
    fun interruptedCompanionWriteRestoresTheWholePreEditSidecar() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "interrupted.wav").apply { writeBytes(byteArrayOf(0)) }
            val lyric = File(directory, "interrupted.lrc").apply { writeText("old lyric") }
            val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
            transaction.initializeSidecarsOnly()
            transaction.beforeWrite(lyric.absolutePath, "new lyric".toByteArray())
            lyric.writeText("new lyric")
            transaction.afterWrite(lyric.absolutePath)
            val record = requireNotNull(transaction.record)

            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals("old lyric", lyric.readText())
            assertFalse(record.journalFile.exists())
        }
    }

    @Test
    fun rollbackRestoresOwnAtomicWriteInterruptedBeforeIdentityConfirmation() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "pre-confirmation.wav").apply { writeBytes(byteArrayOf(0)) }
            val lyric = File(directory, "pre-confirmation.lrc").apply { writeText("old lyric") }
            val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
            transaction.initializeSidecarsOnly()
            transaction.beforeWrite(lyric.absolutePath, "new lyric".toByteArray())
            transaction.publishPreparedFile(lyric.absolutePath)
            val record = requireNotNull(transaction.record)

            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals("old lyric", lyric.readText())
            assertFalse(record.journalFile.exists())
        }
    }

    @Test
    fun rollbackPreservesAReplacementInodeEvenWhenItsBytesMatchTheAttempt() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "identity.wav").apply { writeBytes(byteArrayOf(0)) }
            val lyric = File(directory, "identity.lrc").apply { writeText("old lyric") }
            val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
            transaction.initializeSidecarsOnly()
            transaction.beforeWrite(lyric.absolutePath, "new lyric".toByteArray())
            lyric.writeText("new lyric")
            transaction.afterWrite(lyric.absolutePath)
            val record = requireNotNull(transaction.record)

            val externalReplacement = File(directory, "external.lrc").apply {
                writeText("new lyric")
            }
            assertTrue(lyric.delete())
            assertTrue(externalReplacement.renameTo(lyric))

            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals("new lyric", lyric.readText())
            assertTrue(record.journalFile.exists())
        }
    }

    @Test
    fun rollbackContinuesPastAConflictingCompanionAndRestoresOwnedArtifacts() = runBlocking {
        withFixture { context, directory ->
            val audio = File(directory, "group.wav").apply { writeText("old audio") }
            val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply {
                check(mkdirs())
            }
            val backup = File(staging, "group-backup.wav").apply { writeText("old audio") }
            val updated = File(staging, "group-updated.wav").apply { writeText("new audio") }
            val first = File(directory, "group.lrc").apply { writeText("old first") }
            val conflicting = File(directory, "group_trans.lrc").apply { writeText("old conflict") }
            val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
            transaction.initialize(backup, updated, audio.lastModified())
            transaction.prepareUpdatedAudio()
            audio.writeText("new audio")
            transaction.audioVerified()
            transaction.beforeWrite(first.absolutePath, "new first".toByteArray())
            first.writeText("new first")
            transaction.afterWrite(first.absolutePath)
            transaction.beforeWrite(conflicting.absolutePath, "new conflict".toByteArray())
            conflicting.writeText("new conflict")
            transaction.afterWrite(conflicting.absolutePath)
            conflicting.writeText("external owner")
            val record = requireNotNull(transaction.record)

            LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
            assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            assertEquals("old audio", audio.readText())
            assertEquals("old first", first.readText())
            assertEquals("external owner", conflicting.readText())
            assertTrue(record.journalFile.exists())
        }
    }

    @Test
    fun partialCompanionWriteRestoresOriginalBeforeAfterWrite() = runBlocking {
        for (partial in listOf("", "new", "new lyric")) {
            withFixture { context, directory ->
                val audio = File(directory, "partial.wav").apply { writeText("audio") }
                val lyric = File(directory, "partial.lrc").apply { writeText("old lyric") }
                val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)
                transaction.beforeWrite(lyric.absolutePath, "new lyric".toByteArray())
                lyric.writeText(partial)
                LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
                assertEquals("partial=$partial", 1, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
                assertEquals("old lyric", lyric.readText())
                assertEquals(0, LocalMediaMetadataRecoveryStore.recoverInterruptedWrites(context))
            }
        }
    }

    private fun assertFailed(outcome: LocalMediaMetadataWriteOutcome) {
        assertFalse(outcome == LocalMediaMetadataWriteOutcome.SUCCESS)
        assertFalse(outcome == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY)
    }

    private fun song(uri: String, name: String, path: String? = null) = SongItem(
        id = 339L, name = "edited title", artist = "edited artist", album = "Local", albumId = 0L,
        durationMs = 1_000L, coverUrl = null, mediaUri = uri, localFileName = name, localFilePath = path,
        matchedLyric = "[00:02.00]new original", matchedTranslatedLyric = "[00:02.00]new translated",
        matchedRomanizedLyric = "[00:02.00]new romanized"
    )

    private suspend fun withFixture(block: suspend (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "companion-rollback-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(directory, "recovery").apply { mkdirs() }
        }
        try {
            block(context, directory)
        } finally {
            LocalMediaSupport.clearLyricsLookupCache()
            check(directory.deleteRecursively())
        }
    }
}
