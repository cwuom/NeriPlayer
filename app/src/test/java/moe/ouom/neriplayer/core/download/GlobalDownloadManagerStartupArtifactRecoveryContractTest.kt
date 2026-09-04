package moe.ouom.neriplayer.core.download

import java.io.File
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定启动交接行为，避免缓慢的 SAF 恢复阻塞首次目录发布
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
    fun `migration recovery is gated before startup root operations and legacy scheduling`() {
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
        val legacySchedule = initializeBody.indexOf(
            "LegacyJsonCleanupScheduler.schedule(appContext, \"download-startup\")"
        )
        val pendingRecovery = initializeBody.indexOf("recoverPendingDownloadsForStartup(")
        val coverRepair = initializeBody.indexOf("repairFinalizedDownloadedCoversFromRoot(")
        val initialScan = initializeBody.indexOf("scanLocalFilesAwait(")

        assertTrue(migrationProbe >= 0)
        assertTrue(migrationResume > migrationProbe)
        assertTrue(legacySchedule > migrationResume)
        assertTrue(pendingRecovery < legacySchedule)
        assertTrue(coverRepair > pendingRecovery)
        assertTrue(initialScan > coverRepair)
        assertFalse(initializeBody.contains("runDownloadUpgradeOnce(appContext)"))

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
        assertTrue(schedulerBody.contains("isDownloadAdmissionTicketCurrent(appContext, admissionTicket)"))
        assertTrue(schedulerBody.contains("catch (error: CancellationException)"))
        assertTrue(schedulerBody.contains("catch (error: Throwable)"))
        assertTrue(schedulerBody.contains("startupArtifactRecoveryActive.set(false)"))
    }

    @Test
    fun `startup and cover recovery hold the directory lease across root writes`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulerBody = methodBody(source, "scheduleStartupArtifactRecovery")
        val schedulerLeaseIndex = schedulerBody.indexOf("acquireRecoveryLeaseOrNull(")
        val pendingIndex = schedulerBody.indexOf("recoverPendingAudioWritesFromRoot(")
        val publishedIndex = schedulerBody.indexOf(
            "recoverUnfinalizedPublishedAudioFromRoot(",
            startIndex = pendingIndex
        )
        val schedulerCloseIndex = schedulerBody.lastIndexOf("directoryMutationLease.close()")

        assertTrue(schedulerLeaseIndex >= 0)
        assertTrue(pendingIndex > schedulerLeaseIndex)
        assertTrue(publishedIndex > pendingIndex)
        assertTrue(schedulerCloseIndex > publishedIndex)

        val coverBody = methodBody(source, "repairFinalizedDownloadedCoversFromRoot")
        val coverLeaseIndex = coverBody.indexOf("acquireRecoveryLeaseOrNull(")
        val snapshotIndex = coverBody.indexOf("buildDownloadLibrarySnapshot(")
        val readIndex = coverBody.indexOf("readDownloadedMetadata(")
        val writeIndex = coverBody.indexOf("publishOptimisticDownloadedSongs(")
        val coverCloseIndex = coverBody.lastIndexOf("directoryMutationLease?.close()")

        assertTrue(coverLeaseIndex >= 0)
        assertTrue(snapshotIndex > coverLeaseIndex)
        assertTrue(readIndex > coverLeaseIndex)
        assertTrue(writeIndex > readIndex)
        assertTrue(coverCloseIndex > writeIndex)
        assertTrue(coverBody.contains("目录迁移占用封面恢复入口"))
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
    fun `artifact recovery keeps the captured admission ticket through core commit`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val pendingBody = methodBody(source, "recoverPendingAudioWritesFromRoot")
        val publishedBody = methodBody(source, "recoverUnfinalizedPublishedAudioFromRoot")
        val coreBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val coreDeclarationStart = source.indexOf(
            "private suspend fun completeCoreDownloadAndEnqueueEnrichment("
        )
        val coreDeclarationEnd = source.indexOf('{', startIndex = coreDeclarationStart)
        assertTrue(coreDeclarationStart >= 0)
        assertTrue(coreDeclarationEnd > coreDeclarationStart)
        val coreSignature = source.substring(
            coreDeclarationStart,
            coreDeclarationEnd
        )

        assertTrue(pendingBody.contains("admissionTicket = admissionTicket"))
        assertTrue(publishedBody.contains("admissionTicket = admissionTicket"))
        assertTrue(coreSignature.contains("admissionTicket: Long?"))
        assertTrue(coreBody.contains("stableKey = songKey"))
        assertTrue(coreBody.contains("operationId = operationId"))
    }

    @Test
    fun `core artifact commit failure settles task and schedules in-process recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val coreBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val failureIndex = coreBody.indexOf("if (!artifactCommitted)")

        assertTrue(failureIndex >= 0)
        val failureBodyStart = coreBody.indexOf('{', startIndex = failureIndex)
        assertTrue(failureBodyStart > failureIndex)
        val failureBody = coreBody.substring(
            failureIndex,
            findBodyEnd(coreBody, failureBodyStart, "artifactCommitted branch")
        )
        val settleIndex = failureBody.indexOf("settlePostCoreEnrichmentFailure(")
        val leaseReleaseIndex = failureBody.indexOf(
            "managedDownloadArtifactLeases.remove(songKey, leaseId)"
        )
        val recoveryScheduleIndex = failureBody.indexOf("scheduleStartupArtifactRecovery(context)")
        val returnIndex = failureBody.lastIndexOf("return")

        assertTrue(settleIndex >= 0)
        assertTrue(failureBody.contains("operationId = recoveryOperationId"))
        assertTrue(failureBody.contains("errorCode = \"CORE_ARTIFACT_COMMIT_FAILED\""))
        assertTrue(failureBody.contains("scheduleRetry = false"))
        assertTrue(failureBody.contains("admissionTicket = admissionTicket"))
        assertTrue(failureBody.contains("coreAudioCommitted = false"))
        assertTrue(failureBody.contains("保留 core 音频"))
        assertTrue(leaseReleaseIndex > settleIndex)
        assertTrue(recoveryScheduleIndex > leaseReleaseIndex)
        assertTrue(returnIndex > recoveryScheduleIndex)
    }

    @Test
    fun `migration worker keeps durable credentials across interruption and clears them only after publish`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val workerBody = methodBody(source, "runMigration")
        val cancellationIndex = workerBody.indexOf("catch (error: CancellationException)")
        val terminalHelperBody = methodBody(source, "markTerminalRequestAndCompleteProcessing")

        assertTrue(workerBody.indexOf("checkpointStore.recordRequestIfCurrent(") >= 0)
        assertTrue(cancellationIndex >= 0)
        val cancellationBody = workerBody.substring(cancellationIndex)
        assertTrue(cancellationBody.contains("checkpointStore.markRequestRetryable("))
        assertTrue(cancellationBody.contains("markTerminalRequestAndCompleteProcessing("))
        assertTrue(terminalHelperBody.contains("checkpointStore.markRequestTerminal("))
        assertTrue(
            terminalHelperBody.indexOf("checkpointStore.markRequestTerminal(") <
                terminalHelperBody.indexOf("waitForRetry = false")
        )
        assertTrue(workerBody.contains("checkpointStore.clearCompletedAndRunIfCurrent("))
        assertTrue(
            workerBody.indexOf("checkpointStore.clearCompletedAndRunIfCurrent(") >
                workerBody.indexOf("scanLocalFilesAwait(")
        )
    }

    @Test
    fun `migration worker owns the progress session before collecting the shared flow`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val doWorkBody = methodBody(source, "doWork")
        val beginIndex = doWorkBody.indexOf("beginMigrationProgressSession(")
        val collectorIndex = doWorkBody.indexOf("val progressJob = launch")
        val collectIndex = doWorkBody.indexOf("migrationProgressFlow.collect")
        val endIndex = doWorkBody.indexOf("endMigrationProgressSession(")

        assertTrue(beginIndex >= 0)
        assertTrue(collectorIndex > beginIndex)
        assertTrue(collectIndex > collectorIndex)
        assertTrue(endIndex > collectorIndex)
    }

    @Test
    fun `old worker cannot release permission outside owner guarded completion`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val workerBody = methodBody(source, "runMigration")
        val completionIndex = workerBody.indexOf("clearCompletedAndRunIfCurrent(")
        val releaseIndex = workerBody.indexOf("releasePersistedDirectoryPermission(")

        assertTrue(completionIndex >= 0)
        assertTrue(releaseIndex > completionIndex)
    }

    @Test
    fun `replacement backup deletion reports its own cleanup progress`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val body = methodBody(source, "cleanupMigrationReplacementBackups")
        val declarationStart = Regex(
            "private\\s+(?:suspend\\s+)?fun\\s+cleanupMigrationReplacementBackups\\("
        ).find(source)?.range?.first ?: -1
        val declarationEnd = source.indexOf('{', declarationStart)
        assertTrue(declarationStart >= 0 && declarationEnd > declarationStart)
        assertTrue(
            source.substring(declarationStart, declarationEnd)
                .contains("progressTracker: ManagedMigrationProgressReporter?")
        )
        assertTrue(body.contains("progressTracker?.startCleanup(total"))
        assertTrue(body.contains("progressTracker?.finishCleanup("))
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
    fun `new pending audio and metadata writes stay under the dedicated tmp child`() {
        val storageSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val saveBody = methodBody(storageSource, "saveAudioFromTempBlocking")
        val pendingMetadataBody = methodBody(storageSource, "writePendingAudioMetadata")

        assertTrue(saveBody.contains("resolveTemporaryRoot("))
        assertTrue(saveBody.contains("FileStorageBackend(temporaryRoot.dir)"))
        assertTrue(saveBody.contains("pendingTarget = File(temporaryRoot.dir, pendingName)"))
        assertTrue(saveBody.contains("parent = temporaryRoot.tree"))
        assertFalse(saveBody.contains("FileStorageBackend(root.dir)"))

        assertTrue(pendingMetadataBody.contains("resolveTemporaryRoot("))
        assertTrue(pendingMetadataBody.contains("root = temporaryRoot"))
        assertTrue(
            pendingMetadataBody.contains(
                "displayName = \"\$audioName\$PENDING_METADATA_SUFFIX\""
            )
        )
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
