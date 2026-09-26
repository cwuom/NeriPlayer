package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadTask
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.applyCancelledStatus
import moe.ouom.neriplayer.core.download.model.applyWaitingNetworkStatus
import moe.ouom.neriplayer.core.download.model.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.model.hasActiveDownloadOperations
import moe.ouom.neriplayer.core.download.model.hasActiveDownloadTasks
import moe.ouom.neriplayer.core.download.model.isActiveDownloadAttempt
import moe.ouom.neriplayer.core.download.model.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.download.model.isDownloadTaskFinalizing
import moe.ouom.neriplayer.core.download.model.shouldApplyTaskMutation
import moe.ouom.neriplayer.core.download.model.shouldHideRemoteDownloadAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.core.download.policy.recoveryOperationIdsForKeys
import moe.ouom.neriplayer.core.download.policy.shouldRecoverDownloadCandidateWithBatch
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem


class GlobalDownloadManagerStartupPolicyGroup3Test : GlobalDownloadManagerStartupPolicyTestSupport() {

    @Test
    fun `catalog upsert replaces a legacy entry when the local file reference is unchanged`() {
        val legacy = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L
        )
        val refreshed = legacy.copy(
            sourceChannelId = "netease",
            sourceAudioId = "42",
            downloadTime = 20L
        )

