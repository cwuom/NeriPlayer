package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.engine.datasource.ChunkRequestIOException
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Timeout
import okio.Buffer
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject


class AudioDownloadManagerTest : AudioDownloadManagerTestSupport() {

    @Test
    fun `latest progress snapshot rejects an older attempt`() {
        val previous = AudioDownloadManager.DownloadProgress(
            songKey = "snapshot-song",
            songId = 1L,
            fileName = "snapshot-song.mp3",
            bytesRead = 300L,
            totalBytes = 1_000L,
            speedBytesPerSec = 10L,
            attemptId = 9L
        )
        val older = previous.copy(
            bytesRead = 500L,
            attemptId = 8L
        )
        val newer = previous.copy(
            bytesRead = 100L,
            attemptId = 10L
        )

        assertFalse(AudioDownloadManager.shouldReplaceLatestProgress(previous, older))
        assertTrue(AudioDownloadManager.shouldReplaceLatestProgress(previous, newer))
        assertEquals(newer, AudioDownloadManager.mergeLatestProgress(previous, newer))
        assertTrue(
            AudioDownloadManager.shouldReplaceLatestProgress(
                previous.copy(attemptId = null),
                newer.copy(bytesRead = 1L)
            )
        )
    }

    @Test
    fun `latest progress snapshot retains same attempt byte high water`() {
        val previous = AudioDownloadManager.DownloadProgress(
            songKey = "snapshot-song",
            songId = 1L,
            fileName = "snapshot-song.mp3",
            bytesRead = 800L,
            totalBytes = 0L,
            speedBytesPerSec = 10L,
            attemptId = 9L
        )
        val retryProgress = previous.copy(
            bytesRead = 200L,
            totalBytes = 1_000L,
            speedBytesPerSec = 0L,
            stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
        )
        val mergedRetryProgress = AudioDownloadManager.mergeLatestProgress(
            previous,
            retryProgress
        )

        assertEquals(800L, mergedRetryProgress.bytesRead)
        assertEquals(1_000L, mergedRetryProgress.totalBytes)
        assertEquals(0L, mergedRetryProgress.speedBytesPerSec)
        assertEquals(AudioDownloadManager.DownloadStage.WAITING_RETRY, mergedRetryProgress.stage)
        assertTrue(AudioDownloadManager.shouldReplaceLatestProgress(previous, retryProgress))

        val resumedProgress = AudioDownloadManager.mergeLatestProgress(
            mergedRetryProgress,
            retryProgress.copy(
                bytesRead = 300L,
                totalBytes = 500L,
                speedBytesPerSec = 20L,
                stage = AudioDownloadManager.DownloadStage.TRANSFERRING
            )
        )
        assertEquals(800L, resumedProgress.bytesRead)
        assertEquals(1_000L, resumedProgress.totalBytes)
        assertEquals(20L, resumedProgress.speedBytesPerSec)
        assertEquals(AudioDownloadManager.DownloadStage.TRANSFERRING, resumedProgress.stage)
        assertFalse(
            AudioDownloadManager.shouldReplaceLatestProgress(
                previous,
                previous.copy(bytesRead = 799L)
            )
        )

        val durableProgress = AudioDownloadManager.mergeLatestProgress(
            previous.copy(
                bytesRead = 600L,
                durableBytesRead = 512L
            ),
            previous.copy(
                bytesRead = 900L,
                durableBytesRead = 700L
            )
        )
        assertEquals(700L, durableProgress.durableBytesRead)

        val clampedDurableProgress = AudioDownloadManager.mergeLatestProgress(
            previous.copy(
                bytesRead = 600L,
                durableBytesRead = 512L
            ),
            previous.copy(
                bytesRead = 400L,
                durableBytesRead = 900L
            )
        )
        assertEquals(600L, clampedDurableProgress.durableBytesRead)
    }

