package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.data.settings.SettingsRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadStorageMigrationInstrumentedTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val providerRootUri = DocumentsContract.buildDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private val privateDirectories = mutableListOf<File>()
    private var previousDirectoryUri: String? = null

    @Before
    fun resetMigrationFixture() {
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        appContext.contentResolver.call(
            providerRootUri,
            ManagedDownloadMigrationTestDocumentProvider.RESET,
            null,
            null
        )
        ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
    }

    @After
    fun restoreMigrationFixture() = runBlocking {
        appContext.contentResolver.call(
            providerRootUri,
            ManagedDownloadMigrationTestDocumentProvider.RESET,
            null,
            null
        )
        SettingsRepository(appContext).setDownloadDirectory(previousDirectoryUri, null)
        ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        privateDirectories.forEach(File::deleteRecursively)
        privateDirectories.clear()
    }

    @Test
    fun privateSafPrivateRoundTripPreservesManagedAudioMetadataAndSidecars() = runBlocking {
        val context = isolatedPrivateContext()
        val fixture = writePrivateFixture(context)
        val treeUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)

        val toSaf = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri.toString()
        )

        assertTrue(toSaf.canSwitchDirectory)
        assertEquals(0, toSaf.cleanupFailedFiles)
        assertFalse(fixture.audio.exists())
        assertFalse(fixture.metadata.exists())
        assertFalse(fixture.cover.exists())
        assertFalse(fixture.lyric.exists())
        assertFalse(fixture.translatedLyric.exists())
        assertFalse(fixture.romanizedLyric.exists())

        val treeRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        val targetAudio = requireTreeFile(treeRoot, fixture.audio.name)
        val targetMetadata = requireTreeFile(treeRoot, fixture.metadata.name)
        val targetCover = requireTreeFile(requireTreeDirectory(treeRoot, "Covers"), fixture.cover.name)
        val targetLyrics = requireTreeDirectory(treeRoot, "Lyrics")
        val targetLyric = requireTreeFile(targetLyrics, fixture.lyric.name)
        val targetTranslatedLyric = requireTreeFile(targetLyrics, fixture.translatedLyric.name)
        val targetRomanizedLyric = requireTreeFile(targetLyrics, fixture.romanizedLyric.name)

        assertArrayEquals(fixture.audioBytes, readDocument(targetAudio))
        assertArrayEquals(fixture.coverBytes, readDocument(targetCover))
        assertArrayEquals(fixture.lyricBytes, readDocument(targetLyric))
        assertArrayEquals(fixture.translatedLyricBytes, readDocument(targetTranslatedLyric))
        assertArrayEquals(fixture.romanizedLyricBytes, readDocument(targetRomanizedLyric))
        val safMetadata = JSONObject(readDocument(targetMetadata).decodeToString())
        assertSameDocument(targetAudio.uri, safMetadata.getString("mediaUri"))
        assertSameDocument(targetCover.uri, safMetadata.getString("coverPath"))
        assertSameDocument(targetLyric.uri, safMetadata.getString("lyricPath"))
        assertSameDocument(
            targetTranslatedLyric.uri,
            safMetadata.getString("translatedLyricPath")
        )
        assertSameDocument(
            targetRomanizedLyric.uri,
            safMetadata.getString("romanizedLyricPath")
        )

        val resumedAfterCommittedCleanup = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri.toString(),
            minimumSourceEntryCount = 1,
            targetPreviouslyCommitted = true
        )
        assertTrue(resumedAfterCommittedCleanup.canSwitchDirectory)
        assertEquals(0, resumedAfterCommittedCleanup.cleanupFailedFiles)
        assertArrayEquals(fixture.audioBytes, readDocument(targetAudio))

        val toPrivate = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = treeUri.toString(),
            toDirectoryUri = null
        )

        assertTrue(toPrivate.canSwitchDirectory)
        assertEquals(0, toPrivate.cleanupFailedFiles)
        assertNull(treeRoot.findFile(fixture.audio.name))
        assertNull(treeRoot.findFile(fixture.metadata.name))
        val privateRoot = defaultRoot(context)
        val restoredAudio = File(privateRoot, fixture.audio.name)
        val restoredMetadata = File(privateRoot, fixture.metadata.name)
        val restoredCover = File(File(privateRoot, "Covers"), fixture.cover.name)
        val restoredLyric = File(File(privateRoot, "Lyrics"), fixture.lyric.name)
        val restoredTranslatedLyric = File(File(privateRoot, "Lyrics"), fixture.translatedLyric.name)
        val restoredRomanizedLyric = File(File(privateRoot, "Lyrics"), fixture.romanizedLyric.name)

        assertArrayEquals(fixture.audioBytes, restoredAudio.readBytes())
        assertArrayEquals(fixture.coverBytes, restoredCover.readBytes())
        assertArrayEquals(fixture.lyricBytes, restoredLyric.readBytes())
        assertArrayEquals(fixture.translatedLyricBytes, restoredTranslatedLyric.readBytes())
        assertArrayEquals(fixture.romanizedLyricBytes, restoredRomanizedLyric.readBytes())
        val privateMetadata = JSONObject(restoredMetadata.readText())
        assertEquals(restoredAudio.toURI().toString(), privateMetadata.getString("mediaUri"))
        assertEquals(restoredCover.toURI().toString(), privateMetadata.getString("coverPath"))
        assertEquals(restoredLyric.toURI().toString(), privateMetadata.getString("lyricPath"))
        assertEquals(
            restoredTranslatedLyric.toURI().toString(),
            privateMetadata.getString("translatedLyricPath")
        )
        assertEquals(
            restoredRomanizedLyric.toURI().toString(),
            privateMetadata.getString("romanizedLyricPath")
        )
    }

    @Test
    fun privateMigrationDeletesTemporaryDirectoryAsOneTree() = runBlocking {
        val context = isolatedPrivateContext()
        val fixture = writePrivateFixture(context)
        val temporary = File(defaultRoot(context), ".tmp").apply { mkdirs() }
        File(temporary, "nested/deep/unfinished.part").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(32) { 7 })
        }
        val treeUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)

        val result = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri.toString()
        )

        assertTrue(result.canSwitchDirectory)
        assertFalse(temporary.exists())
        val targetRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        assertArrayEquals(
            fixture.audioBytes,
            readDocument(requireTreeFile(targetRoot, fixture.audio.name))
        )
    }

    @Test
    fun safMigrationDeletesTemporaryDirectoryAsOneTree() = runBlocking {
        val sourceRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID)
        val targetRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID)
        writeTreeFixture(sourceRoot)
        val temporary = requireNotNull(sourceRoot.createDirectory(".tmp"))
        val nested = requireNotNull(temporary.createDirectory("nested"))
        writeDocument(nested, "unfinished.part", "application/octet-stream", ByteArray(32) { 9 })

        val result = ManagedDownloadStorage.migrateManagedDownloads(
            context = appContext,
            fromDirectoryUri = treeUri(
                ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
            ).toString(),
            toDirectoryUri = treeUri(
                ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID
            ).toString()
        )

        assertTrue(result.canSwitchDirectory)
        assertNull(sourceRoot.findFile(".tmp"))
        assertArrayEquals(
            "audio-payload".encodeToByteArray(),
            readDocument(requireTreeFile(targetRoot, "RoundTrip.mp3"))
        )
    }

    @Test
    fun sameSongInTargetIsOverwrittenBySourceAudioAndMetadata() = runBlocking {
        val context = isolatedPrivateContext()
        val root = defaultRoot(context).apply { mkdirs() }
        val sourceBytes = "source-audio".encodeToByteArray()
        val sourceAudio = File(root, "Overwrite.mp3").apply { writeBytes(sourceBytes) }
        val sourceMetadata = File(root, "${sourceAudio.name}.npmeta.json").apply {
            writeText(
                metadataJson(
                    stableKey = "overwrite-song",
                    mediaUri = sourceAudio.toURI().toString(),
                    coverPath = null,
                    lyricPath = null,
                    translatedLyricPath = null,
                    romanizedLyricPath = null
                )
            )
        }
        val targetRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        val oldAudio = writeDocument(
            targetRoot,
            sourceAudio.name,
            "audio/mpeg",
            "old-target".encodeToByteArray()
        )
        writeDocument(
            targetRoot,
            sourceMetadata.name,
            "application/json",
            metadataJson(
                stableKey = "overwrite-song",
                mediaUri = oldAudio.uri.toString(),
                coverPath = null,
                lyricPath = null,
                translatedLyricPath = null,
                romanizedLyricPath = null
            ).encodeToByteArray()
        )

        val result = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri(
                ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
            ).toString()
        )

        assertTrue(result.canSwitchDirectory)
        assertFalse(sourceAudio.exists())
        assertFalse(sourceMetadata.exists())
        val migratedAudio = requireTreeFile(targetRoot, sourceAudio.name)
        assertArrayEquals(sourceBytes, readDocument(migratedAudio))
        val migratedMetadata = JSONObject(
            readDocument(requireTreeFile(targetRoot, sourceMetadata.name)).decodeToString()
        )
        assertSameDocument(migratedAudio.uri, migratedMetadata.getString("mediaUri"))
        assertTrue(
            targetRoot.listFiles().none { file ->
                file.name.orEmpty().startsWith(".np-migration-backup-")
            }
        )
    }

    @Test
    fun manifestOnlySafRootDoesNotRequireMigration() = runBlocking {
        val rootId = ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
        val treeRoot = treeRoot(rootId)
        writeDocument(
            parent = treeRoot,
            name = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
            mimeType = "application/json",
            content = JSONObject()
                .put("libraryId", "manifest-only-library")
                .toString()
                .encodeToByteArray()
        )

        assertFalse(
            ManagedDownloadStorage.hasMigratableDownloads(
                context = appContext,
                directoryUri = treeUri(rootId).toString()
            )
        )
    }

    @Test
    fun committedCleanupResumeRejectsAnIncompleteTarget() = runBlocking {
        val context = isolatedPrivateContext()
        val fixture = writePrivateFixture(context)
        val treeUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        var discoveredMinimumAudioCount = 0
        val initialMigration = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri.toString(),
            minimumSourceEntryCount = 0,
            onSourceAudioCountResolved = { count ->
                discoveredMinimumAudioCount = count
            }
        )
        assertTrue(initialMigration.canSwitchDirectory)
        assertEquals(1, discoveredMinimumAudioCount)

        val treeRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        assertTrue(requireTreeFile(treeRoot, fixture.audio.name).delete())
        var failure: ManagedDownloadMigrationException? = null
        try {
            ManagedDownloadStorage.migrateManagedDownloads(
                context = context,
                fromDirectoryUri = null,
                toDirectoryUri = treeUri.toString(),
                minimumSourceEntryCount = discoveredMinimumAudioCount,
                targetPreviouslyCommitted = true
            )
        } catch (error: ManagedDownloadMigrationException) {
            failure = error
        }

        assertNotNull(failure)
        assertTrue(requireNotNull(failure).retryable)
    }

    @Test
    fun committedCleanupResumeWithRemainingSidecarRejectsAnIncompleteTarget() = runBlocking {
        val context = isolatedPrivateContext()
        val fixture = writePrivateFixture(context)
        val metadataBytes = fixture.metadata.readBytes()
        val treeUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        val initialMigration = ManagedDownloadStorage.migrateManagedDownloads(
            context = context,
            fromDirectoryUri = null,
            toDirectoryUri = treeUri.toString()
        )
        assertTrue(initialMigration.canSwitchDirectory)

        val treeRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        assertTrue(requireTreeFile(treeRoot, fixture.audio.name).delete())
        assertNull(treeRoot.findFile(fixture.audio.name))
        val privateRoot = defaultRoot(context)
        val remainingMetadata = File(privateRoot, fixture.metadata.name).apply {
            parentFile?.mkdirs()
            writeBytes(metadataBytes)
        }

        var failure: ManagedDownloadMigrationException? = null
        var migrationResult: ManagedDownloadStorage.MigrationResult? = null
        try {
            migrationResult = ManagedDownloadStorage.migrateManagedDownloads(
                context = context,
                fromDirectoryUri = null,
                toDirectoryUri = treeUri.toString(),
                minimumSourceEntryCount = 1,
                targetPreviouslyCommitted = true
            )
        } catch (error: ManagedDownloadMigrationException) {
            failure = error
        }

        assertNotNull(
            "expected incomplete target failure; result=$migrationResult, " +
                "target=${treeRoot.listFiles().mapNotNull(DocumentFile::getName)}",
            failure
        )
        assertTrue(requireNotNull(failure).retryable)
        assertTrue(remainingMetadata.exists())
    }

    @Test
    fun privateAndSafSixteenMiBMigrationCompletesWithinTenSecondsPerDirection() {
        runBlocking {
        val context = isolatedPrivateContext()
        val root = defaultRoot(context).apply { mkdirs() }
        val payload = ByteArray(1024 * 1024) { index -> (index % 251).toByte() }
        repeat(16) { index ->
            val audio = File(root, "Migration-$index.mp3").apply { writeBytes(payload) }
            File(root, "${audio.name}.npmeta.json").writeText(
                metadataJson(
                    stableKey = "migration-$index",
                    mediaUri = audio.toURI().toString(),
                    coverPath = null,
                    lyricPath = null,
                    translatedLyricPath = null,
                    romanizedLyricPath = null
                )
            )
        }
        val treeUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)

        val toSafElapsedMs = measureTimeMillis {
            val result = ManagedDownloadStorage.migrateManagedDownloads(
                context = context,
                fromDirectoryUri = null,
                toDirectoryUri = treeUri.toString()
            )
            assertTrue(result.canSwitchDirectory)
            assertEquals(0, result.cleanupFailedFiles)
        }
        assertTrue("private to SAF took ${toSafElapsedMs}ms", toSafElapsedMs < 10_000L)
        val treeRoot = treeRoot(ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
        repeat(16) { index ->
            val name = "Migration-$index.mp3"
            val targetAudio = requireTreeFile(treeRoot, name)
            val targetMetadata = requireTreeFile(treeRoot, "$name.npmeta.json")
            assertArrayEquals(payload, readDocument(targetAudio))
            assertSameDocument(
                targetAudio.uri,
                JSONObject(readDocument(targetMetadata).decodeToString()).getString("mediaUri")
            )
            assertFalse(File(root, name).exists())
        }

        val toPrivateElapsedMs = measureTimeMillis {
            val result = ManagedDownloadStorage.migrateManagedDownloads(
                context = context,
                fromDirectoryUri = treeUri.toString(),
                toDirectoryUri = null
            )
            assertTrue(result.canSwitchDirectory)
            assertEquals(0, result.cleanupFailedFiles)
        }
        assertTrue("SAF to private took ${toPrivateElapsedMs}ms", toPrivateElapsedMs < 10_000L)
        assertEquals(16, root.listFiles()?.count { it.extension == "mp3" })
        repeat(16) { index ->
            val name = "Migration-$index.mp3"
            val restoredAudio = File(root, name)
            val restoredMetadata = File(root, "$name.npmeta.json")
            assertArrayEquals(payload, restoredAudio.readBytes())
            assertEquals(
                restoredAudio.toURI().toString(),
                JSONObject(restoredMetadata.readText()).getString("mediaUri")
            )
        }
            Log.i(
                MIGRATION_TEST_TAG,
                "private_to_saf_ms=$toSafElapsedMs saf_to_private_ms=$toPrivateElapsedMs bytes=${payload.size * 16}"
            )
        }
    }

    @Test
    fun workerPersistsSafMigrationWithMetadataAndSidecars() = runBlocking {
        val sourceTree = treeRoot(ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID)
        val targetTree = treeRoot(ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID)
        writeTreeFixture(sourceTree)
        val sourceUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID)
        val targetUri = treeUri(ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID)

        val workId = ManagedDownloadMigrationWorker.enqueueOrGetActiveWorkId(
            context = appContext,
            fromDirectoryUri = sourceUri.toString(),
            toDirectoryUri = targetUri.toString(),
            targetLabel = "worker-target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1
        )
        val workInfo = awaitWork(workId)

        assertEquals(WorkInfo.State.SUCCEEDED, workInfo.state)
        val targetAudio = requireTreeFile(targetTree, "RoundTrip.mp3")
        val targetMetadata = requireTreeFile(targetTree, "RoundTrip.mp3.npmeta.json")
        val targetCover = requireTreeFile(requireTreeDirectory(targetTree, "Covers"), "RoundTrip.jpg")
        val targetLyrics = requireTreeDirectory(targetTree, "Lyrics")
        val targetLyric = requireTreeFile(targetLyrics, "RoundTrip.lrc")
        val targetTranslated = requireTreeFile(targetLyrics, "RoundTrip_trans.lrc")
        val targetRomanized = requireTreeFile(targetLyrics, "RoundTrip_roma.lrc")
        val metadata = JSONObject(readDocument(targetMetadata).decodeToString())
        assertSameDocument(targetAudio.uri, metadata.getString("mediaUri"))
        assertSameDocument(targetCover.uri, metadata.getString("coverPath"))
        assertSameDocument(targetLyric.uri, metadata.getString("lyricPath"))
        assertSameDocument(targetTranslated.uri, metadata.getString("translatedLyricPath"))
        assertSameDocument(targetRomanized.uri, metadata.getString("romanizedLyricPath"))
        assertNull(sourceTree.findFile("RoundTrip.mp3"))
        assertNull(sourceTree.findFile("RoundTrip.mp3.npmeta.json"))
        assertEquals(targetUri.toString(), ManagedDownloadStorage.configuredDirectoryUri())
    }

    @Test
    fun safBackendDeleteRequiresProviderMissingConfirmation() = runBlocking {
        val sourceTree = treeRoot(ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID)
        writeTreeFixture(sourceTree)
        val audio = requireTreeFile(sourceTree, "RoundTrip.mp3")
        val backend = SafStorageBackend(appContext)

        val result = backend.delete(
            TrustedManagedRef(StorageReference.SafRef(audio.uri))
        )

        assertEquals(StorageMutationResult.Deleted, result)
        assertEquals(
            StorageLookupResult.Missing,
            backend.stat(StorageReference.SafRef(audio.uri))
        )
    }

    @Test
    fun finalizedPendingAudioPromotesFromTreeChildAfterStoredUriIsMissing() = runBlocking {
        val rootId = ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
        val treeUri = treeUri(rootId)
        val treeRoot = treeRoot(rootId)
        val finalName = "Recovered.mp3"
        val pendingName = "$finalName.npdl_pending.stale-uri.pending"
        val payload = "pending-audio-payload".encodeToByteArray()
        val pendingDocument = writeDocument(
            parent = treeRoot,
            name = pendingName,
            mimeType = "application/octet-stream",
            content = payload
        )
        writeDocument(
            parent = treeRoot,
            name = "$finalName.npmeta.json",
            mimeType = "application/json",
            content = JSONObject(
                metadataJson(
                    stableKey = "stale-pending-uri",
                    mediaUri = pendingDocument.uri.toString(),
                    coverPath = null,
                    lyricPath = null,
                    translatedLyricPath = null,
                    romanizedLyricPath = null
                )
            ).apply {
                put("downloadFinalized", true)
                put(
                    "metadataEmbeddingState",
                    DownloadedAudioEmbeddingState.USER_DISABLED.name
                )
            }.toString().encodeToByteArray()
        )
        ManagedDownloadStorage.primeSettings(treeUri.toString(), "test-tree")
        val missingReference = DocumentsContract.buildDocumentUri(
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
            "missing-pending-document"
        )

        val promoted = ManagedDownloadStorage.promoteFinalizedPendingAudio(
            context = appContext,
            audio = ManagedDownloadStorage.StoredEntry(
                name = pendingName,
                reference = missingReference.toString(),
                mediaUri = missingReference.toString(),
                localFilePath = null,
                sizeBytes = payload.size.toLong(),
                lastModifiedMs = System.currentTimeMillis()
            )
        )

        assertEquals(finalName, requireNotNull(promoted).audio.name)
        assertArrayEquals(payload, readDocument(requireTreeFile(treeRoot, finalName)))
        assertNull(treeRoot.findFile(pendingName))
    }

    private suspend fun awaitWork(workId: String): WorkInfo {
        val deadlineMs = SystemClock.elapsedRealtime() + 30_000L
        val uuid = java.util.UUID.fromString(workId)
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val workInfo = WorkManager.getInstance(appContext).getWorkInfoById(uuid).get()
            if (workInfo != null && workInfo.state.isFinished) {
                return workInfo
            }
            delay(100L)
        }
        throw AssertionError("migration worker did not finish within 30000ms")
    }

    private fun isolatedPrivateContext(): Context {
        val directory = File(
            appContext.cacheDir,
            "managed-download-migration-private-${System.nanoTime()}"
        ).apply { mkdirs() }
        privateDirectories += directory
        return object : ContextWrapper(appContext) {
            override fun getExternalFilesDir(type: String?): File {
                return File(directory, type ?: "files").apply { mkdirs() }
            }
        }
    }

    private fun defaultRoot(context: Context): File {
        return File(
            requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)),
            ROOT_DIR_NAME
        )
    }

    private fun treeUri(rootId: String) = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        rootId
    )

    private fun treeRoot(rootId: String): DocumentFile {
        return requireNotNull(DocumentFile.fromTreeUri(appContext, treeUri(rootId)))
    }

    private fun writePrivateFixture(context: Context): PrivateFixture {
        val root = defaultRoot(context).apply { mkdirs() }
        val audioBytes = "audio-payload".encodeToByteArray()
        val coverBytes = "cover-payload".encodeToByteArray()
        val lyricBytes = "[00:00.00]original".encodeToByteArray()
        val translatedLyricBytes = "[00:00.00]translated".encodeToByteArray()
        val romanizedLyricBytes = "[00:00.00]romanized".encodeToByteArray()
        val audio = File(root, "RoundTrip.mp3").apply { writeBytes(audioBytes) }
        val cover = File(File(root, "Covers").apply { mkdirs() }, "RoundTrip.jpg")
            .apply { writeBytes(coverBytes) }
        val lyricDirectory = File(root, "Lyrics").apply { mkdirs() }
        val lyric = File(lyricDirectory, "RoundTrip.lrc").apply { writeBytes(lyricBytes) }
        val translatedLyric = File(lyricDirectory, "RoundTrip_trans.lrc")
            .apply { writeBytes(translatedLyricBytes) }
        val romanizedLyric = File(lyricDirectory, "RoundTrip_roma.lrc")
            .apply { writeBytes(romanizedLyricBytes) }
        val metadata = File(root, "${audio.name}.npmeta.json").apply {
            writeText(
                metadataJson(
                    stableKey = "42|__local_files__|${audio.toURI()}",
                    mediaUri = audio.toURI().toString(),
                    coverPath = cover.toURI().toString(),
                    lyricPath = lyric.toURI().toString(),
                    translatedLyricPath = translatedLyric.toURI().toString(),
                    romanizedLyricPath = romanizedLyric.toURI().toString()
                )
            )
        }
        return PrivateFixture(
            audio = audio,
            metadata = metadata,
            cover = cover,
            lyric = lyric,
            translatedLyric = translatedLyric,
            romanizedLyric = romanizedLyric,
            audioBytes = audioBytes,
            coverBytes = coverBytes,
            lyricBytes = lyricBytes,
            translatedLyricBytes = translatedLyricBytes,
            romanizedLyricBytes = romanizedLyricBytes
        )
    }

    private fun writeTreeFixture(root: DocumentFile) {
        val audio = writeDocument(root, "RoundTrip.mp3", "audio/mpeg", "audio-payload".encodeToByteArray())
        val covers = requireNotNull(root.createDirectory("Covers"))
        val cover = writeDocument(covers, "RoundTrip.jpg", "image/jpeg", "cover-payload".encodeToByteArray())
        val lyrics = requireNotNull(root.createDirectory("Lyrics"))
        val lyric = writeDocument(lyrics, "RoundTrip.lrc", "text/plain", "[00:00.00]original".encodeToByteArray())
        val translated = writeDocument(
            lyrics,
            "RoundTrip_trans.lrc",
            "text/plain",
            "[00:00.00]translated".encodeToByteArray()
        )
        val romanized = writeDocument(
            lyrics,
            "RoundTrip_roma.lrc",
            "text/plain",
            "[00:00.00]romanized".encodeToByteArray()
        )
        writeDocument(
            root,
            "RoundTrip.mp3.npmeta.json",
            "application/json",
            metadataJson(
                stableKey = "worker-round-trip",
                mediaUri = audio.uri.toString(),
                coverPath = cover.uri.toString(),
                lyricPath = lyric.uri.toString(),
                translatedLyricPath = translated.uri.toString(),
                romanizedLyricPath = romanized.uri.toString()
            ).encodeToByteArray()
        )
    }

    private fun writeDocument(
        parent: DocumentFile,
        name: String,
        mimeType: String,
        content: ByteArray
    ): DocumentFile {
        val document = requireNotNull(parent.createFile(mimeType, name))
        requireNotNull(appContext.contentResolver.openOutputStream(document.uri, "w")).use {
            it.write(content)
        }
        return document
    }

    private fun readDocument(document: DocumentFile): ByteArray {
        return requireNotNull(appContext.contentResolver.openInputStream(document.uri)).use {
            it.readBytes()
        }
    }

    private fun assertSameDocument(expected: Uri, actual: String) {
        val actualUri = Uri.parse(actual)
        assertEquals(expected.authority, actualUri.authority)
        assertEquals(
            DocumentsContract.getDocumentId(expected),
            DocumentsContract.getDocumentId(actualUri)
        )
    }

    private fun requireTreeDirectory(parent: DocumentFile, name: String): DocumentFile {
        return requireNotNull(parent.findFile(name)).also { directory ->
            assertTrue(directory.isDirectory)
        }
    }

    private fun requireTreeFile(parent: DocumentFile, name: String): DocumentFile {
        val availableNames = parent.listFiles().joinToString { file -> file.name.orEmpty() }
        return requireNotNull(parent.findFile(name)) {
            "Missing $name in ${parent.uri}; available=$availableNames"
        }.also { file ->
            assertFalse(file.isDirectory)
        }
    }

    private fun metadataJson(
        stableKey: String,
        mediaUri: String,
        coverPath: String?,
        lyricPath: String?,
        translatedLyricPath: String?,
        romanizedLyricPath: String?
    ): String {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("songId", 42L)
            put("identityAlbum", "__local_files__")
            put("mediaUri", mediaUri)
            put("coverPath", coverPath)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("romanizedLyricPath", romanizedLyricPath)
        }.toString()
    }

    private data class PrivateFixture(
        val audio: File,
        val metadata: File,
        val cover: File,
        val lyric: File,
        val translatedLyric: File,
        val romanizedLyric: File,
        val audioBytes: ByteArray,
        val coverBytes: ByteArray,
        val lyricBytes: ByteArray,
        val translatedLyricBytes: ByteArray,
        val romanizedLyricBytes: ByteArray
    )

    private companion object {
        const val MIGRATION_TEST_TAG = "ManagedDownloadMigrationTest"
    }
}
