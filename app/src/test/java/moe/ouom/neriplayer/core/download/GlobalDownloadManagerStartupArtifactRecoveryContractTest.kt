package moe.ouom.neriplayer.core.download

import java.io.File
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * locks down the startup handoff so slow SAF recovery cannot delay the first
 * directory publication
 */
class GlobalDownloadManagerStartupArtifactRecoveryContractTest {
    @Test
    fun `startup artifact recovery is dispatched only after a published scan`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = methodBody(source, "initialize")
        val scanIndex = initializeBody.indexOf("scanLocalFilesAwait(")
        val publishedBranchIndex = initializeBody.indexOf(
            "if (refreshOutcome is ManagedLibraryRefreshOutcome.Published)"
        )
        val handoffIndex = initializeBody.indexOf(
            "scheduleStartupArtifactRecovery(appContext)",
            startIndex = publishedBranchIndex
        )
        val preScanBody = initializeBody.substringBefore("scanLocalFilesAwait(")

        assertTrue(scanIndex >= 0)
        assertTrue(publishedBranchIndex > scanIndex)
        assertTrue(handoffIndex > publishedBranchIndex)
        assertFalse(preScanBody.contains("recoverPendingAudioWritesFromRoot(appContext)"))
        assertFalse(
            preScanBody.contains("recoverUnfinalizedPublishedAudioFromRoot(appContext)")
        )
        assertTrue(
            preScanBody.contains(
                "ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(appContext)"
            )
        )
        assertTrue(
            initializeBody.contains("跳过并行目录扫描")
        )
    }

    @Test
    fun `migration recovery is gated before legacy upgrade and every startup root operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = methodBody(source, "initialize")
        val migrationProbe = initializeBody.indexOf(
            "ManagedDownloadMigrationWorker.hasPersistedMigrationRecovery(appContext)"
        )
        val migrationResume = initializeBody.indexOf(
            "ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(appContext)"
        )
        val legacyUpgrade = initializeBody.indexOf(
            "LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(appContext)"
        )
        val pendingRecovery = initializeBody.indexOf("recoverPendingDownloadsForStartup(appContext)")
        val coverRepair = initializeBody.indexOf("repairFinalizedDownloadedCoversFromRoot(appContext)")
        val initialScan = initializeBody.indexOf("scanLocalFilesAwait(")

        assertTrue(migrationProbe >= 0)
        assertTrue(migrationResume > migrationProbe)
        assertTrue(legacyUpgrade > migrationResume)
        assertTrue(pendingRecovery > legacyUpgrade)
        assertTrue(coverRepair > pendingRecovery)
        assertTrue(initialScan > coverRepair)

        val recoveryFailure = initializeBody.indexOf("迁移恢复凭据检查失败")
        assertTrue(recoveryFailure >= 0)
        assertTrue(
            initializeBody.indexOf("return@recovery", recoveryFailure) > recoveryFailure
        )
        assertTrue(
            initializeBody.contains("迁移恢复检查暂时失败，保留请求并跳过启动目录操作")
        )
    }

    @Test
    fun `storage startup defers root cleanup and snapshot warmup while migration is pending`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val initializeBody = methodBody(source, "initialize")
        val migrationProbe = initializeBody.indexOf("hasPendingStartupMigrationRecovery(appContext)")
        val rootCreation = initializeBody.indexOf("createDefaultRoot(appContext)")
        val stagingCleanup = initializeBody.indexOf("cleanupStagingFiles(appContext)")
        val snapshotWarmup = initializeBody.indexOf("scheduleSnapshotWarmup(appContext)")

        assertTrue(migrationProbe >= 0)
        assertTrue(rootCreation > migrationProbe)
        assertTrue(stagingCleanup > migrationProbe)
        assertTrue(snapshotWarmup > migrationProbe)
        assertTrue(initializeBody.contains("if (!migrationRecoveryPending)"))

        val helperBody = methodBody(source, "hasPendingStartupMigrationRecovery")
        assertTrue(helperBody.contains("readRequest()"))
        assertTrue(helperBody.contains("readReplacementJournal()"))
        assertTrue(helperBody.contains("getWorkInfosForUniqueWork"))
        assertTrue(helperBody.contains("迁移恢复凭据检查失败，延后启动存储清理"))
    }

    @Test
    fun `startup artifact recovery is single flight and failure contained`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulerBody = methodBody(source, "scheduleStartupArtifactRecovery")

        assertTrue(schedulerBody.contains("startupArtifactRecoveryActive.compareAndSet(false, true)"))
        assertTrue(schedulerBody.contains("scope.launch"))
        assertTrue(schedulerBody.contains("startupRecoveryMutex.withLock"))
        assertTrue(schedulerBody.contains("isDownloadClearFenceActive(appContext)"))
        assertTrue(schedulerBody.contains("catch (error: CancellationException)"))
        assertTrue(schedulerBody.contains("catch (error: Throwable)"))
        assertTrue(schedulerBody.contains("startupArtifactRecoveryActive.set(false)"))
    }

    @Test
    fun `recovery loops yield and preserve cancellation at bounded batches`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val pendingBody = methodBody(source, "recoverPendingAudioWritesFromRoot")
        val publishedBody = methodBody(source, "recoverUnfinalizedPublishedAudioFromRoot")

        listOf(pendingBody, publishedBody).forEach { body ->
            assertTrue(body.contains("withIndex()"))
            assertTrue(body.contains("STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE"))
            assertTrue(body.contains("yield()"))
            assertTrue(body.contains("if (error is CancellationException)"))
        }
        assertTrue(pendingBody.contains("读取 pending 音频失败"))
        assertTrue(publishedBody.contains("读取待收尾下载音频失败"))
    }

    @Test
    fun `migration worker keeps durable credentials across interruption and clears them only after publish`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val workerBody = methodBody(source, "runMigration")
        assertTrue(workerBody.indexOf("checkpointStore.recordRequest(") >= 0)
        assertTrue(workerBody.lastIndexOf("waitForRetry = true") >
            workerBody.indexOf("catch (error: CancellationException)"))
        assertTrue(workerBody.contains("checkpointStore.clearCompleted("))
        assertTrue(
            workerBody.indexOf("checkpointStore.clearCompleted(") >
                workerBody.indexOf("scanLocalFilesAwait(")
        )
    }

    @Test
    fun `replacement backup deletion reports its own cleanup progress`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val body = methodBody(source, "cleanupMigrationReplacementBackups")
        val declarationStart = source.indexOf(
            "private fun cleanupMigrationReplacementBackups("
        )
        val declarationEnd = source.indexOf('{', declarationStart)
        assertTrue(declarationStart >= 0 && declarationEnd > declarationStart)
        assertTrue(
            source.substring(declarationStart, declarationEnd)
                .contains("progressTracker: ManagedMigrationProgressReporter?")
        )
        assertTrue(body.contains("progressTracker?.startCleanup(backups.size"))
        assertTrue(body.contains("progressTracker?.finishCleanup(backup.name)"))
    }

    @Test
    fun `collision audio writes pair pending metadata with the reserved name`() {
        val storageSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val audioSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val saveBody = methodBody(storageSource, "saveAudioFromTempBlocking")
        val helperBody = methodBody(storageSource, "writeCollisionPendingMetadata")
        val helperIndex = saveBody.indexOf("writeCollisionPendingMetadata(")
        val secondHelperIndex = saveBody.indexOf(
            "writeCollisionPendingMetadata(",
            startIndex = helperIndex + 1
        )
        val copyIndex = saveBody.indexOf("writeRecoverable(")
        val safCopyIndex = saveBody.indexOf("writeSafFileThroughBackend(")
        val saveCall = methodBody(audioSource, "finalizeDownloadedAudio")

        assertTrue(
            "the actual reserved name must get a pending recovery credential before copy",
            helperIndex >= 0 && copyIndex > helperIndex
        )
        assertTrue(secondHelperIndex > helperIndex)
        assertTrue(safCopyIndex > secondHelperIndex)
        assertEquals(2, saveBody.countOccurrences("writeCollisionPendingMetadata("))
        assertTrue(helperBody.contains("requestedAudioName == actualAudioName"))
        assertTrue(helperBody.contains("pendingMetadataJson"))
        assertTrue(storageSource.contains("pendingMetadataJson: String?"))
        assertTrue(saveCall.contains("pendingMetadataJson = pendingMetadata"))
    }

    @Test
    fun `orphan pending metadata cannot index a same-name formal audio`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "content://downloads/audio/song.mp3",
            mediaUri = "content://downloads/audio/song.mp3",
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val pendingMetadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3.npmeta.pending.json",
            reference = "content://downloads/audio/song.mp3.npmeta.pending.json",
            mediaUri = "content://downloads/audio/song.mp3.npmeta.pending.json",
            localFilePath = null,
            sizeBytes = 32L,
            lastModifiedMs = 1L
        )
        val pendingMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "netease:new",
            operationId = "operation-new",
            audioFileName = "song.mp3",
            downloadFinalized = false,
            artifactState = "COMMITTING"
        )

        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(audio),
            metadataEntries = listOf(pendingMetadataEntry),
            metadataByAudioName = mapOf(audio.name to pendingMetadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            pendingMetadataByAudioName = mapOf(audio.name to pendingMetadata)
        )

        assertTrue(snapshot.metadataEntriesByAudioName.isEmpty())
        assertTrue(snapshot.audioEntriesWithoutMetadata.contains(audio))
        assertTrue(snapshot.audioEntriesByStableKey.isEmpty())
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        return source.substring(bodyStart, findBodyEnd(source, bodyStart, methodName))
    }

    private fun findBodyEnd(source: String, bodyStart: Int, methodName: String): Int {
        var depth = 0
        var index = bodyStart
        var state = SourceLexState.NORMAL
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                SourceLexState.NORMAL -> when {
                    current == '/' && next == '/' -> {
                        state = SourceLexState.LINE_COMMENT
                        index++
                    }
                    current == '/' && next == '*' -> {
                        state = SourceLexState.BLOCK_COMMENT
                        index++
                    }
                    current == '"' && source.startsWith("\"\"\"", index) -> {
                        state = SourceLexState.TRIPLE_QUOTE
                        index += 2
                    }
                    current == '"' -> state = SourceLexState.DOUBLE_QUOTE
                    current == '\'' -> state = SourceLexState.CHAR_QUOTE
                    current == '{' -> depth++
                    current == '}' -> {
                        depth--
                        if (depth == 0) return index + 1
                    }
                }
                SourceLexState.LINE_COMMENT -> if (current == '\n') {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.BLOCK_COMMENT -> if (current == '*' && next == '/') {
                    state = SourceLexState.NORMAL
                    index++
                }
                SourceLexState.DOUBLE_QUOTE,
                SourceLexState.CHAR_QUOTE -> if (current == '\\') {
                    index++
                } else if (
                    (state == SourceLexState.DOUBLE_QUOTE && current == '"') ||
                        (state == SourceLexState.CHAR_QUOTE && current == '\'')
                ) {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.TRIPLE_QUOTE -> if (
                    current == '"' && source.startsWith("\"\"\"", index)
                ) {
                    state = SourceLexState.NORMAL
                    index += 2
                }
            }
            index++
        }
        error("unterminated method body: $methodName")
    }

    private enum class SourceLexState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        DOUBLE_QUOTE,
        CHAR_QUOTE,
        TRIPLE_QUOTE
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val index = indexOf(needle, offset)
            if (index < 0) return count
            count++
            offset = index + needle.length
        }
    }
}
