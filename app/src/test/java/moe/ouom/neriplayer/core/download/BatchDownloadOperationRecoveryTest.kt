package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutationForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.awaitDownloadAdmissionTicket
import moe.ouom.neriplayer.core.download.manager.admission.awaitDownloadAdmissionTicketForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.promoteUserInitiatedInFlightRequests
import moe.ouom.neriplayer.core.download.manager.admission.resolveOperationRequestsForBatchBinding
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueue
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueuePage
import moe.ouom.neriplayer.core.download.manager.batch.beginBatchDownloadPresentation
import moe.ouom.neriplayer.core.download.manager.batch.cancelBatchDownloadPresentationMembers
import moe.ouom.neriplayer.core.download.manager.batch.claimAndPrepareBatchArtifact
import moe.ouom.neriplayer.core.download.manager.batch.clearBatchDownloadPresentationWithoutOutstandingWork
import moe.ouom.neriplayer.core.download.manager.batch.clearSongCancellationForFreshStart
import moe.ouom.neriplayer.core.download.manager.batch.ensureDurableBatchSnapshot
import moe.ouom.neriplayer.core.download.manager.batch.findClaimableBatchDownloadSongs
import moe.ouom.neriplayer.core.download.manager.batch.findPendingAudioForFinalization
import moe.ouom.neriplayer.core.download.manager.batch.findStrictlyCompletedBatchSongKeys
import moe.ouom.neriplayer.core.download.manager.batch.markBatchDownloadPresentationTerminal
import moe.ouom.neriplayer.core.download.manager.batch.prepareAndScheduleBatchDownloadSession
import moe.ouom.neriplayer.core.download.manager.batch.prepareBatchDownloadTasks
import moe.ouom.neriplayer.core.download.manager.batch.recoverInFlightDownloadOperations
import moe.ouom.neriplayer.core.download.manager.batch.rememberPendingDownloadQueue
import moe.ouom.neriplayer.core.download.manager.batch.requestAllDownloadTaskCancellation
import moe.ouom.neriplayer.core.download.manager.batch.runBatchDownloadSession
import moe.ouom.neriplayer.core.download.manager.batch.schedulePendingBatchDownload
import moe.ouom.neriplayer.core.download.manager.batch.schedulePendingBatchDownloads
import moe.ouom.neriplayer.core.download.manager.batch.seedInitialBatchDownloadPresentation
import moe.ouom.neriplayer.core.download.manager.batch.startBatchDownload
import moe.ouom.neriplayer.core.download.manager.batch.startBatchDownloadConfirmed
import moe.ouom.neriplayer.core.download.manager.batch.tryFinalizePreparedBatchArtifact
import moe.ouom.neriplayer.core.download.manager.catalog.awaitDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.releaseDownloadArtifactAfterExecutionOwnershipLoss
import moe.ouom.neriplayer.core.download.manager.commit.cleanupDownloadArtifactsBeforeFreshStart
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingDownloadsForStartup
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingResumableDownloadsLocked
import moe.ouom.neriplayer.core.download.manager.recovery.resolveCoreRecoveryAudioCandidate
import moe.ouom.neriplayer.core.download.manager.recovery.resolvePendingDownloadRecoveryPlan
import moe.ouom.neriplayer.core.download.manager.runtime.deferPendingDownloadRecoveryForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.isMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.loadFinalizationRecoverySnapshot
import moe.ouom.neriplayer.core.download.manager.runtime.prepareConfirmedDownload
import moe.ouom.neriplayer.core.download.manager.runtime.recoverPostCoreDownloadOperation
import moe.ouom.neriplayer.core.download.manager.runtime.reopenMissingPostCoreArtifactForFreshTransfer
import moe.ouom.neriplayer.core.download.manager.runtime.requestStorageExhaustionCancellation
import moe.ouom.neriplayer.core.download.manager.runtime.scheduleUserDownload
import moe.ouom.neriplayer.core.download.manager.runtime.settleAndRemoveRecoveredTask
import moe.ouom.neriplayer.core.download.manager.runtime.startDownloadConfirmed
import moe.ouom.neriplayer.core.download.model.BatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.model.resolveBatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.model.resolveDownloadPreserveStaging
import moe.ouom.neriplayer.core.download.model.selectBatchArtifactLeaseForCancellation
import moe.ouom.neriplayer.core.download.model.selectBatchDownloadCandidates
import moe.ouom.neriplayer.core.download.model.shouldPreserveBatchPreparationForHandedOffOperation
import moe.ouom.neriplayer.core.download.model.shouldRehandoffRecoveredDownloadOperation
import moe.ouom.neriplayer.core.download.policy.isDownloadFinalizationDurablySettled
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

class BatchDownloadOperationRecoveryTest {
    @Test
    fun `three selected songs remain three batch candidates when none is active`() {
        val songs = (1L..3L).map { id -> song(id) }

        assertEquals(
            songs,
            selectBatchDownloadCandidates(
                songs = songs,
                inFlightSongKeys = emptySet()
            )
        )
    }