    @Test
    fun `late progress from an older transfer generation is ignored`() {
        val current = AudioDownloadManager.DownloadProgress(
            songKey = "generation-song",
            songId = 1L,
            fileName = "generation-song.mp3",
            bytesRead = 800L,
            totalBytes = 1_000L,
            speedBytesPerSec = 20L,
            attemptId = 3L,
            transferGeneration = 9L,
            durableBytesRead = 700L
        )
        val late = current.copy(
            bytesRead = 950L,
            speedBytesPerSec = 30L,
            transferGeneration = 8L,
            durableBytesRead = 900L
        )

        assertFalse(AudioDownloadManager.shouldReplaceLatestProgress(current, late))
        assertEquals(current, AudioDownloadManager.mergeLatestProgress(current, late))
    }

    @Test
    fun `newer attempt wins even when its transfer generation is lower`() {
        val previous = AudioDownloadManager.DownloadProgress(
            songKey = "generation-song",
            songId = 1L,
            fileName = "generation-song.mp3",
            bytesRead = 800L,
            totalBytes = 1_000L,
            speedBytesPerSec = 20L,
            attemptId = 9L,
            transferGeneration = 12L
        )
        val newerAttempt = previous.copy(
            bytesRead = 32L,
            speedBytesPerSec = 4L,
            attemptId = 10L,
            transferGeneration = 1L
        )

        assertEquals(
            newerAttempt,
            AudioDownloadManager.mergeLatestProgress(previous, newerAttempt)
        )
    }

    @Test
    fun `new transfer generation keeps monotonic visible and durable bytes`() {
        val previous = AudioDownloadManager.DownloadProgress(
            songKey = "generation-song",
            songId = 1L,
            fileName = "generation-song.mp3",
            bytesRead = 800L,
            totalBytes = 1_000L,
            speedBytesPerSec = 20L,
            attemptId = 3L,
            transferGeneration = 8L,
            durableBytesRead = 700L
        )
        val resumed = previous.copy(
            bytesRead = 300L,
            speedBytesPerSec = 10L,
            transferGeneration = 9L,
            durableBytesRead = 250L
        )

        val merged = AudioDownloadManager.mergeLatestProgress(previous, resumed)
        assertEquals(800L, merged.bytesRead)
        assertEquals(700L, merged.durableBytesRead)
        assertEquals(9L, merged.transferGeneration)
    }