        assertTrue(matchesDownloadedSongCatalogEntry(legacy, refreshed))
        assertEquals(listOf(refreshed), upsertDownloadedSongCatalog(listOf(legacy), refreshed))
    }

    @Test
    fun `matchesDownloadedSongCatalogEntry keeps legacy media uri entries aligned`() {
        val legacy = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )
        val refreshed = legacy.copy(
            filePath = "/storage/emulated/0/Android/data/moe.ouom.neriplayer/files/song.flac",
            mediaUri = "content://downloads/song",
            downloadTime = 20L
        )

        assertTrue(matchesDownloadedSongCatalogEntry(legacy, refreshed))
    }

    @Test
    fun `upsertDownloadedSongCatalog replaces legacy media uri entries when file path was blank`() {
        val legacy = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )
        val refreshed = legacy.copy(
            filePath = "/storage/emulated/0/Android/data/moe.ouom.neriplayer/files/song.flac",
            fileSize = 20L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song"
        )

        assertEquals(listOf(refreshed), upsertDownloadedSongCatalog(listOf(legacy), refreshed))
    }

    @Test
    fun `resolveDownloadedSongPlaybackReference falls back to media uri when file path is blank`() {
        val downloaded = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )

        assertEquals(
            "content://downloads/song",
            resolveDownloadedSongPlaybackReference(downloaded)
        )
        assertNull(
            resolveDownloadedSongPlaybackReference(
                downloaded.copy(mediaUri = "https://example.com/remote")
            )
        )
    }

    @Test
    fun `fast downloaded catalog hit trusts lightweight cache without accessibility probe`() {
        assertTrue(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = null
            )
        )
        assertTrue(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = setOf("content://downloads/song")
            )
        )
        assertFalse(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = setOf("content://downloads/other")
            )
        )
        assertFalse(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "",
                cachedKnownReferences = null
            )
        )
    }

    @Test
    fun `completed download post processing skips access probe for trusted reference`() {
        assertFalse(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "content://downloads/song",
                fastPathTrusted = true
            )
        )
        assertTrue(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "content://downloads/song",
                fastPathTrusted = false
            )
        )
        assertFalse(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "",
                fastPathTrusted = false
            )
        )
    }

    @Test
    fun `SAF sidecar lookup avoids indexed scan during fast background finalization`() {
        assertFalse(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = true,
                allowSlowLookup = true
            )
        )
        assertTrue(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = false,
                allowSlowLookup = true
            )
        )
        assertFalse(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = false,
                allowSlowLookup = false
            )
        )
    }

    @Test
    fun `cancelled artifact recovery yields to active retry`() {
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = true,
                taskStatus = null
            )
        )
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.QUEUED
            )
        )
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.DOWNLOADING
            )
        )
        assertFalse(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.CANCELLED
            )
        )
        assertFalse(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = null
            )
        )
    }

    @Test
    fun `detailed inspection stays disabled when slow local inspection is turned off`() {
        assertEquals(
            false,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = false,
                metadata = null,
                coverReference = null,
                needsLocalLyricFallback = true
            )
        )
    }

    @Test
    fun `detailed inspection is skipped when cached metadata is already complete`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            name = "Song",
            artist = "Artist",
            originalName = "Song",
            originalArtist = "Artist",
            durationMs = 3000L
        )

        assertEquals(
            false,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = true,
                metadata = metadata,
                coverReference = "content://covers/song.jpg",
                needsLocalLyricFallback = false
            )
        )
    }

    @Test
    fun `detailed inspection stays enabled when local lyric fallback is the only source left`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            name = "Song",
            artist = "Artist",
            originalName = "Song",
            originalArtist = "Artist",
            durationMs = 3000L
        )

        assertEquals(
            true,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = true,
                metadata = metadata,
                coverReference = "content://covers/song.jpg",
                needsLocalLyricFallback = true
            )
        )
    }

    @Test
    fun `hidden downloaded metadata refresh does not republish the whole catalog`() {
        val currentSong = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            matchedLyric = null
        )
        val updatedSong = currentSong.copy(
            matchedLyric = "[00:00.00]lyric",
            durationMs = 3000L,
            mediaUri = "content://downloads/song.flac"
        )

        assertFalse(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = currentSong,
                updatedSong = updatedSong
            )
        )
    }

    @Test
    fun `visible downloaded metadata refresh still republishes the catalog`() {
        val currentSong = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            coverPath = null
        )
        val updatedSong = currentSong.copy(coverPath = "content://covers/song.jpg")

        assertTrue(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = currentSong,
                updatedSong = updatedSong
            )
        )
    }

    @Test
    fun `downloaded song matches active local playback by local media reference`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null,
            mediaUri = "content://downloads/song.flac"
        )
        val downloadedSong = DownloadedSong(
            id = 7L,
            name = "Other",
            artist = "Other",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song.flac"
        )

        assertTrue(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song matches remote playback by stable track identity fallback`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "netease",
            albumId = 99L,
            durationMs = 3000L,
            coverUrl = null,
            mediaUri = "https://example.com/stream"
        )
        val downloadedSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song.flac"
        )

        assertTrue(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song stable key prevents same name track collisions`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Bilibili|2002",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null
        )
        val downloadedSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            stableKey = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Bilibili|1001",
                albumId = 0L,
                durationMs = 3000L,
                coverUrl = null
            ).stableKey()
        )

        assertFalse(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song catalog preserves stable key`() {
        val song = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.mp3",
            fileSize = 2048L,
            downloadTime = 123456L,
            stableKey = "42|Album|content://song",
            mediaUri = "content://downloads/song.mp3",
            durationMs = 3000L
        )

        val payload = serializeDownloadedSongsCatalog(
            cacheKey = "tree:test",
            songs = listOf(song)
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = payload,
            expectedCacheKey = "tree:test"
        )

        assertEquals(listOf(song), restored)
    }

    @Test
    fun `completed download finalization rolls back when cancel arrives after audio commit`() {
        assertEquals(
            CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED,
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = true,
                cancelled = true
            )
        )
    }

    @Test
    fun `completed download finalization detects missing audio when not cancelled`() {
        assertEquals(
            CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO,
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `pre existing downloaded audio settles directly instead of finalizing missing completed reference`() {
        assertEquals(
            PreExistingDownloadedAudioAction.DIRECT_SETTLE,
            resolvePreExistingDownloadedAudioAction(hasExistingAudio = true)
        )
        assertEquals(
            PreExistingDownloadedAudioAction.CONTINUE_DOWNLOAD,
            resolvePreExistingDownloadedAudioAction(hasExistingAudio = false)
        )
    }

    @Test
    fun `task mutation ignores stale attempt id but accepts current attempt`() {
        val task = DownloadTask(
            song = SongItem(
                id = 11L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/audio"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 99L
        )

        assertFalse(shouldApplyTaskMutation(task, expectedAttemptId = 98L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = 99L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = null))
    }

    @Test
    fun `active download attempt only matches current unfinished attempt`() {
        val song = SongItem(
            id = 12L,
            name = "Retry",
            artist = "Artist",
            album = "Album",
            albumId = 12L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/retry"
        )
        val queuedTask = DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 201L
        )
        val completedTask = queuedTask.copy(
            status = DownloadStatus.COMPLETED,
            attemptId = 202L
        )

        assertTrue(isActiveDownloadAttempt(listOf(queuedTask), song.stableKey(), expectedAttemptId = 201L))
        assertFalse(isActiveDownloadAttempt(listOf(queuedTask), song.stableKey(), expectedAttemptId = 200L))
        assertFalse(isActiveDownloadAttempt(listOf(completedTask), song.stableKey(), expectedAttemptId = 202L))
    }

    @Test
    fun `finalizing download task remains cancellable`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "1|Album|",
                songId = 1L,
                fileName = "song.flac",
                bytesRead = 10L,
                totalBytes = 10L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )

        assertTrue(isDownloadTaskFinalizing(task))
        assertTrue(isDownloadTaskCancellable(task))
    }

    @Test
    fun `download action stays visible while task is unfinished even if local file is detected`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = null,
            status = DownloadStatus.CANCELLED
        )

        assertFalse(
            shouldHideRemoteDownloadAction(
                hasLocalDownload = true,
                task = task
            )
        )
        assertTrue(
            shouldHideRemoteDownloadAction(
                hasLocalDownload = true,
                task = null
            )
        )
    }

    @Test
    fun `lyric only downloaded playback hydration is immediate`() {
        val originalSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = "content://covers/song.jpg",
            mediaUri = "content://audio/song.flac",
            localFileName = "song.flac",
            localFilePath = "content://audio/song.flac"
        )
        val hydratedSong = originalSong.copy(
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translated"
        )

        assertFalse(
            shouldUseImmediateDownloadedPlaybackHydration(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
        assertEquals(
            0L,
            resolveDownloadedPlaybackHydrationDelayMs(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
    }

    @Test
    fun `cover changes keep downloaded playback hydration eager`() {
        val originalSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = null,
            mediaUri = "content://audio/song.flac",
            localFileName = "song.flac",
            localFilePath = "content://audio/song.flac"
        )
        val hydratedSong = originalSong.copy(
            coverUrl = "content://covers/song.jpg"
        )

        assertTrue(
            shouldUseImmediateDownloadedPlaybackHydration(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
        assertEquals(
            1_500L,
            resolveDownloadedPlaybackHydrationDelayMs(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
    }

    @Test
    fun `applyCancelledStatus keeps cancelled tasks visible for the matching attempt`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 11L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 11L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued"
            ),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 101L
        )
        val downloadingTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 12L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 102L
        )
        val completedTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 13L, name = "Completed"),
            status = DownloadStatus.COMPLETED,
            attemptId = 103L
        )

        val updatedTasks = applyCancelledStatus(
            tasks = listOf(queuedTask, downloadingTask, completedTask),
            cancelledTasks = listOf(queuedTask, downloadingTask)
        )

        assertEquals(3, updatedTasks.size)
        assertEquals(DownloadStatus.CANCELLED, updatedTasks[0].status)
        assertEquals(DownloadStatus.CANCELLED, updatedTasks[1].status)
        assertEquals(DownloadStatus.COMPLETED, updatedTasks[2].status)
    }

    @Test
    fun `applyCancelledStatus ignores stale attempts for the same song`() {
        val song = SongItem(
            id = 21L,
            name = "Retry",
            artist = "Artist",
            album = "Album",
            albumId = 21L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/retry"
        )
        val activeRetryTask = DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 202L
        )
        val staleCancelledTask = activeRetryTask.copy(
            status = DownloadStatus.DOWNLOADING,
            attemptId = 201L
        )

        val updatedTasks = applyCancelledStatus(
            tasks = listOf(activeRetryTask),
            cancelledTasks = listOf(staleCancelledTask)
        )

        assertEquals(DownloadStatus.QUEUED, updatedTasks.single().status)
        assertEquals(202L, updatedTasks.single().attemptId)
    }

    @Test
    fun `waiting network status keeps queue visible but not active`() {
        val queuedTask = DownloadTask(
            song = recoverySong(id = 31L, name = "Queued"),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 301L
        )
        val downloadingTask = queuedTask.copy(
            song = recoverySong(id = 32L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 302L
        )

        val waitingTasks = applyWaitingNetworkStatus(
            tasks = listOf(queuedTask, downloadingTask),
            waitingTasks = listOf(queuedTask, downloadingTask)
        )

        assertEquals(listOf(DownloadStatus.WAITING_NETWORK, DownloadStatus.WAITING_NETWORK), waitingTasks.map { it.status })
        assertEquals(2, countPendingDownloadTasks(waitingTasks))
        assertFalse(hasActiveDownloadTasks(waitingTasks))
        assertFalse(
            hasActiveDownloadOperations(
                tasks = waitingTasks,
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `resolveUndeletedManagedReferences only keeps references that still exist`() = runBlocking {
        val remaining = resolveUndeletedManagedReferences(
            requestedReferences = setOf("audio", "cover", "lyric"),
            deletedReferences = setOf("audio")
        ) { reference ->
            reference == "cover"
        }

        assertEquals(setOf("cover"), remaining)
    }

    @Test
    fun `mergeManagedRequestedReferences removes duplicates across songs`() {
        val merged = mergeManagedRequestedReferences(
            listOf(
                linkedSetOf("audio-a", "cover-shared", "lyric-a"),
                linkedSetOf("audio-b", "cover-shared", "lyric-b")
            )
        )

        assertEquals(
            linkedSetOf("audio-a", "cover-shared", "lyric-a", "audio-b", "lyric-b"),
            merged
        )
    }

    @Test
    fun `groupRemainingManagedReferencesByIdentity only keeps remaining references per song`() {
        val remainingBySong = groupRemainingManagedReferencesByIdentity(
            requestedReferencesByIdentity = mapOf(
                "song-a" to setOf("audio-a", "cover-shared"),
                "song-b" to setOf("audio-b", "cover-shared", "lyric-b")
            ),
            remainingReferences = setOf("cover-shared", "lyric-b")
        )

        assertEquals(
            mapOf(
                "song-a" to setOf("cover-shared"),
                "song-b" to setOf("cover-shared", "lyric-b")
            ),
            remainingBySong
        )
    }

    @Test
    fun `shouldRepairMetadataLessManagedDownload returns true for fallback parsed source prefix`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("One Day"),
                expectedArtists = setOf("Matisyahu"),
                expectedDurationMs = 205_000L,
                actualTitle = "Matisyahu - One Day",
                actualArtist = "netease",
                actualDurationMs = 205_000L
            )
        )
    }

    @Test
    fun `shouldRepairMetadataLessManagedDownload keeps valid metadata less legacy file`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("One Day"),
                expectedArtists = setOf("Matisyahu"),
                expectedDurationMs = 205_000L,
                actualTitle = "One Day",
                actualArtist = "Matisyahu",
                actualDurationMs = 204_500L
            )
        )
    }

    @Test
    fun `download recovery merges queued snapshot and partial files without losing queued songs`() {
        val firstSong = recoverySong(id = 901L, name = "First")
        val secondSong = recoverySong(id = 902L, name = "Second")
        val queuedDownloads = listOf(
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = firstSong.stableKey(),
                song = firstSong,
                order = 0,
                queuedAtMs = 10L,
                requiresWifiNetwork = false
            ),
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = secondSong.stableKey(),
                song = secondSong,
                order = 1,
                queuedAtMs = 10L
            )
        )
        val partialFile = File("first.partial")

        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = queuedDownloads,
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = firstSong.copy(durationMs = 2_000L),
                    workingFile = partialFile
                )
            )
        )

        assertEquals(listOf(firstSong.stableKey(), secondSong.stableKey()), merged.map { it.song.stableKey() })
        assertEquals(partialFile, merged.first().workingFile)
        assertEquals(2_000L, merged.first().song.durationMs)
        assertFalse(merged.first().requiresWifiNetwork)
        assertNull(merged[1].workingFile)
    }

    @Test
    fun `download recovery preserves resumable operation identity`() {
        val song = recoverySong(id = 905L, name = "Operation")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = emptyList(),
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = song,
                    workingFile = File("operation.partial"),
                    operationId = "operation-905"
                )
            )
        )

        assertEquals("operation-905", merged.single().operationId)
    }

    @Test
    fun `download recovery preserves queued operation identity when no partial exists`() {
        val song = recoverySong(id = 906L, name = "Queued operation")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = song.stableKey(),
                    song = song,
                    order = 0,
                    queuedAtMs = 10L,
                    operationId = "operation-906"
                )
            ),
            resumableDownloads = emptyList()
        )

        assertEquals("operation-906", merged.single().operationId)
    }

    @Test
    fun `settled recovery cleanup selects only matching operation identities`() {
        val settledSong = recoverySong(id = 914L, name = "Settled")
        val unrelatedSong = recoverySong(id = 915L, name = "Unrelated")
        val candidates = listOf(
            PendingDownloadRecoveryCandidate(
                song = settledSong,
                workingFile = null,
                order = 0,
                cancelled = true,
                operationId = "settled-operation"
            ),
            PendingDownloadRecoveryCandidate(
                song = unrelatedSong,
                workingFile = null,
                order = 1,
                cancelled = false,
                operationId = "unrelated-operation"
            ),
            PendingDownloadRecoveryCandidate(
                song = settledSong.copy(name = "Settled duplicate"),
                workingFile = null,
                order = 2,
                cancelled = true,
                operationId = "   "
            )
        )

        assertEquals(
            setOf("settled-operation"),
            recoveryOperationIdsForKeys(candidates, setOf(settledSong.stableKey()))
        )
    }

    @Test
    fun `download recovery keeps first queued order when room and legacy entries overlap`() {
        val firstSong = recoverySong(id = 907L, name = "First queued")
        val secondSong = recoverySong(id = 908L, name = "Second queued")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = secondSong.stableKey(),
                    song = secondSong,
                    order = 0,
                    queuedAtMs = 10L,
                    operationId = "legacy-second"
                ),
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = firstSong.stableKey(),
                    song = firstSong,
                    order = 1,
                    queuedAtMs = 10L,
                    operationId = "legacy-first"
                )
            ),
            resumableDownloads = emptyList()
        )

        assertEquals(
            listOf(secondSong.stableKey(), firstSong.stableKey()),
            merged.map(PendingDownloadRecoveryCandidate::song).map(SongItem::stableKey)
        )
        assertEquals(
            listOf("legacy-second", "legacy-first"),
            merged.map(PendingDownloadRecoveryCandidate::operationId)
        )
    }

    @Test
    fun `interrupted operations remain recoverable unless user explicitly stopped them`() {
        listOf("RUNNING", "QUEUED", "RETRYABLE").forEach { state ->
            assertFalse(
                shouldRequireExplicitResume(
                    userInitiated = true,
                    state = state,
                    hasPendingUidtJob = false
                )
            )
        }
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = true
            )
        )
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = false,
                state = "RUNNING",
                hasPendingUidtJob = false
            )
        )
        assertTrue(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = true,
                stopRequestedByUser = true
            )
        )
    }

    @Test
    fun `download recovery marks cancelled candidates so stale partial files do not resurrect`() {
        val cancelledSong = recoverySong(id = 911L, name = "Cancelled")
        val queuedSong = recoverySong(id = 912L, name = "Queued")
        val partialFile = File("cancelled.partial")

        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = cancelledSong.stableKey(),
                    song = cancelledSong,
                    order = 0,
                    queuedAtMs = 10L
                ),
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = queuedSong.stableKey(),
                    song = queuedSong,
                    order = 1,
                    queuedAtMs = 10L
                )
            ),
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = cancelledSong,
                    workingFile = partialFile
                )
            ),
            cancelledKeys = setOf(cancelledSong.stableKey())
        )

        assertEquals(listOf(true, false), merged.map { it.cancelled })
        assertEquals(partialFile, merged.first().workingFile)
        assertEquals(listOf(cancelledSong.stableKey(), queuedSong.stableKey()), merged.map { it.song.stableKey() })
    }

    @Test
    fun `download recovery keeps a replacement operation after an old cancellation`() {
        val song = recoverySong(id = 913L, name = "Replacement")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = song.stableKey(),
                    song = song,
                    order = 0,
                    queuedAtMs = 10L,
                    operationId = "replacement-operation"
                )
            ),
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = song,
                    workingFile = File("old-operation.partial"),
                    operationId = "old-operation"
                )
            ),
            cancelledKeys = setOf(song.stableKey()),
            cancelledOperationIds = setOf("old-operation")
        )

        assertFalse(merged.single().cancelled)
        assertEquals("replacement-operation", merged.single().operationId)
        assertNull(merged.single().workingFile)
    }
}