    @Test
    fun `batch keeps every selected song except a truly in flight operation`() {
        val songs = (1L..3L).map { id -> song(id) }

        val candidates = selectBatchDownloadCandidates(
            songs = songs + songs.first(),
            inFlightSongKeys = setOf(songs[1].stableKey())
        )

        assertEquals(listOf(songs[0], songs[2]), candidates)
    }

    @Test
    fun `newly persisted waiting request remains readable before metadata reload`() {
        val request = DownloadExecutionRequest(
            operationId = "operation-new-batch",
            song = song(11L)
        )

        val stableKey = GlobalDownloadManager.resolveBatchWaitingOperationStableKey(
            directRequest = request,
            metadataStableKey = null,
            identityStableKey = request.song.stableKey()
        )

        assertEquals(request.song.stableKey(), stableKey)
        assertTrue(
            GlobalDownloadManager.isBatchWaitingOperationReadable(
                directRequest = request,
                metadataAvailable = false,
                stableKey = stableKey,
                identityStableKey = request.song.stableKey()
            )
        )
    }

    @Test
    fun `metadata fallback keeps a recovered waiting request readable`() {
        val stableKey = song(12L).stableKey()

        assertEquals(
            stableKey,
            GlobalDownloadManager.resolveBatchWaitingOperationStableKey(
                directRequest = null,
                metadataStableKey = stableKey,
                identityStableKey = stableKey
            )
        )
        assertTrue(
            GlobalDownloadManager.isBatchWaitingOperationReadable(
                directRequest = null,
                metadataAvailable = true,
                stableKey = stableKey,
                identityStableKey = stableKey
            )
        )
    }

    @Test
    fun `identity alone does not promote an unreadable waiting request`() {
        val stableKey = song(13L).stableKey()

        assertEquals(
            stableKey,
            GlobalDownloadManager.resolveBatchWaitingOperationStableKey(
                directRequest = null,
                metadataStableKey = null,
                identityStableKey = stableKey
            )
        )
        assertFalse(
            GlobalDownloadManager.isBatchWaitingOperationReadable(
                directRequest = null,
                metadataAvailable = false,
                stableKey = stableKey,
                identityStableKey = stableKey
            )
        )
    }

