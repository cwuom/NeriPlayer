package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun `recoverable operation rehandoff excludes live and user stopped hosts`() {
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
    fun `batch publishes its membership before preparation and hands off prepared songs early`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val startBody = methodBody(source, "startBatchDownload")
        val preparationBody = methodBody(source, "prepareAndScheduleBatchDownloadSession")

        assertTrue(
            startBody.indexOf("beginBatchDownloadPresentation(requestedSongs)") <
                startBody.indexOf("scope.launch")
        )
        assertTrue(preparationBody.contains("readyRequests"))
        assertFalse(preparationBody.contains("BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT"))
        val earlyHandoffIndex = preparationBody.indexOf("readyRequests.forEach")
        val batchSchedulingIndex = preparationBody.indexOf("schedulePendingBatchDownloads(")
        assertTrue(earlyHandoffIndex >= 0)
        assertTrue(batchSchedulingIndex >= 0)
        assertTrue(
            earlyHandoffIndex < batchSchedulingIndex
        )
    }

    @Test
    fun `batch clears a preloaded presentation when no task can be prepared`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val preparationBody = methodBody(source, "prepareAndScheduleBatchDownloadSession")

        assertTrue(
            preparationBody.contains(
                "if (claimableSongs.isEmpty()) {\n" +
                    "            clearBatchDownloadPresentation(session.batchPresentationId)"
            )
        )
        assertTrue(
            preparationBody.contains(
                "if (!prepareBatchDownloadTasks(session, claimableSongs)) {\n" +
                    "            clearBatchDownloadPresentation(session.batchPresentationId)"
            )
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
    }

    @Test
    fun `batch staging uses bounded operation snapshots instead of per song reads`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val roomStoreSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/DownloadExecutionRoomStore.kt"
        ).readText()
        val recoveryStoreSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/queue/DownloadRecoveryRoomStore.kt"
        ).readText()
        val stagingBody = methodBody(managerSource, "stageAndPromotePendingDownloadQueue")
        val batchBody = methodBody(managerSource, "startBatchDownload")
        val pendingUpsertBody = methodBody(recoveryStoreSource, "upsertPendingDownloadQueue")
        val waitingUpsertBody = methodBody(recoveryStoreSource, "upsertWaitingStorageMutation")

        assertTrue(stagingBody.contains("readOperationRequestMetadata("))
        assertTrue(stagingBody.contains("readOperationIdentities("))
        assertTrue(stagingBody.contains("promoteWaitingStorageMutations("))
        assertFalse(stagingBody.contains("rememberPendingDownloadQueue("))
        assertTrue(batchBody.contains("val operationHeaders"))
        assertFalse(batchBody.contains("val operationSnapshots"))
        assertTrue(roomStoreSource.contains("normalizedOperationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE)"))
        assertTrue(pendingUpsertBody.contains("rehydrateMalformedReusableOperations("))
        assertFalse(pendingUpsertBody.contains("rehydrateMalformedReusableOperation("))
        assertTrue(waitingUpsertBody.contains("findAllHeadersByStableKeys("))
        assertTrue(waitingUpsertBody.contains("findAllHeadersByOperationIds("))
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
                "private suspend fun recoverPendingDownloadsForStartup(\n" +
                    "        context: Context,\n" +
                    "        admissionTicket: Long?\n"
            )
        )
        assertTrue(
            source.contains(
                "recoverPendingDownloadsForStartup(\n                    context = appContext,\n                    admissionTicket = startupAdmissionTicket"
            )
        )
    }

    @Test
    fun `stale batch ticket cannot schedule an OS host after clear all`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulingBody = methodBody(source, "schedulePendingBatchDownload")

        val admissionIndex = schedulingBody.indexOf(
            "admitDownloadMutation(\n                context = session.context,\n                admissionTicket = session.admissionTicket"
        )
        val hostScheduleIndex = schedulingBody.indexOf(
            "DownloadExecutionHosts.default.schedule("
        )

        assertTrue(admissionIndex >= 0)
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
            "private suspend fun schedulePendingBatchDownload("
        )
        val returnTypeIndex = source.indexOf("): Boolean {", signatureIndex)

        assertTrue(callIndex >= 0)
        assertTrue(breakIndex > callIndex)
        assertTrue(signatureIndex >= 0)
        assertTrue(returnTypeIndex > signatureIndex)
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = listOf(
            "private fun $methodName(",
            "private suspend fun $methodName(",
            "suspend fun $methodName("
        ).asSequence()
            .map(source::indexOf)
            .firstOrNull { index -> index >= 0 }
            ?: -1
        require(signatureStart >= 0) { "method not found: $methodName" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
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
