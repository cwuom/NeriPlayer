package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.operation.content.writeRootText
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.cleanupMigratedEntriesDetailed
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.rewriteMigratedMetadataReferences
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.verifyMigratedEntries
import android.content.Context
import java.text.Normalizer
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.plan.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntryRef
import moe.ouom.neriplayer.core.download.storage.migration.copy.InputStreamManagedMigrationEntryReader
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PROGRESS_EMIT_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteArtifacts
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis


class ManagedDownloadStorageMigrationCompatGroup2Test : ManagedDownloadStorageMigrationCompatTestSupport() {

    @Test
    fun `createUniqueName treats canonically equivalent unicode names as a conflict`() {
        val decomposed = "Cafe\u0301 - Artist.mp3"
        assertEquals(
            "Cafe\u0301 - Artist (1).mp3",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Café - Artist.mp3"),
                desiredName = decomposed
            )
        )
    }

    @Test
    fun `createUniqueName increments numbered suffix on conflict`() {
        assertEquals(
            "Artist - Song (2).flac",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf(
                    "Artist - Song.flac",
                    "Artist - Song (1).flac"
                ),
                desiredName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `createUniqueName supports extensionless names`() {
        assertEquals(
            "Artist - Song (1)",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Artist - Song"),
                desiredName = "Artist - Song"
            )
        )
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps embedded lyrics for local fallback`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "[00:00.00]原文")
                put("matchedTranslatedLyric", "[00:00.00]翻译")
                put("originalLyric", "[00:00.00]原始原文")
                put("originalTranslatedLyric", "[00:00.00]原始翻译")
                put("lyricPath", "/music/Lyrics/Artist - Song.lrc")
            }.toString()
        )

        assertEquals("[00:00.00]原文", metadata?.matchedLyric)
        assertEquals("[00:00.00]翻译", metadata?.matchedTranslatedLyric)
        assertEquals("[00:00.00]原始原文", metadata?.originalLyric)
        assertEquals("[00:00.00]原始翻译", metadata?.originalTranslatedLyric)
        assertEquals("/music/Lyrics/Artist - Song.lrc", metadata?.lyricPath)
    }

    @Test
    fun `download metadata preserves original download time`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("downloadTimeMs", 123456789L)
            }.toString()
        )

        assertEquals(123456789L, metadata?.downloadTimeMs)
    }

    @Test
    fun `download metadata restores immutable created at fields`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("schemaVersion", 3)
                put("createdAtMs", 123456789L)
                put("createdAtSource", "MANAGED_COMMIT")
            }.toString()
        )

        assertEquals(123456789L, metadata?.createdAtMs)
        assertEquals("MANAGED_COMMIT", metadata?.createdAtSource)
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps explicit cleared lyrics as blank string`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "")
                put("matchedTranslatedLyric", "")
                put("originalLyric", "")
                put("originalTranslatedLyric", "")
            }.toString()
        )

        assertEquals("", metadata?.matchedLyric)
        assertEquals("", metadata?.matchedTranslatedLyric)
        assertEquals("", metadata?.originalLyric)
        assertEquals("", metadata?.originalTranslatedLyric)
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when copied size is unknown or empty`() {
        // #D3 回归: 目标尺寸为 0 (SAF 对新建文档常返回 length=0) 时必须保留源, 避免误删导致数据丢失
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 0L
            )
        )
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 0L
            )
        )
        // 防御性: 负数 (不可知) 同样保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = -1L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when target is truncated or size mismatches`() {
        // 目标非空但明显小于源 (截断/损坏) 时保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 1L
            )
        )
        // 源尺寸不可知(0) 但目标非空且远超容差, 视为不一致, 保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch allows deleting source when copy faithfully matches`() {
        // 源/目标尺寸一致 (容差内) 时确认拷贝可信, 允许删源
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 100L
            )
        )
        // 容差为 1 字节, 相差 1 仍视为一致
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 101L
            )
        )
        // 源本就为空(0), 目标落在容差内(1), 视为一致, 允许删源 (源本就为空才可删)
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 1L
            )
        )
    }

    @Test
    fun `migration verifies content with streaming sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ManagedDownloadMigrationFinalizer.sha256Hex(
                ByteArrayInputStream("abc".toByteArray())
            )
        )
    }

    @Test
    fun `directory changes only have URI level confirmation eligibility`() {
        assertTrue(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads"
            )
        )
        assertFalse(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = "content://provider/tree/downloads",
                toDirectoryUri = "content://provider/tree/downloads"
            )
        )
        assertTrue(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = "content://provider/tree/downloads",
                toDirectoryUri = null
            )
        )
    }

    @Test
    fun `fresh install reattaches a populated managed SAF directory instead of migrating empty private root`() {
        assertTrue(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = false,
                targetHasManagedEntries = true
            )
        )
        assertTrue(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = null,
                targetHasManagedEntries = true
            )
        )
        assertFalse(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = "content://provider/tree/private",
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = false,
                targetHasManagedEntries = true
            )
        )
    }

    @Test
    fun `migration scan retry classification keeps unavailable source permanent`() {
        assertFalse(
            ManagedDownloadMigrationException.permanent("source unavailable").retryable
        )
        assertTrue(
            ManagedDownloadMigrationException.transient("incomplete SAF enumeration").retryable
        )
    }

    @Test
    fun `migration does not reuse an unknown same-name metadata target`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3.npmeta.json",
            reference = "/source/track.mp3.npmeta.json",
            mediaUri = "file:///source/track.mp3.npmeta.json",
            localFilePath = "/source/track.mp3.npmeta.json",
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        val target = source.copy(
            reference = "/target/track.mp3.npmeta.json",
            mediaUri = "file:///target/track.mp3.npmeta.json",
            localFilePath = "/target/track.mp3.npmeta.json"
        )

        val directory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = directory,
                displayName = source.name,
                sourceEntry = source,
                targetNames = setOf(source.name),
                targetEntry = target,
                readExistingEntry = { target },
                reserveName = { "$it (1)" },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertTrue(resolved.createdNew)
            assertEquals("${source.name} (1)", resolved.entry.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration reserves even an initially available target name`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/source/track.mp3",
            mediaUri = "file:///source/track.mp3",
            localFilePath = "/source/track.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        val directory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            var reservedName: String? = null
            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = directory,
                displayName = source.name,
                sourceEntry = source,
                targetNames = emptySet(),
                targetEntry = null,
                readExistingEntry = { null },
                reserveName = { name ->
                    reservedName = name
                    name
                },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertTrue(resolved.createdNew)
            assertEquals(source.name, reservedName)
            assertEquals(source.name, resolved.entry.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration hashes source when its provider does not report a size`() {
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForMigrationSize(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `migration switches directory after copies succeed even when source cleanup needs retry`() {
        val result = ManagedDownloadStorage.MigrationResult(
            movedFiles = 2,
            skippedFiles = 0,
            cleanupFailedFiles = 1,
            cleanupRetryableFailedFiles = 1
        )

        assertTrue(result.canSwitchDirectory)
        assertFalse(result.canReleasePreviousPermission)
        assertTrue(result.hasOnlyRetryableCleanupFailures)
    }

    @Test
    fun `migration makes source authoritative when metadata identifies the same song`() {
        val sourceAudio = ManagedDownloadStorage.StoredEntry(
            name = "source.mp3",
            reference = "/old/source.mp3",
            mediaUri = "file:///old/source.mp3",
            localFilePath = "/old/source.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 100L
        )
        val targetAudio = ManagedDownloadStorage.StoredEntry(
            name = "target.mp3",
            reference = "/new/target.mp3",
            mediaUri = "file:///new/target.mp3",
            localFilePath = "/new/target.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 200L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "42|netease|track-42",
            songId = 42L,
            identityAlbum = "netease"
        )
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(
                ManagedMigrationEntryRef(null, sourceAudio)
            ),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(targetAudio.name to metadata)
            ),
            sourceMetadataByAudioName = mapOf(sourceAudio.name to metadata)
        )

        assertEquals(targetAudio.name, plan.targetNameFor(ManagedMigrationEntryRef(null, sourceAudio)))
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        assertEquals(null, plan.reusedTargetFor(sourceRef))
        assertEquals(targetAudio.name, plan.replacementFor(sourceRef)?.targetName)
    }

    @Test
    fun `migration keeps unknown size non-audio source when copied content differs`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-sidecar-migration").toFile()
        try {
            val sourceFile = File(directory, "source-cover.jpg").apply {
                writeText("original-cover")
            }
            val targetFile = File(directory, "target-cover.jpg").apply {
                writeText("cut")
            }
            val sourceEntry = ManagedDownloadStorage.StoredEntry(
                name = sourceFile.name,
                reference = sourceFile.absolutePath,
                mediaUri = sourceFile.toURI().toString(),
                localFilePath = sourceFile.absolutePath,
                sizeBytes = 0L,
                lastModifiedMs = 1L
            )
            val targetEntry = ManagedDownloadStorage.StoredEntry(
                name = targetFile.name,
                reference = targetFile.absolutePath,
                mediaUri = targetFile.toURI().toString(),
                localFilePath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
                lastModifiedMs = 2L
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, sourceEntry),
                        copiedEntry = targetEntry,
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(1, cleanupFailures)
            assertTrue(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration does not invoke destructive cleanup before target verification`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-unverified").toFile()
        try {
            val sourceFile = File(directory, "source.mp3").apply { writeText("source") }
            val targetFile = File(directory, "target.mp3").apply { writeText("different") }
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val deleteCalls = AtomicInteger(0)
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, _, _ ->
                    deleteCalls.incrementAndGet()
                    StorageMutationResult.Deleted
                },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, entry(sourceFile)),
                        copiedEntry = entry(targetFile),
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(1, cleanupFailures)
            assertEquals(0, deleteCalls.get())
            assertTrue(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration keeps source when permission or provider cleanup fails`() = runBlocking {
        listOf(
            StorageMutationResult.PermissionLost,
            StorageMutationResult.ProviderFailure(IOException("provider unavailable"))
        ).forEach { deleteResult ->
            val directory = Files.createTempDirectory("neriplayer-migration-delete-failure").toFile()
            try {
                val sourceFile = File(directory, "source.mp3").apply { writeText("same") }
                val targetFile = File(directory, "target.mp3").apply { writeText("same") }
                fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                    name = file.name,
                    reference = file.absolutePath,
                    mediaUri = file.toURI().toString(),
                    localFilePath = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified()
                )
                val finalizer = ManagedDownloadMigrationFinalizer(
                    tag = "ManagedDownloadStorageMigrationCompatTest",
                    rewriteParallelism = { 1 },
                    deleteParallelism = { 1 },
                    readText = { _, _ -> null },
                    entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                        File(entry.reference).inputStream()
                    },
                    writeRootText = { _, _, _, _ -> null },
                    deleteReference = { _, _, _ -> deleteResult },
                    rewriteMetadataReferences = { raw, _ -> raw }
                )

                val cleanupResult = finalizer.cleanupMigratedEntriesDetailed(
                    context = mock(Context::class.java),
                    copiedEntries = listOf(
                        CopiedMigrationEntry(
                            original = ManagedMigrationEntry(null, entry(sourceFile)),
                            copiedEntry = entry(targetFile),
                            createdNew = true
                        )
                    ),
                    sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
                )

                assertEquals(1, cleanupResult.failedFiles)
                assertEquals(
                    if (deleteResult is StorageMutationResult.ProviderFailure) 1 else 0,
                    cleanupResult.retryableFailedFiles
                )
                assertTrue(sourceFile.exists())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `migration removes verified source when typed cleanup is confirmed`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-delete-success").toFile()
        try {
            val sourceFile = File(directory, "source.mp3").apply { writeText("same") }
            val targetFile = File(directory, "target.mp3").apply { writeText("same") }
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, entry(sourceFile)),
                        copiedEntry = entry(targetFile),
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(0, cleanupFailures)
            assertFalse(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration reports cleanup before deletion and advances failed attempts once`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-cleanup-progress").toFile()
        try {
            val sourceFiles = listOf(
                File(directory, "source-a.mp3").apply { writeText("a") },
                File(directory, "source-b.mp3").apply { writeText("b") }
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val progressUpdates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
            val reporter = ManagedMigrationProgressReporter(
                totalFiles = sourceFiles.size,
                totalBytes = sourceFiles.sumOf(File::length),
                metadataFilesTotal = 0,
                onProgress = progressUpdates::add
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                entryReader = InputStreamManagedMigrationEntryReader { _, storedEntry ->
                    File(storedEntry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, _, _ -> StorageMutationResult.Deleted },
                deleteReferences = {
                        _,
                        references,
                        _,
                        onDeleteStarted,
                        onDeleteFinished ->
                    assertEquals(
                        ManagedDownloadStorage.MigrationStage.CLEANING_UP,
                        progressUpdates.last().stage
                    )
                    assertEquals(0, progressUpdates.last().cleanupFilesProcessed)
                    references.mapIndexed { index, reference ->
                        onDeleteStarted(reference)
                        Thread.sleep(MIGRATION_PROGRESS_EMIT_INTERVAL_MS + 20L)
                        onDeleteFinished(reference)
                        onDeleteFinished(reference)
                        reference to if (index == 0) {
                            StorageMutationResult.Deleted
                        } else {
                            StorageMutationResult.PermissionLost
                        }
                    }.toMap()
                },
                rewriteMetadataReferences = { raw, _ -> raw }
            )
            val copiedEntries = sourceFiles.map { sourceFile ->
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceFile)),
                    copiedEntry = entry(sourceFile),
                    createdNew = true
                )
            }

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = copiedEntries,
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory),
                targetsAlreadyVerified = true,
                progressTracker = reporter
            )

            assertEquals(1, cleanupFailures)
            assertTrue(progressUpdates.any { it.cleanupFilesProcessed == 1 })
            assertEquals(2, progressUpdates.last().cleanupFilesProcessed)
            assertEquals(2, progressUpdates.last().cleanupFilesTotal)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration cleanup propagates cancellation without converting it to a provider failure`() {
        val sourceFile = Files.createTempFile("neriplayer-migration-cancel", ".mp3").toFile()
        try {
            val sourceEntry = ManagedDownloadStorage.StoredEntry(
                name = sourceFile.name,
                reference = sourceFile.absolutePath,
                mediaUri = sourceFile.toURI().toString(),
                localFilePath = sourceFile.absolutePath,
                sizeBytes = sourceFile.length(),
                lastModifiedMs = sourceFile.lastModified()
            )
            val copiedEntry = CopiedMigrationEntry(
                original = ManagedMigrationEntry(null, sourceEntry),
                copiedEntry = sourceEntry,
                createdNew = true
            )
            val cancellation = CancellationException("migration cancelled")
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, _, _ -> StorageMutationResult.Deleted },
                deleteReferences = { _, _, _, _, _ -> throw cancellation },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking {
                    finalizer.cleanupMigratedEntriesDetailed(
                        context = mock(Context::class.java),
                        copiedEntries = listOf(copiedEntry),
                        sourceRoot = ManagedDownloadRootHandle.FileRoot(sourceFile.parentFile!!),
                        targetsAlreadyVerified = true
                    )
                }
            }

            assertEquals(cancellation.message, thrown.message)
            assertTrue(sourceFile.exists())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun `migration verifies rewritten metadata before deleting either source root`() = runBlocking {
        val sourceDirectory = Files.createTempDirectory("neriplayer-migration-source").toFile()
        val targetDirectory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val sourceAudio = File(sourceDirectory, "track.mp3").apply { writeText("audio") }
            val targetAudio = File(targetDirectory, "track.mp3").apply { writeText("audio") }
            val sourceMetadata = File(sourceDirectory, "track.mp3.npmeta.json")
            val targetMetadata = File(targetDirectory, "track.mp3.npmeta.json")
            val sourceMetadataText = JSONObject().apply {
                put("mediaUri", sourceAudio.toURI().toString())
                put("stableKey", "1|local|${sourceAudio.toURI()}")
            }.toString()
            sourceMetadata.writeText(sourceMetadataText)
            targetMetadata.writeText(
                ManagedDownloadStorage.rewriteManagedMetadataReferences(
                    rawJson = sourceMetadataText,
                    referenceMap = mapOf(
                        sourceAudio.absolutePath to targetAudio.absolutePath,
                        sourceAudio.toURI().toString() to targetAudio.toURI().toString()
                    )
                )
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val copiedEntries = listOf(
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceAudio)),
                    copiedEntry = entry(targetAudio),
                    createdNew = true,
                    sourceDigest = sourceAudio.inputStream().use(
                        ManagedDownloadMigrationFinalizer::sha256Hex
                    )
                ),
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceMetadata)),
                    copiedEntry = entry(targetMetadata),
                    createdNew = true
                )
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, reference -> File(reference).readText() },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = ManagedDownloadStorage::rewriteManagedMetadataReferences
            )

            assertEquals(
                0,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = copiedEntries
                )
            )

            targetMetadata.writeText("{}")
            assertEquals(
                1,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = copiedEntries
                )
            )
            assertTrue(sourceAudio.exists())
            assertTrue(sourceMetadata.exists())
        } finally {
            sourceDirectory.deleteRecursively()
            targetDirectory.deleteRecursively()
        }
    }

    @Test
    fun `migration rewrites metadata left by an interrupted copy`() = runBlocking {
        val sourceDirectory = Files.createTempDirectory("neriplayer-migration-source").toFile()
        val targetDirectory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val sourceAudio = File(sourceDirectory, "track.mp3").apply { writeText("audio") }
            val targetAudio = File(targetDirectory, "track.mp3").apply { writeText("audio") }
            val sourceMetadata = File(sourceDirectory, "track.mp3.npmeta.json")
            val targetMetadata = File(targetDirectory, "track.mp3.npmeta.json")
            val sourceMetadataText = JSONObject().apply {
                put("mediaUri", sourceAudio.toURI().toString())
                put("localFilePath", sourceAudio.absolutePath)
                put("stableKey", "1|local|${sourceAudio.toURI()}")
            }.toString()
            sourceMetadata.writeText(sourceMetadataText)
            targetMetadata.writeText(sourceMetadataText)
            val providerRewrittenMetadata = File(
                targetDirectory,
                "track.provider-rewritten.mp3.npmeta.json"
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString().replaceFirst("file:", "file://"),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val copiedEntries = listOf(
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceAudio)),
                    copiedEntry = entry(targetAudio),
                    createdNew = false
                ),
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(
                        subdirectory = null,
                        entry = entry(sourceMetadata),
                        metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                            createdAtMs = 1_700_000_123_000L,
                            createdAtSource = "MTIME",
                            createdAtConfidence = "INFERRED"
                        )
                    ),
                    copiedEntry = entry(targetMetadata),
                    createdNew = false
                )
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, reference -> File(reference).readText() },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, content ->
                    providerRewrittenMetadata.apply { writeText(content) }.let(::entry)
                },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = ManagedDownloadStorage::rewriteManagedMetadataReferences
            )

            val rewriteResult = finalizer.rewriteMigratedMetadataReferences(
                context = mock(Context::class.java),
                targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                copiedEntries = copiedEntries
            )
            assertEquals(0, rewriteResult.failedFiles)
            val rewrittenMetadata = rewriteResult.copiedEntries.last().copiedEntry
            assertEquals(providerRewrittenMetadata.absolutePath, rewrittenMetadata.reference)
            val rewritten = JSONObject(File(rewrittenMetadata.reference).readText())
            assertEquals(targetAudio.toURI().toString(), rewritten.getString("mediaUri"))
            assertEquals(targetAudio.absolutePath, rewritten.getString("localFilePath"))
            assertEquals("1|local|${targetAudio.toURI()}", rewritten.getString("stableKey"))
            assertEquals(1_700_000_123_000L, rewritten.getLong("createdAtMs"))
            assertEquals("MTIME", rewritten.getString("createdAtSource"))
            assertEquals("INFERRED", rewritten.getString("createdAtConfidence"))
            assertEquals(
                0,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = rewriteResult.copiedEntries
                )
            )
        } finally {
            sourceDirectory.deleteRecursively()
            targetDirectory.deleteRecursively()
        }
    }

    @Test
    fun `private migration commit copies sixteen MiB within ten seconds`() {
        val directory = Files.createTempDirectory("neriplayer-migration-performance").toFile()
        val payload = ByteArray(512 * 1024) { index -> (index % 251).toByte() }
        try {
            val elapsedMs = measureTimeMillis {
                repeat(32) { index ->
                    ManagedDownloadCommitIo.copyFileAtomically(
                        parent = directory,
                        targetName = "track-$index.mp3",
                        input = ByteArrayInputStream(payload),
                        bufferSizeBytes = 64 * 1024
                    )
                }
            }

            assertTrue("private migration took ${elapsedMs}ms", elapsedMs < 10_000L)
            assertEquals(32, directory.listFiles()?.count(File::isFile))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `atomic migration copy removes partial file after source failure`() {
        val directory = Files.createTempDirectory("neriplayer-migration-test").toFile()
        try {
            val failingInput = object : InputStream() {
                private var readCalls = 0

                override fun read(): Int {
                    throw IOException("single-byte read should not be used")
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readCalls++ == 0) {
                        buffer[offset] = 1
                        return 1
                    }
                    throw IOException("injected source failure")
                }
            }
            runCatching {
                ManagedDownloadCommitIo.copyFileAtomically(
                    parent = directory,
                    targetName = "song.mp3",
                    input = failingInput,
                    bufferSizeBytes = 8,
                    onProgress = {}
                )
            }
            assertFalse(File(directory, "song.mp3").exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `private migration commit never replaces a concurrently created target`() {
        val directory = Files.createTempDirectory("neriplayer-migration-target-race").toFile()
        val target = File(directory, "song.mp3")
        val input = object : ByteArrayInputStream("migration".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!target.exists()) {
                    target.writeText("external")
                }
                return super.read(buffer, offset, length)
            }
        }
        try {
            val thrown = runCatching {
                ManagedDownloadCommitIo.copyFileAtomically(
                    parent = directory,
                    targetName = target.name,
                    input = input,
                    bufferSizeBytes = 8
                )
            }.exceptionOrNull()

            val migrationError = requireNotNull(thrown as? ManagedDownloadMigrationException)
            assertTrue(migrationError.retryable)
            assertFalse(migrationError.retryWithinEntry)
            assertEquals("external", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `private migration resumes by removing only its target bound stale partial`() {
        val directory = Files.createTempDirectory("neriplayer-migration-resume").toFile()
        val target = File(directory, "song.mp3")
        val storageTarget = StorageTarget.FileTarget(target.absolutePath)
        val stalePartial = File(
            directory,
            ManagedTemporaryWriteArtifacts.displayNameFor(
                target = storageTarget,
                nonce = ManagedDownloadCommitIo.MIGRATION_TEMPORARY_WRITE_NONCE
            )
        ).apply { writeText("interrupted") }
        val unrelated = File(directory, ".np-migration-user.partial").apply {
            writeText("keep")
        }
        try {
            ManagedDownloadCommitIo.copyFileAtomically(
                parent = directory,
                targetName = target.name,
                input = ByteArrayInputStream("complete".toByteArray()),
                bufferSizeBytes = 8
            )

            assertEquals("complete", target.readText())
            assertFalse(stalePartial.exists())
            assertEquals("keep", unrelated.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