    @Test
    fun `recoverable operation rescheduling excludes live and user stopped hosts`() {
        assertTrue(
            shouldRehandoffRecoveredDownloadOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                isExecuting = false,
                isStoppedByUser = false
            )
        )
        assertTrue(
            shouldRehandoffRecoveredDownloadOperation(
                operationState = "QUEUED",
                requestMatchesSong = true,
                isExecuting = false,
                isStoppedByUser = false
            )
        )
        assertTrue(
            !shouldRehandoffRecoveredDownloadOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                isExecuting = true,
                isStoppedByUser = false
            )
        )
        assertTrue(
            !shouldRehandoffRecoveredDownloadOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                isExecuting = false,
                isStoppedByUser = true
            )
        )
        assertTrue(
            !shouldRehandoffRecoveredDownloadOperation(
                operationState = "STOPPED",
                requestMatchesSong = true,
                isExecuting = false,
                isStoppedByUser = false
            )
        )
        assertTrue(
            !shouldRehandoffRecoveredDownloadOperation(
                operationState = "RUNNING",
                requestMatchesSong = false,
                isExecuting = false,
                isStoppedByUser = false
            )
        )
    }

    @Test
    fun `startup recovery preserves staging while a fresh batch starts clean`() {
        assertTrue(
            resolveDownloadPreserveStaging(
                persistedPreserveStaging = false,
                preserveRequested = true
            )
        )
        assertTrue(
            !resolveDownloadPreserveStaging(
                persistedPreserveStaging = false,
                preserveRequested = false
            )
        )
        assertTrue(
            resolveDownloadPreserveStaging(
                persistedPreserveStaging = true,
                preserveRequested = false
            )
        )
    }

    @Test
    fun `retryable and queued operations schedule while running is handed off`() {
        assertEquals(
            BatchOperationScheduleAction.SCHEDULE,
            resolveBatchOperationScheduleAction("RETRYABLE", requestMatchesSong = true)
        )
        assertEquals(
            BatchOperationScheduleAction.SCHEDULE,
            resolveBatchOperationScheduleAction("QUEUED", requestMatchesSong = true)
        )
        assertEquals(
            BatchOperationScheduleAction.HANDED_OFF,
            resolveBatchOperationScheduleAction("RUNNING", requestMatchesSong = true)
        )
        assertEquals(
            BatchOperationScheduleAction.SCHEDULE,
            resolveBatchOperationScheduleAction(
                operationState = "RUNNING",
                requestMatchesSong = true,
                isExecuting = false
            )
        )
        assertEquals(
            BatchOperationScheduleAction.INVALID,
            resolveBatchOperationScheduleAction("RUNNING", requestMatchesSong = false)
        )
        listOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED").forEach { state ->
            assertEquals(
                BatchOperationScheduleAction.RELEASE,
                resolveBatchOperationScheduleAction(state, requestMatchesSong = true)
            )
        }
        assertEquals(
            BatchOperationScheduleAction.INVALID,
            resolveBatchOperationScheduleAction(null, requestMatchesSong = false)
        )
    }

    @Test
    fun `batch preparation preserves an attempt that becomes in flight`() {
        listOf(
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        ).forEach { state ->
            assertTrue(
                shouldPreserveBatchPreparationForHandedOffOperation(
                    operationState = state,
                    requestMatchesSong = true,
                    attemptId = 42L,
                    requestGenerationCurrent = true
                )
            )
        }
        assertFalse(
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = "QUEUED",
                requestMatchesSong = true,
                attemptId = 42L,
                requestGenerationCurrent = true
            )
        )
        assertFalse(
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                attemptId = null,
                requestGenerationCurrent = true
            )
        )
        assertFalse(
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                attemptId = 42L,
                requestGenerationCurrent = false
            )
        )
        assertFalse(
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = "RUNNING",
                requestMatchesSong = true,
                attemptId = 42L,
                requestGenerationCurrent = true,
                isExecuting = false
            )
        )
    }

    @Test
    fun `batch preparation never tears down an operation that raced into the OS host`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val preparationBody = methodBody(source, "prepareAndScheduleBatchDownloadSession")
        val prepareTasksBody = methodBody(source, "prepareBatchDownloadTasks")
        val claimableBody = methodBody(source, "findClaimableBatchDownloadSongs")
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")
        val clearPresentationBody = methodBody(
            source,
            "clearBatchDownloadPresentationWithoutOutstandingWork"
        )

        assertTrue(prepareTasksBody.contains("HOST_ADMISSION_HANDOFF_STATES"))
        assertTrue(claimableBody.contains("HOST_ADMISSION_HANDOFF_STATES"))
        assertTrue(
            artifactBody.indexOf("shouldPreserveBatchPreparationForHandedOffOperation(") <
                artifactBody.indexOf("managedDownloadArtifactCoordinator.claim(")
        )
        assertTrue(artifactBody.contains("session.handedOffSongKeys += songKey"))
        assertTrue(artifactBody.contains("session.scheduledSongKeys += songKey"))
        assertTrue(
            artifactBody.contains(
                "isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)"
            )
        )
        assertTrue(
            methodBody(source, "schedulePendingBatchDownload").contains(
                "isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)"
            )
        )
        assertTrue(
            preparationBody.contains(
                "val settledAttemptIds = session.settledAttemptIds.filterKeys"
            )
        )
        assertTrue(
            preparationBody.contains("songKey in session.settledSongKeys")
        )
        assertTrue(
            clearPresentationBody.contains("session.handedOffSongKeys.isNotEmpty()")
        )
    }

    @Test
    fun `batch scheduling delegates every operation to the durable host admission`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulingBody = methodBody(source, "schedulePendingBatchDownload")
        val recoveryBody = methodBody(source, "recoverInFlightDownloadOperations")

        assertTrue(schedulingBody.contains("DownloadExecutionHosts.default.schedule"))
        assertTrue(recoveryBody.contains("DownloadExecutionHosts.default.schedule"))
        assertTrue(!schedulingBody.contains("awaitBatchDownloadHostSlot"))
        assertTrue(!recoveryBody.contains("awaitBatchDownloadHostSlot"))
        assertTrue(!source.contains("BATCH_DOWNLOAD_HOST_WINDOW_SIZE"))
    }

    @Test
    fun `batch persists its membership before publishing and hands off prepared songs early`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val startBody = methodBody(source, "startBatchDownload")
        val preparationBody = methodBody(source, "prepareAndScheduleBatchDownloadSession")

        val durableSnapshotIndex = startBody.indexOf("ensureDurableBatchSnapshot(")
        val beginPresentationIndex = startBody.indexOf("beginBatchDownloadPresentation(")
        val batchAdmissionIndex = startBody.indexOf("val batchCreated = admitDownloadMutationForStableKeys")

        assertTrue(durableSnapshotIndex >= 0)
        assertTrue(beginPresentationIndex > durableSnapshotIndex)
        assertTrue(durableSnapshotIndex > batchAdmissionIndex)
        assertTrue(startBody.contains("seedInitialBatchDownloadPresentation"))
        assertTrue(startBody.contains("cancelBatchDownloadPresentationMembers"))
        assertFalse(startBody.contains("beginBatchDownloadPresentation(requestedSongs)"))
        assertTrue(preparationBody.contains("pendingSongs.lastOrNull"))
        assertFalse(preparationBody.contains("BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT"))
        assertTrue(
            preparationBody.contains(
                "resolveDownloadDispatchWindow(currentDownloadParallelism(session.context))"
            )
        )
        assertFalse(preparationBody.contains("BATCH_PENDING_MEMORY_LIMIT"))
        val earlyHandoffIndex = preparationBody.indexOf(
            "val request = session.pendingSongs.lastOrNull"
        )
        val batchSchedulingIndex = preparationBody.indexOf("schedulePendingBatchDownloads(")
        assertTrue(earlyHandoffIndex >= 0)
        assertTrue(batchSchedulingIndex >= 0)
        assertTrue(
            earlyHandoffIndex < batchSchedulingIndex
        )
    }

    @Test
    fun `finalization recovery reuses one snapshot and accepts formal audio`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = methodBody(source, "findPendingAudioForFinalization")
        val snapshotBody = methodBody(source, "loadFinalizationRecoverySnapshot")

        assertTrue(recoveryBody.contains("resolveCoreRecoveryAudioCandidate"))
        assertTrue(recoveryBody.contains("allowFormalAudio = true"))
        assertTrue(recoveryBody.contains("loadFinalizationRecoverySnapshot"))
        assertTrue(source.contains("FINALIZATION_RECOVERY_SNAPSHOT_TTL_MS"))
        assertTrue(source.contains("finalizationRecoverySnapshotMutex"))
        assertTrue(snapshotBody.contains("cache.forceRefreshed"))
        assertTrue(snapshotBody.contains("!forceRefresh || cache.forceRefreshed"))
        assertTrue(snapshotBody.contains("allowFreshCacheReuse"))
        assertTrue(snapshotBody.contains("includeMetadataLessAudioForLegacyUpgrade = true"))
        assertTrue(recoveryBody.contains("preferredAudioReference"))
        assertTrue(source.contains("downloadedSongCatalogIndex.find(song)"))
        assertTrue(recoveryBody.contains("snapshot.audioEntriesByLookupKey"))
        assertTrue(source.contains("return !audio.isDirectory"))
        assertTrue(source.contains("tryFinalizePreparedBatchArtifact"))
        assertTrue(
            source.indexOf("tryFinalizePreparedBatchArtifact(session, song, preparedArtifact)") <
                source.indexOf("session.enqueue(song, attemptId, preparedArtifact.operationId)")
        )
        assertTrue(source.contains("recoverPostCoreDownloadOperation"))
        assertTrue(source.contains("findDownloadedAudioIncludingMetadataLess"))
        assertTrue(source.contains("snapshot = downloadLibrarySnapshot"))
    }

    @Test
    fun `batch clears a preloaded presentation when no task can be prepared`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val preparationBody = methodBody(source, "prepareAndScheduleBatchDownloadSession")

        val noClaimableSongsIndex = preparationBody.indexOf("if (claimableSongs.isEmpty())")
        val preparationFailedIndex = preparationBody.indexOf(
            "if (!prepareBatchDownloadTasks(session, claimableWindow))"
        )
        val clearPresentationCall = "clearBatchDownloadPresentation(session.batchPresentationId)"

        assertTrue(
            noClaimableSongsIndex >= 0 &&
                preparationBody.indexOf(clearPresentationCall, noClaimableSongsIndex) >
                noClaimableSongsIndex
        )
        assertTrue(
            preparationFailedIndex >= 0 &&
                preparationBody.indexOf(clearPresentationCall, preparationFailedIndex) >
                preparationFailedIndex
        )
        assertTrue(
            preparationBody.contains(
                "val settledAdmitted = if (session.settledSongKeys.isEmpty())"
            )
        )
        assertTrue(
            preparationBody.contains(
                "clearBatchDownloadPresentationWithoutOutstandingWork(session)"
            )
        )
    }

    @Test
    fun `batch restart rehydrates reusable operations and rehands off only inactive inflight work`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val startBody = methodBody(source, "startBatchDownload")
        val recoveryBody = methodBody(source, "recoverInFlightDownloadOperations")

        assertTrue(startBody.contains("val inFlightOperationRequests"))
        assertTrue(startBody.contains("findReadableOperationsBySongKeys("))
        assertTrue(startBody.contains("excludeUserStoppedOperations = true"))
        assertTrue(startBody.contains("inFlightOperationSongKeys"))
        assertTrue(
            startBody.contains(
                "!DownloadExecutionHosts.default.isExecuting(request.operationId)"
            )
        )
        assertTrue(
            startBody.contains(
                "inFlightSongKeys = inFlightOperationSongKeys"
            )
        )
        assertTrue(
            !startBody.contains("?: DownloadExecutionRoomStore.findReadableOperationIdForSong")
        )
        assertTrue(!startBody.contains("findReadableOperationIdForSong("))
        assertTrue(recoveryBody.contains("shouldRehandoffRecoveredDownloadOperation("))
        assertTrue(recoveryBody.contains("DownloadExecutionRoomStore.isStopped("))
        assertTrue(recoveryBody.contains("DownloadExecutionHosts.default"))
        assertTrue(recoveryBody.contains("var handedOffPostCore = false"))
        assertTrue(recoveryBody.contains("PostCoreDownloadRecoveryWorker.schedule("))
        assertTrue(!recoveryBody.contains("recoverPostCoreDownloadOperation("))
        assertTrue(
            recoveryBody.indexOf("PostCoreDownloadRecoveryWorker.schedule(") <
                recoveryBody.indexOf("DownloadExecutionHosts.default.schedule(")
        )
    }

    @Test
    fun `post core recovery keeps durable enrichment state when finalization is not yet confirmed`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val executeBody = methodBody(source, "executeDownloadOperation")
        val recoveryBody = methodBody(source, "recoverPostCoreDownloadOperation")
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")

        val postCoreBranch = executeBody
            .substringAfter("val restartMissingPostCoreArtifact")
            .substringBefore("downloadAdmissionGate.awaitOpen()")
        assertTrue(postCoreBranch.contains("return DownloadExecutionResult.AlreadyHandled"))
        assertFalse(postCoreBranch.contains("return DownloadExecutionResult.Retry"))
        assertTrue(recoveryBody.contains("currentStateAnyRoot("))
        assertTrue(recoveryBody.contains("isDownloadFinalizationDurablySettled("))
        assertTrue(recoveryBody.contains("matchingCompletedTask"))
        assertTrue(recoveryBody.contains("settleAndRemoveRecoveredTask"))
        assertFalse(recoveryBody.contains("finalized = matchingCompletedTask != null"))
        assertTrue(source.contains("removeDownloadTask"))
        assertTrue(recoveryBody.contains("promoteStatus = durablePostCore"))
        assertTrue(source.contains("audioEntriesWithoutMetadata"))
        assertTrue(artifactBody.contains("attemptId == null && !canFinalizePreparedArtifact"))
        assertTrue(source.contains("if (!artifactFinalized)"))
    }

    @Test
    fun `post core rows without an audio reference reenter the transfer path`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val roomSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/persistence/DownloadExecutionRoomStore.kt"
        ).readText()
        val executeBody = methodBody(managerSource, "executeDownloadOperation")

        val reopenIndex = executeBody.indexOf(
            "reopenMissingPostCoreArtifactForFreshTransfer("
        )
        val recoveryIndex = executeBody.indexOf(
            "recoverPostCoreDownloadOperation("
        )

        assertTrue(reopenIndex >= 0)
        assertTrue(recoveryIndex > reopenIndex)
        assertTrue(executeBody.contains("!restartMissingPostCoreArtifact"))
        assertTrue(
            managerSource.contains(
                "shouldRestartPostCoreOperationForFreshTransfer"
            )
        )
        assertTrue(
            managerSource.contains("findPendingAudioForFinalization(")
        )
        assertTrue(
            roomSource.contains(
                "reopenMissingPostCoreArtifactForFreshTransfer"
            )
        )
        assertTrue(
            roomSource.contains("state = \"RUNNING\"")
        )
    }

    @Test
    fun `automatic post core recovery never reclaims a referenced artifact`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val reopenBody = methodBody(
            source,
            "reopenMissingPostCoreArtifactForFreshTransfer"
        )

        assertTrue(reopenBody.contains("allowFreshTransferReclaim = false"))
        assertTrue(
            methodBody(
                source,
                "shouldRestartPostCoreOperationForFreshTransfer"
            ).contains("acquiredArtifact.audioReference.isNullOrBlank()")
        )
        assertTrue(
            reopenBody.contains("已有可恢复音频，跳过重新传输")
        )
    }

    @Test
    fun `post core recovery uses the current artifact lease instead of a stale request lease`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = methodBody(source, "recoverPostCoreDownloadOperation")

        assertTrue(recoveryBody.contains("val expectedArtifactLeaseId = when"))
        assertTrue(
            recoveryBody.contains("artifact != null -> artifact.leaseId")
        )
        assertTrue(recoveryBody.contains("expectedArtifactLeaseId = expectedArtifactLeaseId"))
        assertTrue(recoveryBody.contains("expectedLeaseId = expectedArtifactLeaseId"))
    }

    @Test
    fun `operation identity can settle a batch member when task attempt is unavailable`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val terminalBody = methodBody(source, "markBatchDownloadPresentationTerminal")

        assertTrue(
            terminalBody.contains(
                "if (normalizedAttemptId == null && operationId.isNullOrBlank())"
            )
        )
        assertTrue(
            terminalBody.contains("val effectiveAttemptId = normalizedAttemptId ?: memberAttemptId")
        )
        assertTrue(
            terminalBody.contains("attemptId = normalizedAttemptId")
        )
    }

    @Test
    fun `user requested unavailable artifact bypasses recovery and enters a fresh transfer`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val prepareBody = methodBody(managerSource, "prepareConfirmedDownload")
        val startBody = methodBody(managerSource, "startDownloadConfirmed")
        val batchArtifactBody = methodBody(managerSource, "claimAndPrepareBatchArtifact")

        assertTrue(
            prepareBody.contains(
                "allowFreshTransferReclaim = persistedOperationRequest?.userInitiated == true"
            )
        )
        assertTrue(
            batchArtifactBody.contains(
                "allowFreshTransferReclaim = operationRequest.userInitiated"
            )
        )
        assertTrue(startBody.contains("preservesExistingReference == true"))
        assertTrue(startBody.contains("val forceFreshTransfer"))
        assertTrue(startBody.contains("if (forceFreshTransfer)"))
        assertTrue(
            startBody.contains("cleanupBeforeStart && !preserveArtifactForRepair")
        )
        assertTrue(
            locateProjectFile(
                "app/src/main/java/moe/ouom/neriplayer/core/download/artifact/" +
                    "ManagedDownloadArtifactCoordinator.kt"
            ).readText().contains(
                "shouldForceFreshTransferForUser"
            )
        )
    }

    @Test
    fun `new user request promotes reused inflight operation intent`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val roomSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/persistence/DownloadExecutionRoomStore.kt"
        ).readText()
        val batchBody = methodBody(managerSource, "startBatchDownload")
        val singleBody = methodBody(managerSource, "scheduleUserDownload")

        assertTrue(batchBody.contains("promoteUserInitiatedInFlightRequests"))
        assertTrue(batchBody.contains("userInitiated = userInitiated"))
        assertTrue(singleBody.contains("findReadableOperationsBySongKeys("))
        assertTrue(singleBody.contains("inFlightRequestToRecover"))
        assertTrue(roomSource.contains("suspend fun promoteUserInitiatedOperation("))
        assertTrue(roomSource.contains("request.copy(userInitiated = true)"))
        assertTrue(roomSource.contains("val effectiveUserInitiated = request.userInitiated ||"))
        assertTrue(roomSource.contains("existingRequest?.userInitiated == true"))
        assertTrue(
            roomSource.contains(
                "header.state !in IN_FLIGHT_OPERATION_STATES + REUSABLE_OPERATION_STATES"
            )
        )
        assertTrue(
            managerSource.contains("effectiveExistingReusableOperationsBySongKey")
        )
        assertTrue(
            managerSource.contains("val latestPromotedRequest = operationId?.let")
        )
        assertTrue(
            managerSource.contains("reason = \"user_retry_intent_promoted\"")
        )
    }

    @Test
    fun `batch staging uses bounded operation snapshots instead of per song reads`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val roomReadStoreSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/persistence/DownloadExecutionRoomReadStore.kt"
        ).readText()
        val recoveryStoreSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/queue/DownloadRecoveryRoomStore.kt"
        ).readText()
        val stagingBody = methodBody(managerSource, "stageAndPromotePendingDownloadQueue")
        val stagingPageBody = methodBody(
            managerSource,
            "stageAndPromotePendingDownloadQueuePage"
        )
        val bindingRequestsBody = methodBody(
            managerSource,
            "resolveOperationRequestsForBatchBinding"
        )
        val batchBody = methodBody(managerSource, "startBatchDownload")
        val pendingUpsertBody = methodBody(recoveryStoreSource, "upsertPendingDownloadQueue")
        val waitingUpsertBody = methodBody(recoveryStoreSource, "upsertWaitingStorageMutation")

        assertTrue(stagingBody.contains("chunked(BATCH_OPERATION_STAGE_PAGE_SIZE)"))
        assertTrue(stagingBody.contains("onPageReady(pageIndex, stagedPage)"))
        assertTrue(stagingBody.contains("resolveOperationRequestsForBatchBinding("))
        assertTrue(bindingRequestsBody.contains("val missingOperationIds = normalizedOperationIds"))
        assertTrue(bindingRequestsBody.contains("if (missingOperationIds.isEmpty())"))
        assertTrue(bindingRequestsBody.contains("operationIds = missingOperationIds"))
        assertTrue(stagingPageBody.contains("readOperationRequestMetadata("))
        assertTrue(stagingPageBody.contains("readOperationIdentities("))
        assertTrue(stagingPageBody.contains("promoteWaitingStorageMutations("))
        assertFalse(stagingPageBody.contains("rememberPendingDownloadQueue("))
        assertTrue(batchBody.contains("var operationHeaders"))
        assertTrue(batchBody.contains("val operationIdsMissingBatchIdentity"))
        assertTrue(batchBody.contains("if (operationIdsMissingBatchIdentity.isNotEmpty())"))
        assertTrue(batchBody.contains("resolveOperationRequestsForBatchBinding("))
        assertFalse(batchBody.contains("val operationSnapshots"))
        assertTrue(
            roomReadStoreSource.contains(
                "normalizedOperationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)"
            )
        )
        assertTrue(pendingUpsertBody.contains("rehydrateMalformedReusableOperations("))
        assertFalse(pendingUpsertBody.contains("rehydrateMalformedReusableOperation("))
        assertTrue(waitingUpsertBody.contains("findAllHeadersByStableKeys("))
        assertTrue(waitingUpsertBody.contains("findAllHeadersByOperationIds("))
    }

    @Test
    fun `batch preflight settles finalized audio before staging and retains waiting operations`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchBody = methodBody(source, "startBatchDownload")
        val strictCompletionBody = methodBody(source, "findStrictlyCompletedBatchSongKeys")
        val snapshotIndex = batchBody.indexOf("val initialDownloadLibrarySnapshot =")
        val preflightIndex = batchBody.indexOf("val preflightCompletedSongKeys =")
        val stagingIndex = batchBody.indexOf(
            "val stagedQueue = stageAndPromotePendingDownloadQueue("
        )

        assertTrue(snapshotIndex >= 0)
        assertTrue(preflightIndex > snapshotIndex)
        assertTrue(stagingIndex > preflightIndex)
        assertTrue(batchBody.contains("val songsToStage = stageCandidateSongs.filterNot"))
        assertTrue(batchBody.contains("listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)"))
        assertTrue(
            batchBody.contains(
                "filterNot { songKey -> songKey in existingOperationSongKeys }"
            )
        )
        assertTrue(strictCompletionBody.contains("snapshot?.takeIf { it.rootEntriesComplete }"))
        assertTrue(
            strictCompletionBody.contains("findDownloadedAudioIncludingMetadataLess")
        )
        assertTrue(strictCompletionBody.contains("isFinalizedDownloadedAudioEntry("))
        assertTrue(strictCompletionBody.contains("isMetadataOwnedBySong(metadata, song)"))
    }

    @Test
    fun `definitive storage exhaustion marks all operations before asynchronous clearing`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val audioSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val cancellationBody = methodBody(
            managerSource,
            "requestStorageExhaustionCancellation"
        )
        val failureBody = methodBody(audioSource, "handleDownloadAttemptFailure")

        assertTrue(failureBody.contains("storageFailureKind.isDefinitive"))
        assertTrue(failureBody.contains("cancelAllDownloads = true"))
        assertTrue(failureBody.contains("STORAGE_SPACE_CONTENTION_RETRY_DELAY_MS"))
        assertTrue(cancellationBody.contains("DownloadExecutionRoomStore.requestCancelAllFast"))
        assertTrue(cancellationBody.contains("requestAllDownloadTaskCancellation()"))
        assertTrue(
            cancellationBody.indexOf("requestCancelAllFast") <
                cancellationBody.indexOf("requestAllDownloadTaskCancellation()")
        )
    }

    @Test
    fun `batch presentation snapshots active attempts once instead of scanning per song`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val presentationBody = methodBody(source, "beginBatchDownloadPresentation")

        assertTrue(presentationBody.contains("taskStore.currentTasks()"))
        assertTrue(presentationBody.contains("activeAttemptIdsBySongKey"))
        assertTrue(!presentationBody.contains("taskStore.findTask("))
        assertTrue(source.contains("scope = downloadPresentationScope"))
    }

    @Test
    fun `new download intents wait for storage deletion before becoming runnable`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val singleBody = methodBody(source, "scheduleUserDownload")
        val batchBody = methodBody(source, "startBatchDownload")

        listOf(singleBody, batchBody).forEach { body ->
            val deletionBarrierIndex = body.indexOf("awaitDownloadedSongDeletion(")
            val ticketIndex = listOf(
                body.indexOf("awaitDownloadAdmissionTicket("),
                body.indexOf("awaitDownloadAdmissionTicketForStableKeys(")
            ).filter { index -> index >= 0 }.minOrNull() ?: -1
            val admissionIndex = listOf(
                body.indexOf("admitDownloadMutation("),
                body.indexOf("admitDownloadMutationForStableKeys(")
            ).filter { index -> index >= 0 }.minOrNull() ?: -1
            val stagingIndex = body.indexOf("stageAndPromotePendingDownloadQueue(")

            assertTrue(deletionBarrierIndex >= 0)
            assertTrue(ticketIndex > deletionBarrierIndex)
            assertTrue(admissionIndex > ticketIndex)
            assertTrue(stagingIndex > admissionIndex)
        }
    }

    @Test
    fun `cancel before host handoff releases only the captured artifact lease`() {
        assertEquals(
            "old-operation",
            selectBatchArtifactLeaseForCancellation(
                handedOff = false,
                capturedLeaseId = "old-operation"
            )
        )
        assertEquals(
            null,
            selectBatchArtifactLeaseForCancellation(
                handedOff = true,
                capturedLeaseId = "old-operation"
            )
        )
        assertEquals(
            null,
            selectBatchArtifactLeaseForCancellation(
                handedOff = false,
                capturedLeaseId = null
            )
        )
    }

    @Test
    fun `batch session fixes one durable operation before artifact claim and scheduling`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val launchBody = methodBody(source, "startBatchDownloadConfirmed")
        val sessionBody = methodBody(source, "runBatchDownloadSession")
        val claimableBody = methodBody(source, "findClaimableBatchDownloadSongs")
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")
        val schedulingBody = methodBody(source, "schedulePendingBatchDownload")

        assertTrue(
            "the bounded batch lambda must delegate to the named session",
            launchBody.contains("runBatchDownloadSession(")
        )
        assertTrue(
            "the batch session must retain the operation selected before claim",
            sessionBody.contains("prepareAndScheduleBatchDownloadSession(session)") &&
                artifactBody.contains("operationIdsBySongKey[songKey]")
        )
        assertTrue(
            "batch scheduling must verify the fixed operation before enqueueing its host",
            artifactBody.contains("DownloadExecutionRoomStore.read") &&
                claimableBody.contains("DownloadExecutionRoomStore.readOperationHeaders") &&
                schedulingBody.contains("scheduleMetadataBySongKey") &&
                schedulingBody.contains("DownloadExecutionRoomStore.state")
        )
        assertTrue(
            "only the claimed execution owner may clean staging",
            !sessionBody.contains("cleanupDownloadArtifactsBeforeFreshStart") &&
                !schedulingBody.contains("cleanupDownloadArtifactsBeforeFreshStart")
        )
    }

    @Test
    fun `stale batch ticket cannot recreate a task after clear all`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val prepareTasksBody = methodBody(source, "prepareBatchDownloadTasks")
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")
        val startBatchBody = methodBody(source, "startBatchDownload")
        val recoveryBody = methodBody(source, "recoverPendingResumableDownloadsLocked")

        assertTrue(startBatchBody.contains("val capturedAdmissionTicket = requestedAdmissionTicket"))
        assertTrue(recoveryBody.contains("requestedAdmissionTicket = admissionTicket"))
        assertTrue(recoveryBody.contains("awaitAdmissionWhenUnavailable = false"))
        assertTrue(source.contains("val admissionTicket: Long,"))
        assertTrue(
            prepareTasksBody.contains("admitDownloadMutationForStableKeys(") &&
                prepareTasksBody.contains("context = session.context") &&
                prepareTasksBody.contains("admissionTicket = session.admissionTicket")
        )
        val admissionIndex = prepareTasksBody.indexOf("admitDownloadMutationForStableKeys(")
        val taskCreationIndex = prepareTasksBody.indexOf("taskStore.ensureDownloadTasks(")
        assertTrue(admissionIndex >= 0)
        assertTrue(taskCreationIndex >= 0)
        assertTrue(admissionIndex < taskCreationIndex)
        assertTrue(artifactBody.contains("session.artifactClaims.remove(songKey)"))
        assertTrue(artifactBody.contains("releaseDownloadArtifactAfterExecutionOwnershipLoss("))
    }

    @Test
    fun `recovery planning keeps mutations behind the captured admission ticket`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = methodBody(source, "recoverPendingResumableDownloadsLocked")
        val planBody = methodBody(source, "resolvePendingDownloadRecoveryPlan")
        val networkPolicyBody = methodBody(
            source,
            "deferPendingDownloadRecoveryForNetworkPolicyIfNeeded"
        )
        val startupBody = methodBody(source, "recoverPendingDownloadsForStartup")

        assertFalse(planBody.contains("rehomeActiveOperationsToCurrentLibrary"))
        assertFalse(planBody.contains("deleteWorkingDownloadArtifacts"))
        assertTrue(planBody.contains("workingFilesToDelete"))
        assertTrue(recoveryBody.contains("recoveryPlan.workingFilesToDelete"))
        assertTrue(networkPolicyBody.contains("recoveryPlan.workingFilesToDelete"))
        assertTrue(
            recoveryBody.contains("admitDownloadMutation(context, admissionTicket)")
        )
        assertTrue(networkPolicyBody.contains("admissionTicket"))
        assertTrue(
            networkPolicyBody.contains(
                "admitDownloadMutation(context, admissionTicket)"
            )
        )
        assertTrue(
            source.contains(
                "internal suspend fun GlobalDownloadManager.recoverPendingDownloadsForStartup("
            )
        )
        assertTrue(source.contains("admissionTicket = startupAdmissionTicket"))
    }

    @Test
    fun `stale batch ticket cannot schedule an OS host after clear all`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulingBody = methodBody(source, "schedulePendingBatchDownload")

        val admissionIndex = schedulingBody.indexOf("admitDownloadMutation(")
        val hostScheduleIndex = schedulingBody.indexOf(
            "DownloadExecutionHosts.default.schedule("
        )

        assertTrue(admissionIndex >= 0)
        assertTrue(schedulingBody.indexOf("context = session.context", admissionIndex) > admissionIndex)
        assertTrue(
            schedulingBody.indexOf("admissionTicket = session.admissionTicket", admissionIndex) >
                admissionIndex
        )
        assertTrue(hostScheduleIndex > admissionIndex)
        assertTrue(schedulingBody.contains("if (!admitted)"))
        assertTrue(schedulingBody.contains("session.artifactClaims.remove(songKey)"))
    }

    @Test
    fun `batch scheduling stops after the first admission expires`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchBody = methodBody(source, "schedulePendingBatchDownloads")
        val callIndex = batchBody.indexOf("val admitted = schedulePendingBatchDownload(")
        val breakIndex = batchBody.indexOf("break", callIndex)
        val signatureIndex = source.indexOf(
            "internal suspend fun GlobalDownloadManager.schedulePendingBatchDownload("
        )
        val returnTypeIndex = source.indexOf("): Boolean {", signatureIndex)

        assertTrue(callIndex >= 0)
        assertTrue(breakIndex > callIndex)
        assertTrue(signatureIndex >= 0)
        assertTrue(returnTypeIndex > signatureIndex)
    }

    @Test
    fun `batch clears stale cancellation identities before reading in flight operations`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchBody = methodBody(source, "startBatchDownload")
        val admittedIndex = batchBody.indexOf("val admittedSongs =")
        val clearIndex = batchBody.indexOf(
            "clearSongCancellationForFreshStart(",
            admittedIndex
        )
        val inFlightIndex = batchBody.indexOf("val inFlightOperationsBySongKey =", admittedIndex)

        assertTrue(admittedIndex >= 0)
        assertTrue("fresh-start fence must precede in-flight reuse", clearIndex > admittedIndex)
        assertTrue("in-flight lookup must follow the fresh-start fence", inFlightIndex > clearIndex)
    }

    @Test
    fun `user waiting requests do not reuse a legacy deterministic identity`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/queue/" +
                "DownloadRecoveryRoomStore.kt"
        ).readText()
        val waitingBody = methodBody(source, "upsertWaitingStorageMutationWithRequests")

        assertTrue(waitingBody.contains("mustCreateFreshUserOperation"))
        assertTrue(waitingBody.contains("mustCreateReplacement"))
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun song(id: Long): SongItem {
        return SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )
    }
}
