package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
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


class AudioDownloadManagerGroup2Test : AudioDownloadManagerTestSupport() {

    @Test
    fun `transfer cycle releases permit only after core commit and wakes the pump`() {
        val attemptSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerAttempt.kt"
        ).readText()
        val assetsSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerAssets.kt"
        ).readText()
        val transferBody = methodBody(attemptSource, "transferAndCommitDownloadAttempt")
        val transferIndex = transferBody.indexOf("transferWatchdog.run(permit)")
        val networkFinishedIndex = transferBody.indexOf("markNetworkFinished()", transferIndex)
        val coreRequestedIndex = transferBody.indexOf(
            "DownloadOperationTracePhase.CORE_COMMIT_REQUESTED",
            networkFinishedIndex
        )
        val coreCommittedIndex = transferBody.indexOf(
            "DownloadOperationTracePhase.CORE_COMMITTED",
            coreRequestedIndex
        )
        val wakeIndex = transferBody.indexOf(
            "GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommit(",
            coreCommittedIndex
        )
        val transferPermitReturnIndex = transferBody.indexOf(
            "// 只有 operation journal 的 CAS 成功后才释放宿主传输槽位",
            coreCommittedIndex
        )
        val cycleBody = methodBody(assetsSource, "withTransferCyclePermit")
        val blockIndex = cycleBody.indexOf(
            "return block(permit, ::markNetworkFinished, transferOwnerToken)"
        )
        val admissionRejectedIndex = cycleBody.indexOf(
            "operationId != null && transferOwnerToken == null"
        )
        val networkStartedIndex = cycleBody.indexOf("permit.markNetworkIoStarted()")
        val releaseIndex = cycleBody.indexOf("permit.release()")

        assertTrue(transferIndex >= 0)
        assertTrue(networkFinishedIndex > transferIndex)
        assertTrue(coreRequestedIndex > networkFinishedIndex)
        assertTrue(coreCommittedIndex > coreRequestedIndex)
        assertTrue(wakeIndex > coreCommittedIndex)
        assertTrue(wakeIndex < transferPermitReturnIndex)
        assertTrue(transferBody.contains("transferOwnerToken = committedAudio.transferOwnerToken"))
        assertTrue(cycleBody.contains("DownloadExecutionHosts.onTransferStarted("))
        assertTrue(cycleBody.contains("transferPermitOwnerKey = permit.ownerKey"))
        assertTrue(admissionRejectedIndex >= 0)
        assertTrue(networkStartedIndex > admissionRejectedIndex)
        assertTrue(blockIndex >= 0)
        assertTrue(releaseIndex > blockIndex)
        assertFalse(attemptSource.contains("withConfiguredDownloadPermit"))
    }

    @Test
    fun `late cancellation cleanup is scoped to the owning operation`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val runtimeSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerRuntime.kt"
        ).readText()
        val assetsSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerAssets.kt"
        ).readText()
        val attemptSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerAttempt.kt"
        ).readText()
        val playbackFacadeSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerFacadePlayback.kt"
        ).readText()
        val allManagerSources = managerSource + runtimeSource + assetsSource + attemptSource + playbackFacadeSource
        val trackedCallBody = methodBody(runtimeSource, "executeTrackedCall")
        val cancellationGuardBody = methodBody(runtimeSource, "ensureSongDownloadNotCancelled")
        val transferSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadFileTransfer.kt"
        ).readText()
        val coverSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadCoverCoordinator.kt"
        ).readText()

        assertTrue(
            trackedCallBody.contains(
                "expectedOperationId = normalizedOperationId"
            )
        )
        assertTrue(
            cancellationGuardBody.contains(
                "expectedAttemptId = attemptId"
            )
        )
        assertTrue(
            cancellationGuardBody.contains(
                "expectedOperationId = operationId"
            )
        )
        assertTrue(
            transferSource.contains(
                "operationId: String?"
            )
        )
        assertTrue(allManagerSources.contains("internal val referenceOwnership"))
        assertTrue(allManagerSources.contains("claimReferenceOwnershipForEnrichment"))
        assertTrue(allManagerSources.contains("operationAllowsWork = operationAllowsWork"))
        assertTrue(allManagerSources.contains("GlobalDownloadManager.withSongExecutionLock(songKey)"))
        assertTrue(allManagerSources.contains("operationId = effectiveOperationId"))
        assertTrue(allManagerSources.contains("operationRegistry.revokeAllReferences()"))
        assertTrue(
            allManagerSources.contains(
                "clearCompletedAudioReference(songKey, operationId = effectiveOperationId)"
            )
        )
        assertTrue(coverSource.contains("operationId: String?"))
        assertTrue(coverSource.contains("requireActiveAttempt: Boolean,"))
        assertTrue(coverSource.contains("operationId"))
        assertTrue(allManagerSources.contains("active, operationId ->"))
    }

    @Test
    fun `batch progress publication leaves aggregation lock before invoking hook`() {
        val batchSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadBatchCoordinator.kt"
        ).readText()
        val publishBody = batchSource
            .substringAfter("suspend fun publishBatchProgress()")
            .substringBefore("suspend fun markSongStarted")
        val snapshotIndex = publishBody.indexOf("val snapshot = progressMutex.withLock")
        val publishCommentIndex = publishBody.indexOf("外部 Flow 发布不占用聚合锁")
        val publishMutexIndex = publishBody.indexOf("progressPublishMutex.withLock")
        val updateIndex = publishBody.indexOf("hooks.updateBatchProgressForSession")

        assertTrue(snapshotIndex >= 0)
        assertTrue(publishCommentIndex > snapshotIndex)
        assertTrue(publishMutexIndex > publishCommentIndex)
        assertTrue(updateIndex > publishMutexIndex)
        assertTrue(batchSource.contains("nextProgressVersion"))
        assertTrue(batchSource.contains("publishedProgressVersion"))
        assertTrue(batchSource.contains("kotlinx.coroutines.NonCancellable"))
        assertTrue(batchSource.contains("progressJob.cancelAndJoin()"))
    }

    @Test
    fun `completed bridge is retained until a real transport starts`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerRuntime.kt"
        ).readText()
        val executionBody = methodBody(source, "executeDownloadSong")
        val cachedLookupIndex = executionBody.indexOf(
            "hasFastCachedManagedDownloadForStart(context, song)"
        )
        val bridgeClearIndex = executionBody.indexOf(
            "clearCompletedAudioReference(songKey)"
        )
        val transportIndex = executionBody.indexOf("downloadPayloadForTransport(")

        assertTrue(cachedLookupIndex >= 0)
        assertTrue(bridgeClearIndex > cachedLookupIndex)
        assertTrue(transportIndex > bridgeClearIndex)
    }

    @Test
    fun `fresh transfer bypasses the audio manager fast cache`() {
        val facadeSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val executionSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManagerRuntime.kt"
        ).readText()
        val executionBody = methodBody(executionSource, "executeDownloadSong")

        assertTrue(facadeSource.contains("forceFreshTransfer: Boolean = false"))
        assertTrue(
            executionBody.contains(
                "if (!forceFreshTransfer && hasFastCachedManagedDownloadForStart(context, song))"
            )
        )
        assertTrue(facadeSource.contains("forceFreshTransfer = forceFreshTransfer"))
    }

    @Test
    fun `recent completed bridge is checked before SAF inspection`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadPlaybackCoordinator.kt"
        ).readText()
        val playbackBody = methodBody(source, "resolvePermittedLocalPlayback")
        val bridgeIndex = playbackBody.indexOf(
            "resolveRecentlyCommittedAudioReference("
        )
        val providerIndex = playbackBody.indexOf(
            "ManagedDownloadReferenceLookup.inspect"
        )

        assertTrue(bridgeIndex >= 0)
        assertTrue(providerIndex > bridgeIndex)
    }

    @Test
    fun `cancelYouTubeCalls cancels only trusted YouTube hosts`() {
        val youtubeCall = FakeCall("https://rr1---sn.example.googlevideo.com/audio")
        val innertubeCall = FakeCall("https://youtubei.googleapis.com/youtubei/v1/player")
        val unrelatedCall = FakeCall("https://example.com/audio")

        val canceled = AudioDownloadManager.cancelYouTubeCalls(
            listOf(youtubeCall, innertubeCall, unrelatedCall)
        )

        assertEquals(2, canceled)
        assertTrue(youtubeCall.isCanceled())
        assertTrue(innertubeCall.isCanceled())
        assertFalse(unrelatedCall.isCanceled())
    }

    @Test
    fun `batch download parallelism keeps default six and caps at eight workers`() {
        assertEquals(6, AudioDownloadManager.DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(8, AudioDownloadManager.MAX_CONCURRENT_DOWNLOADS_LIMIT)
        assertEquals(1, AudioDownloadManager.clampBatchDownloadParallelism(0))
        assertEquals(4, AudioDownloadManager.clampBatchDownloadParallelism(4))
        assertEquals(8, AudioDownloadManager.clampBatchDownloadParallelism(9))
        assertEquals(0, AudioDownloadManager.resolveBatchDownloadWorkerCount(0, 6))
        assertEquals(2, AudioDownloadManager.resolveBatchDownloadWorkerCount(2, 6))
        assertEquals(8, AudioDownloadManager.resolveBatchDownloadWorkerCount(20, 9))
    }

    @Test
    fun `parallelism cache uses a conservative fallback until the persisted setting is readable`() {
        assertEquals(DEFAULT_DOWNLOAD_PARALLELISM, INITIAL_DOWNLOAD_PARALLELISM)
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/DownloadParallelism.kt"
        ).readText()

        assertFalse(source.contains("runBlocking"))
        assertFalse(source.contains("resolveDownloadParallelismBlocking"))
        assertTrue(source.contains("AtomicInteger(INITIAL_DOWNLOAD_PARALLELISM)"))
        assertTrue(source.contains("readBootstrapDownloadParallelism("))
        assertTrue(source.contains("warmBootstrapSettingsSnapshot("))
        assertTrue(source.contains("scope.launch"))
    }

    @Test
    fun `shouldFetchRemoteLyricForDownload only fetches when local override is absent`() {
        assertEquals(true, AudioDownloadManager.shouldFetchRemoteLyricForDownload(null))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload(""))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload("   "))
        assertEquals(
            false,
            AudioDownloadManager.shouldFetchRemoteLyricForDownload("[00:00.00]local lyric")
        )
    }

    @Test
    fun `romanized lyric download only piggybacks on an existing lyric request`() {
        assertFalse(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = true,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = true
            )
        )
    }

    @Test
    fun `sidecar merge preserves expected lyric artifacts across retries`() {
        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = AudioDownloadManager.DownloadedSidecarReferences(
                expectedLyric = true
            ),
            incoming = AudioDownloadManager.DownloadedSidecarReferences(
                expectedTranslatedLyric = true,
                expectedRomanizedLyric = true
            )
        )

        assertTrue(merged.expectedLyric)
        assertTrue(merged.expectedTranslatedLyric)
        assertTrue(merged.expectedRomanizedLyric)
        assertFalse(merged.isEmpty)
    }

    @Test
    fun `resolveLocalLyricForDownload keeps explicit lyrics and preserves cleared state separately`() {
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(null))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(""))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload("   "))
        assertEquals(
            "[00:00.00]translated",
            AudioDownloadManager.resolveLocalLyricForDownload("[00:00.00]translated")
        )
    }

    @Test
    fun `transient download retry delay grows and stays capped`() {
        assertEquals(1_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(1))
        assertEquals(2_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(2))
        assertEquals(4_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(3))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(4))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(9))
    }

    @Test
    fun `confirmed missing source stops after two resolutions but offline remains retryable`() {
        assertFalse(
            AudioDownloadManager.shouldStopRetryingMissingDownloadSource(
                confirmedMissCount = 1,
                hasConfirmedInternetAccess = true
            )
        )
        assertTrue(
            AudioDownloadManager.shouldStopRetryingMissingDownloadSource(
                confirmedMissCount = 2,
                hasConfirmedInternetAccess = true
            )
        )
        assertFalse(
            AudioDownloadManager.shouldStopRetryingMissingDownloadSource(
                confirmedMissCount = 9,
                hasConfirmedInternetAccess = false
            )
        )
    }

    @Test
    fun `netease download lookup keeps explicit no permission separate from transient misses`() {
        val unavailable = AudioDownloadSourceResolver.parseNeteaseDownloadLookup(
            """
                {
                  "code": 200,
                  "data": [{
                    "url": null,
                    "code": 404,
                    "fee": 1,
                    "freeTrialPrivilege": { "cannotListenReason": 1 }
                  }]
                }
            """.trimIndent()
        )
        val missing = AudioDownloadSourceResolver.parseNeteaseDownloadLookup(
            """{"code": 503, "data": []}"""
        )

        assertEquals(AudioDownloadSourceResolver.NeteaseDownloadLookup.ExplicitlyUnavailable, unavailable)
        assertEquals(AudioDownloadSourceResolver.NeteaseDownloadLookup.Missing, missing)
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                DownloadSourceUnavailableException("no permission")
            )
        )
    }

    @Test
    fun `netease download lookup preserves resolved size type and secure url`() {
        val result = AudioDownloadSourceResolver.parseNeteaseDownloadLookup(
            """
                {
                  "code": 200,
                  "data": [{
                    "url": "http://m801.music.126.net/demo.flac",
                    "type": "FLAC",
                    "size": 3758751
                  }]
                }
            """.trimIndent()
        ) as AudioDownloadSourceResolver.NeteaseDownloadLookup.Resolved

        assertEquals("https://m801.music.126.net/demo.flac", result.source.url)
        assertEquals("flac", result.source.fileExtensionHint)
        assertEquals(3_758_751L, result.source.contentLength)
    }

    @Test
    fun `transient download failure detection only retries unstable network failures`() {
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                UnknownHostException("Unable to resolve host")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                SocketException("Software caused connection abort")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 503")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `youtube download failures refresh source for signed url status codes`() {
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 416")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = true
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `forbidden download failure is detected for 403 only`() {
        // 403(含 ChunkRequestIOException)才触发"改走 HLS", 其余可刷新码不触发
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 403")))
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(403, "forbidden")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(429, "rate")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 416")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IOException("磁盘写入失败")))
    }

    @Test
    fun `youtube download resolve plan starts with short shared probe then isolates refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = false)

        assertEquals(4, attempts.size)
        assertEquals("shared_direct", attempts[0].logLabel)
        assertEquals(false, attempts[0].forceRefresh)
        assertEquals(true, attempts[0].requireDirect)
        assertEquals(true, attempts[0].shareInFlight)
        assertEquals("fresh_direct", attempts[1].logLabel)
        assertEquals(true, attempts[1].forceRefresh)
        assertEquals(true, attempts[1].requireDirect)
        assertEquals(false, attempts[1].shareInFlight)
        assertEquals("shared_playable", attempts[2].logLabel)
        assertEquals(false, attempts[2].forceRefresh)
        assertEquals(false, attempts[2].requireDirect)
        assertEquals(true, attempts[2].shareInFlight)
        assertEquals("fresh_playable", attempts[3].logLabel)
        assertEquals(true, attempts[3].forceRefresh)
        assertEquals(false, attempts[3].requireDirect)
        assertEquals(false, attempts[3].shareInFlight)
        assertTrue(attempts[0].timeoutMs < attempts[1].timeoutMs)
        assertTrue(attempts[2].timeoutMs < attempts[3].timeoutMs)
    }

    @Test
    fun `youtube download resolve plan skips shared probes after forced refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = true)

        assertEquals(2, attempts.size)
        assertEquals(listOf("fresh_direct", "fresh_playable"), attempts.map { it.logLabel })
        assertTrue(attempts.all { it.forceRefresh })
        assertTrue(attempts.none { it.shareInFlight })
    }

    @Test
    fun `cover download candidates keep stable fallback order and de duplicate urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = "https://example.com/custom.jpg",
            originalCoverUrl = "https://example.com/original.jpg",
            mediaUri = "https://example.com/audio.m4a"
        )

        assertEquals(
            listOf(
                "https://example.com/custom.jpg",
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover download candidates exclude device local references`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "file:/data/user/0/moe.ouom.neriplayer/files/local_audio_covers/cover.jpg",
            customCoverUrl = "content://media/external/images/media/1",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf("https://example.com/original.jpg"),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover download candidates trim before de duplicating urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = " https://example.com/cover.jpg ",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf(
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover sidecar file names include stable song identity`() {
        val firstCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "1|netease|"
        )
        val sameNameSecondCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "2|netease|"
        )
        val numberedDuplicateCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song (1)",
            songKey = "2|netease|"
        )

        assertNotEquals(firstCoverName, sameNameSecondCoverName)
        assertNotEquals(firstCoverName, numberedDuplicateCoverName)
    }

    @Test
    fun `cover sidecar identity uses a collision resistant digest`() {
        val first = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "FB"
        )
        val second = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "Ea"
        )

        assertNotEquals(first, second)
        assertTrue(Regex("-[0-9a-f]{8}\\.jpg$").containsMatchIn(first))
    }

    @Test
    fun `transfer size completeness rejects short payloads while allowing bounded provider drift`() {
        assertTrue(AudioDownloadManager.isTransferSizeComplete(null, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(0L, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 256L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 257L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 999_000L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_000L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(null, 0L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 258L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 998_999L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_001L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 512L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 128L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(3_758_751L, 5_129_657L))
    }

    @Test
    fun `commit size validation drops transfer expectation after metadata changes file`() {
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 5_129_657L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 3_758_751L,
                bytesAtCommit = 3_758_751L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = null,
                bytesBeforeMetadata = 1_024L,
                bytesAtCommit = 1_024L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
    }

    @Test
    fun `resume range header starts from completed bytes`() {
        assertEquals(null, AudioDownloadManager.buildResumeRangeHeader(0L))
        assertEquals("bytes=1024-", AudioDownloadManager.buildResumeRangeHeader(1_024L))
    }

    @Test
    fun `resume request includes if range validator when fingerprint is available`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "\"abc123\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertEquals("\"abc123\"", resumedRequest.header("If-Range"))
    }

    @Test
    fun `resume request does not use last modified without strong etag`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = null,
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertNull(resumedRequest.header("If-Range"))
        assertEquals("identity", resumedRequest.header("Accept-Encoding"))
    }

    @Test
    fun `resume request rejects weak etag for if range`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val weakWithDate = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "W/\"weak\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
        )
        val weakOnly = weakWithDate.copy(lastModified = null)

        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakWithDate)
                .header("If-Range")
        )
        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakOnly)
                .header("If-Range")
        )
    }

    @Test
    fun `resume request removes an unsafe inherited if range header`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .header("If-Range", "W/\"weak\"")
            .build()

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = null
        )

        assertNull(resumedRequest.header("If-Range"))
    }

    @Test
    fun `chunk resume request removes unsafe inherited if range header`() {
        val request = Request.Builder()
            .url("https://rr1---sn-abcd.googlevideo.com/videoplayback?source=youtube")
            .header("If-Range", "Wed, 15 Jul 2026 12:00:00 GMT")
            .build()

        val chunkRequest = AudioDownloadManager.buildChunkResumeRequest(
            request = request,
            start = 1_024L,
            length = 4_096L,
            fingerprint = null
        )

        assertEquals("bytes=1024-5119", chunkRequest.header("Range"))
        assertEquals("identity", chunkRequest.header("Accept-Encoding"))
        assertNull(chunkRequest.header("If-Range"))
    }

    @Test
    fun `chunk resume prefers the latest persisted fingerprint`() {
        val fallback = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"old\""
        )
        val latest = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"new\""
        )

        assertEquals(
            latest,
            AudioDownloadManager.resolveLatestResumeFingerprint(
                fallback = fallback,
                latest = latest
            )
        )
        assertEquals(
            fallback,
            AudioDownloadManager.resolveLatestResumeFingerprint(
                fallback = fallback,
                latest = null
            )
        )
    }

    @Test
    fun `resume fingerprint exposes only a strong etag as validator`() {
        assertEquals(
            "\"strong\"",
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = "\"strong\"",
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
        assertNull(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = "W/\"weak\"",
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
        assertNull(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = null,
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
    }

    @Test
    fun `signed url rotation does not discard a resumable file`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = "https://example.com/audio.m4a?token=old",
            etag = "\"same-validator\"",
            lastModified = null,
            expectedContentLength = 4_096L
        )

        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertTrue(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/other.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=old",
                fingerprint = fingerprint
            )
        )
        assertTrue(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?quality=low",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a",
                fingerprint = null
            )
        )
    }

    @Test
    fun `response expected bytes keeps full size when resuming partial payload`() {
        val headers = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))

        assertEquals(
            4_096L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://example.com/audio.m4a",
                headers = headers,
                bodyLength = 3_072L,
                resumedBytes = 1_024L,
                isPartialResponse = true
            )
        )
    }

    @Test
    fun `content range validation requires exact start total and body length`() {
        val validHeaders = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))
        assertEquals(
            4_096L,
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_072L
            ).total
        )
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_025L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 1024-4095/*")),
                expectedStart = 1_024L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_071L
            )
        }
        assertEquals(
            4_096L,
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 0-4095/4096")),
                expectedStart = 0L,
                bodyLength = 4_096L
            ).total
        )
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 1-4095/4096")),
                expectedStart = 0L,
                bodyLength = 4_095L
            )
        }
    }

    @Test
    fun `resume response requires the same strong etag and total`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"stable\"",
            expectedContentLength = 4_096L
        )
        val headers = mapOf("ETag" to listOf("\"stable\""))

        assertTrue(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("\"rotated\"")),
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_097L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("W/\"stable\"")),
                4_096L
            )
        )
    }

    @Test
    fun `range not satisfiable is accepted only at exact total`() {
        val headers = mapOf("Content-Range" to listOf("bytes */4096"))
        assertTrue(AudioDownloadManager.isExactRangeEnd(headers, 4_096L))
        assertFalse(AudioDownloadManager.isExactRangeEnd(headers, 4_095L))
        assertTrue(
            AudioDownloadManager.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */0")),
                0L
            )
        )
        assertFalse(
            AudioDownloadManager.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */4096")),
                4_097L
            )
        )
    }

    @Test
    fun `response content length wins over stale non youtube query length`() {
        val headers = mapOf("Content-Length" to listOf("5129657"))

        assertEquals(
            5_129_657L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = headers,
                bodyLength = 5_129_657L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `non youtube query length is ignored when response length is unavailable`() {
        assertNull(
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `google video query length remains a fallback when response length is unavailable`() {
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://rr1---sn-abcd.googlevideo.com/videoplayback?clen=3758751&source=youtube",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `download transport kind falls back to chunked range only for googlevideo without explicit range`() {
        val chunkedRequest = Request.Builder()
            .url("https://rr1---sn-abcd.googlevideo.com/videoplayback?source=youtube")
            .build()
        val directRequest = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val explicitRangeRequest = chunkedRequest.newBuilder()
            .header("Range", "bytes=0-4095")
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                chunkedRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                directRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                explicitRangeRequest
            )
        )
    }

    @Test
    fun `download transport chunks seekable web remix direct url instead of full range`() {
        // 回归: 已解析(n+sig+clen)的 WEB_REMIX 直链此前被判为 DIRECT(整档下载)导致 403
        // 现应统一走 CHUNKED_RANGE, 避免整档 GET 触发 googlevideo 全量下载风控
        val seekableWebRemixRequest = Request.Builder()
            .url(
                "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                    "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fmp4&clen=3611036"
            )
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                seekableWebRemixRequest
            )
        )
    }

    @Test
    fun `partial download preservation requires bytes and hls checkpoint when needed`() {
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.DIRECT,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = true
            )
        )
    }

    @Test
    fun `hls resume state serialization round trips`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 12,
            downloadedBytes = 34_567L,
            durablePrefixSha256 = "b".repeat(64),
            operationId = "operation-1",
            mediaSequence = 42L
        )

        val restored = AudioDownloadManager.deserializeHlsResumeState(
            AudioDownloadManager.serializeHlsResumeState(state)
        )

        assertEquals(state, restored)
        assertTrue(
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("playlistDigestSha256")
        )
        assertTrue(
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("operationId")
        )
        assertEquals(null, AudioDownloadManager.deserializeHlsResumeState("{"))
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"playlistFingerprint":1,"nextSegmentIndex":2,"downloadedBytes":3}"""
            )
        )
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"playlistDigestSha256":"${"a".repeat(64)}","nextSegmentIndex":-1,"downloadedBytes":3}"""
            )
        )
    }
}