    @Test
    fun `unknown total publishes forward progress after the time window`() {
        val previous = AudioDownloadManager.PublishedProgressState(
            attemptId = 7L,
            bytesRead = 1_000L,
            totalBytes = 0L,
            percentage = -1,
            stage = AudioDownloadManager.DownloadStage.TRANSFERRING,
            emittedAtNs = 10L
        )
        val next = AudioDownloadManager.DownloadProgress(
            songKey = "unknown-total",
            songId = 1L,
            fileName = "unknown-total.mp3",
            bytesRead = 1_001L,
            totalBytes = 0L,
            speedBytesPerSec = 1L,
            attemptId = 7L
        )

        assertFalse(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = next,
                nowNs = previous.emittedAtNs + 1L
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = next,
                nowNs = previous.emittedAtNs + 180_000_000L
            )
        )
        assertFalse(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = next.copy(bytesRead = previous.bytesRead),
                nowNs = previous.emittedAtNs + 180_000_000L
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = next.copy(totalBytes = 2_000L),
                nowNs = previous.emittedAtNs + 180_000_000L
            )
        )
    }

    @Test
    fun `new attempt is not throttled by a previous attempt`() {
        val previous = AudioDownloadManager.PublishedProgressState(
            attemptId = 11L,
            bytesRead = 8L * 1024L * 1024L,
            totalBytes = 16L * 1024L * 1024L,
            percentage = 50,
            stage = AudioDownloadManager.DownloadStage.TRANSFERRING,
            emittedAtNs = 20L
        )
        val nextAttempt = AudioDownloadManager.DownloadProgress(
            songKey = "retry-song",
            songId = 2L,
            fileName = "retry-song.mp3",
            bytesRead = 0L,
            totalBytes = previous.totalBytes,
            speedBytesPerSec = 0L,
            attemptId = 12L
        )

        assertTrue(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = nextAttempt,
                nowNs = previous.emittedAtNs
            )
        )
    }

    @Test
    fun `finalizing progress is always published even when bytes are below the transfer high water mark`() {
        val previous = AudioDownloadManager.PublishedProgressState(
            attemptId = 15L,
            bytesRead = 900L,
            totalBytes = 1_000L,
            percentage = 90,
            stage = AudioDownloadManager.DownloadStage.TRANSFERRING,
            emittedAtNs = 30L
        )
        val finalizing = AudioDownloadManager.DownloadProgress(
            songKey = "finalizing-song",
            songId = 3L,
            fileName = "finalizing-song.mp3",
            bytesRead = 100L,
            totalBytes = 1_000L,
            speedBytesPerSec = 0L,
            stage = AudioDownloadManager.DownloadStage.FINALIZING,
            attemptId = 15L
        )

        assertTrue(
            AudioDownloadManager.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = finalizing,
                nowNs = previous.emittedAtNs
            )
        )
    }

    @Test
    fun `network recovery wakes only on an unconfirmed to confirmed edge`() {
        assertTrue(shouldTriggerNetworkRecovery(wasConfirmed = false, isConfirmed = true))
        assertFalse(shouldTriggerNetworkRecovery(wasConfirmed = true, isConfirmed = true))
        assertFalse(shouldTriggerNetworkRecovery(wasConfirmed = false, isConfirmed = false))
        assertFalse(shouldTriggerNetworkRecovery(wasConfirmed = true, isConfirmed = false))
    }

    @Test
    fun `wifi to mobile default transition requests protection only once`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
        tracker.markWifiLossHandled()

        assertFalse(tracker.onDefaultNetworkLost(networkKey = "wifi"))
        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `wifi loss before mobile replacement requests protection only once`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(tracker.onDefaultNetworkLost(networkKey = "wifi"))
        tracker.markWifiLossHandled()

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(tracker.onDefaultNetworkLost(networkKey = "wifi"))
    }

    @Test
    fun `network policy pause aborts only the Wi-Fi bound song work`() {
        assertTrue(
            shouldAbortDownloadWork(
                allDownloadsCancelled = false,
                batchSessionCurrent = true,
                songCancelled = false,
                networkPolicyPaused = true,
                attemptAllowsWork = true
            )
        )
        assertFalse(
            shouldAbortDownloadWork(
                allDownloadsCancelled = false,
                batchSessionCurrent = true,
                songCancelled = false,
                networkPolicyPaused = false,
                attemptAllowsWork = true
            )
        )
        assertTrue(
            shouldAbortDownloadWork(
                allDownloadsCancelled = false,
                batchSessionCurrent = true,
                songCancelled = false,
                networkPolicyPaused = false,
                attemptAllowsWork = true,
                operationAllowsWork = false
            )
        )
    }

    @Test
    fun `system cancellation preserves staging while explicit cancellation may clean it`() {
        assertTrue(
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = true,
                allDownloadsCancelled = false,
                songCancelled = false,
                networkPolicyPaused = false
            )
        )
        assertFalse(
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = true,
                allDownloadsCancelled = true,
                songCancelled = false,
                networkPolicyPaused = false
            )
        )
        assertFalse(
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = true,
                allDownloadsCancelled = false,
                songCancelled = true,
                networkPolicyPaused = false
            )
        )
        assertTrue(
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = false,
                allDownloadsCancelled = true,
                songCancelled = true,
                networkPolicyPaused = true
            )
        )
    }

    @Test
    fun `network policy pause keeps unrelated batch state and gates working file mutations`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val pauseSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerFacadePlayback.kt"
        ).readText()
        val executionSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerRuntime.kt"
        ).readText()
        val batchSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadBatchCoordinator.kt"
        ).readText()
        val hlsSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadHlsTransfer.kt"
        ).readText()
        val pauseBody = methodBody(pauseSource, "pauseDownloadsForNetworkPolicyImpl")
        val executionBody = methodBody(executionSource, "executeDownloadSong")

        assertFalse(pauseBody.contains("_isCancelled.value = true"))
        assertFalse(pauseBody.contains("invalidateBatchSession()"))
        assertFalse(pauseBody.contains("_batchProgressFlow.value = null"))
        assertTrue(pauseBody.contains("activeOperationIdsForSongLocked"))
        assertTrue(pauseBody.contains("operationRegistry.revokeReference"))
        assertTrue(pauseSource.contains("clearVisibleProgressForSong(songKey)"))
        assertTrue(executionBody.contains("stage = \"source_resolved\""))
        assertTrue(executionBody.contains("stage = \"prepare_working_file\""))
        assertTrue(hlsSource.contains("stage = \"hls_resume_reset\""))
        assertTrue(hlsSource.contains("stage = \"hls_open_working_file\""))
        assertFalse(hlsSource.contains("clearHlsResumeState(destFile)\n\n        NPLogger.d"))
        assertTrue(batchSource.contains("onSongPausedForNetworkPolicy"))
        assertTrue(batchSource.contains("queuedCompletion == null && !pausedForNetworkPolicy"))
    }

    @Test
    fun `direct and chunked streams sync the working file before completion`() {
        val transferSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadFileTransfer.kt"
        ).readText()
        assertTrue(transferSource.contains("suspend fun download("))
        assertTrue(transferSource.contains("private suspend fun downloadChunked("))
        assertTrue(transferSource.split("output.fd.sync()").size - 1 >= 4)
    }

    @Test
    fun `stale wifi callback after cellular replacement cannot change policy`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkObserved(
                networkKey = "cellular",
                networkType = TrafficNetworkType.MOBILE,
                activeNetworkKey = "cellular"
            )
        )
        tracker.markWifiLossHandled()

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "wifi",
                networkType = TrafficNetworkType.WIFI,
                activeNetworkKey = "cellular"
            )
        )
        assertEquals(1L, tracker.currentGeneration())
    }

    @Test
    fun `unknown active snapshot does not mutate network policy`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "cellular",
                networkType = TrafficNetworkType.MOBILE,
                activeNetworkKey = null,
                activeNetworkKnown = false
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `duplicate confirmed callback does not advance network generation`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "wifi",
                networkType = TrafficNetworkType.WIFI,
                activeNetworkKey = "wifi"
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `network observation emits Wi-Fi recovery only for a new confirmed snapshot`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "cellular", networkType = TrafficNetworkType.MOBILE)

        val first = tracker.observeDefaultNetwork(
            networkKey = "wifi",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi"
        )
        val duplicate = tracker.observeDefaultNetwork(
            networkKey = "wifi",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi"
        )

        assertTrue(first.changed)
        assertTrue(first.becameWifi)
        assertFalse(first.shouldPause)
        assertEquals(1L, first.generation)
        assertFalse(duplicate.changed)
        assertFalse(duplicate.becameWifi)
        assertEquals(first.generation, duplicate.generation)
    }

    @Test
    fun `unknown active snapshot after network loss does not pause Wi-Fi work`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi",
                activeNetworkKey = null,
                activeNetworkKnown = false
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `confirmed absence of an active network pauses Wi-Fi work conservatively`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi",
                activeNetworkKey = null,
                activeNetworkKnown = true
            )
        )
        assertEquals(1L, tracker.currentGeneration())
    }

    @Test
    fun `new Wi-Fi network after loss emits a recovery transition`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi-old", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi-old",
                activeNetworkKey = null,
                activeNetworkKnown = true
            )
        )
        val recovery = tracker.observeDefaultNetwork(
            networkKey = "wifi-new",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi-new"
        )

        assertTrue(recovery.changed)
        assertTrue(recovery.becameWifi)
    }

    @Test
    fun `managed local references require a strictly verified replacement`() {
        assertEquals(
            "/Music/local.mp3",
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "/Music/local.mp3",
                isManagedDownload = false,
                verifiedManagedReference = null
            )
        )
        assertEquals(
            "content://downloads/finalized.mp3",
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "content://downloads/unfinalized.mp3",
                isManagedDownload = true,
                verifiedManagedReference = "content://downloads/finalized.mp3"
            )
        )
        assertNull(
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "content://downloads/unfinalized.mp3",
                isManagedDownload = true,
                verifiedManagedReference = null
            )
        )
    }

    @Test
    fun `readable file and SAF references stay on local playback`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable("file:///Music/local.flac"),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "file:///Music/local.flac",
                isManagedDownload = false,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/current-root/Song.flac"
            ),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/old-root/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = "content://provider/current-root/Song.flac",
                rawEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }

    @Test
    fun `readable managed SAF reference bypasses an incomplete snapshot`() {
        val rawReference = "content://provider/old-root/Song.flac"
        val incompleteSnapshot = ManagedDownloadStorage
            .emptyDownloadLibrarySnapshot()
            .copy(rootEntriesComplete = false)
        assertFalse(incompleteSnapshot.rootEntriesComplete)

        assertEquals(
            LocalPlaybackReferenceResolution.Playable(rawReference),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = incompleteSnapshot.metadataByAudioName["Song.flac"]
            )
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present,
                managedReferenceIsExplicitlyIncomplete = false
        ) is LocalPlaybackReferenceResolution.Playable
        )
    }

    @Test
    fun `present formal managed audio bypasses stale completion metadata`() {
        val present = ManagedDownloadReferenceLookup.Result.Present

        assertTrue(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertTrue(
            shouldUseDirectPresentLocalPlayback(
                reference = "/storage/emulated/0/NeriPlayer/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac.npdl_pending",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/download_staging/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/npdl_song.flac.download",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = present,
                downloadCancelled = true
            )
        )
    }

    @Test
    fun `completed bridge allows pending reference without synchronous provider probe`() {
        assertTrue(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/Song.flac.npdl_pending"
            )
        )
        assertTrue(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "/storage/emulated/0/NeriPlayer/Song.flac"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/download_staging/Song.flac"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/npdl_song.flac.download"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/Song.flac",
                downloadCancelled = true
            )
        )
    }

    @Test
    fun `managed raw reference remains blocked for an active replacement`() {
        val rawReference = "content://provider/downloads/Song.flac"
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present,
                managedReferenceIsExplicitlyIncomplete = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    operationId = "op-1",
                    artifactState = "COMMITTING"
                )
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    artifactState = "CORE_COMMITTED"
                )
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = null
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    artifactState = "CORE_COMMITTED"
                )
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = true,
                    artifactState = "STAGING"
                )
            )
        )
    }

    @Test
    fun `core commit seeds durable playable metadata before enrichment`() {
        val seeded = coreCommittedSeedMetadataJson(
            """{"downloadFinalized":false,"artifactState":"COMMITTING","stableKey":"song"}"""
        )

        assertTrue(seeded != null)
        val json = JSONObject(requireNotNull(seeded))
        assertFalse(json.optBoolean("downloadFinalized", true))
        assertEquals("CORE_COMMITTED", json.optString("artifactState"))
        assertEquals("song", json.optString("stableKey"))
        assertNull(coreCommittedSeedMetadataJson("not-json"))
    }

    @Test
    fun `explicit unfinished metadata without artifact state remains blocked`() {
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false
                )
            )
        )
    }

    @Test
    fun `present legacy audio remains playable while repair metadata is pending`() {
        val repairMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            downloadFinalized = false,
            artifactState = "REPAIR_REQUIRED"
        )

        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = true
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = false
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = true
            )
        )
    }

    @Test
    fun `only typed missing permits a downloaded song to use remote fallback`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/downloads/Song.flac"
            ),
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Present,
                indexedReferenceIsExplicitlyIncomplete = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertEquals(
            LocalPlaybackReferenceResolution.Missing,
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.npdl_pending.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing,
                missingIsTransient = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Missing,
                missingIsTransient = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider busy")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider busy")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
    }

    @Test
    fun `stale downloaded URI uses rebound current root reference`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/current-root/Rebound.flac"
            ),
            selectIndexedLocalPlaybackResolution(
                verifiedReference = "content://provider/current-root/Rebound.flac",
                indexedReference = "content://provider/old-root/Rebound.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }

    @Test
    fun `stale SAF document URI rebinds by file name only after finalization`() {
        val reboundAudio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Rebound.flac",
            reference = "content://provider/current-root/Artist%20-%20Rebound.flac",
            mediaUri = "content://provider/current-root/Artist%20-%20Rebound.flac",
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 2L
        )
        val finalizedSnapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(reboundAudio),
            metadataByAudioName = mapOf(
                reboundAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
                )
            )
        )
        val staleReference =
            "content://provider/tree/old/document/old%2FArtist%20-%20Rebound.flac"

        assertEquals(
            reboundAudio,
            findReboundFinalizedManagedAudio(finalizedSnapshot, staleReference)
        )
        assertNull(
            findReboundFinalizedManagedAudio(
                finalizedSnapshot.copy(
                    metadataByAudioName = mapOf(
                        reboundAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                            downloadFinalized = false
                        )
                    )
                ),
                staleReference
            )
        )
    }

    @Test
    fun `only strictly finalized managed audio is exposed for local playback`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "content://downloads/Artist%20-%20Song.mp3",
            mediaUri = "content://downloads/Artist%20-%20Song.mp3",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        fun snapshot(
            finalized: Boolean,
            embeddingState: DownloadedAudioEmbeddingState?
        ) = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(
                audio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = finalized,
                    metadataEmbeddingState = embeddingState
                )
            )
        )

        assertTrue(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED),
                audio
            )
        )
        assertTrue(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.USER_DISABLED),
                audio
            )
        )
        assertFalse(canExposeManagedDownloadForPlayback(snapshot(true, null), audio))
        assertFalse(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER),
                audio
            )
        )
        assertFalse(canExposeManagedDownloadForPlayback(snapshot(false, null), audio))
    }

    @Test
    fun `cover response reader rejects an oversized declared length`() {
        assertThrows(IOException::class.java) {
            AudioDownloadManager.readCoverResponseBytes(
                input = ByteArrayInputStream(byteArrayOf(1)),
                declaredLength = AudioDownloadManager.MAX_COVER_RESPONSE_BYTES + 1L
            )
        }
    }

    @Test
    fun `cover response reader rejects an oversized chunked body`() {
        assertThrows(IOException::class.java) {
            AudioDownloadManager.readCoverResponseBytes(
                input = ByteArrayInputStream(
                    ByteArray(AudioDownloadManager.MAX_COVER_RESPONSE_BYTES.toInt() + 1)
                ),
                declaredLength = -1L
            )
        }
    }

    @Test
    fun `download song keeps transfer and core commit in named suspend stages`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val facadeSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerFacadeControl.kt"
        ).readText()
        val attemptSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerAttempt.kt"
        ).readText()
        val downloadSongBody = methodBody(managerSource, "downloadSong", preferLast = true)
        val facadeBody = methodBody(facadeSource, "downloadSongImpl")
        val transferBody = methodBody(attemptSource, "transferAndCommitDownloadAttempt")

        assertTrue(downloadSongBody.contains("downloadSongImpl("))
        assertTrue(facadeBody.contains("downloadSongOnIo("))
        assertTrue(transferBody.contains("downloadPayloadForTransport("))
        assertTrue(transferBody.contains("finalizeDownloadedAudio("))
        assertFalse(transferBody.contains("ManagedDownloadStorage.saveAudioFromTemp("))
        assertTrue(attemptSource.contains("internal suspend fun AudioDownloadManager.downloadPayloadForTransport("))
        assertTrue(attemptSource.contains("internal suspend fun AudioDownloadManager.finalizeDownloadedAudio("))
    }
}
