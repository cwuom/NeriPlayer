package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadTask
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.buildDownloadTaskSummary
import moe.ouom.neriplayer.core.download.model.countFailedDownloadTasks
import moe.ouom.neriplayer.core.download.model.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.model.hasActiveDownloadOperations
import moe.ouom.neriplayer.core.download.model.hasActiveDownloadTasks
import moe.ouom.neriplayer.core.download.model.hasPendingDownloadTasks
import moe.ouom.neriplayer.core.download.model.hasRecoveryBlockingDownloadOperations
import moe.ouom.neriplayer.core.download.model.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.download.model.isDownloadTaskFinalizing
import moe.ouom.neriplayer.core.download.model.shouldApplyTaskMutation
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


class GlobalDownloadManagerStartupPolicyGroup2Test : GlobalDownloadManagerStartupPolicyTestSupport() {

    @Test
    fun `startup download recovery waits for user decision on mobile data`() {
        assertFalse(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.ROAMING,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true
            )
        )
    }

    @Test
    fun `wifi admitted execution waits after transport moves to mobile until user continues`() {
        assertTrue(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.ROAMING,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = false,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
    }

    @Test
    fun `only Wi-Fi-bound durable work keeps network policy active before memory rehydrates`() {
        assertFalse(
            hasWifiBoundNetworkPolicyDownloads(
                activeTaskCount = 0,
                persistedQueuedCount = 0
            )
        )
        assertTrue(
            hasWifiBoundNetworkPolicyDownloads(
                activeTaskCount = 0,
                persistedQueuedCount = 1
            )
        )
    }

    @Test
    fun `Wi-Fi waiting count is the stable-key union across active and durable work`() {
        assertEquals(
            4,
            wifiBoundDownloadTaskCount(
                activeSongKeys = listOf("active-a", "shared", "  "),
                persistedSongKeys = listOf("shared", "persisted-c", "persisted-d", "")
            )
        )
    }

    @Test
    fun `mobile interruption recount distinguishes unavailable data from an authoritative zero`() {
        assertEquals(
            843,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = null,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            0,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = 0,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            2,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = 2,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            3,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 1,
                observedTaskCount = null,
                fallbackTaskCount = 3
            )
        )
    }

    @Test
    fun `mobile interruption snapshot cannot publish after a clear advances its epoch`() {
        assertTrue(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = null,
                currentEpoch = 5L
            )
        )
        assertTrue(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = 5L,
                currentEpoch = 5L
            )
        )
        assertFalse(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = 5L,
                currentEpoch = 6L
            )
        )
    }

    @Test
    fun `known cancelled durable operation is not counted through a stale fallback queue entry`() {
        assertNull(
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = true,
                durableRequiresWifi = null
            )
        )
        assertEquals(
            true,
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = false,
                durableRequiresWifi = null
            )
        )
        assertEquals(
            false,
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = true,
                durableRequiresWifi = false
            )
        )
    }

    @Test
    fun `Wi-Fi disconnect revokes mobile override only while the current route remains non Wi-Fi`() {
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.WIFI,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.ROAMING,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertTrue(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertTrue(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.ROAMING,
                currentNetworkType = TrafficNetworkType.ROAMING
            )
        )
    }

    @Test
    fun `Wi-Fi disconnect skips stale callback after Wi-Fi is restored`() {
        assertFalse(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertTrue(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.WIFI,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `explicit mobile permission is not paused by a later Wi-Fi disconnect`() {
        assertTrue(shouldPauseDownloadForWifiDisconnect(requiresWifiNetwork = true))
        assertFalse(shouldPauseDownloadForWifiDisconnect(requiresWifiNetwork = false))
    }

    @Test
    fun `network pause marker survives an unsettled prior transfer`() {
        assertFalse(
            shouldClearNetworkPolicyPauseAfterCancellationSettled(
                cancellationSettled = false
            )
        )
        assertTrue(
            shouldClearNetworkPolicyPauseAfterCancellationSettled(
                cancellationSettled = true
            )
        )
    }

    @Test
    fun `prepared recovery download start is blocked on mobile data until user confirms`() {
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = false
            )
        )
        assertTrue(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = true
            )
        )
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true,
                deferForNetworkPolicy = true
            )
        )
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = true
            )
        )
    }

    @Test
    fun `missing downloaded cover is repaired only when a network candidate exists`() {
        assertTrue(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = false,
                hasNetworkCoverCandidate = true
            )
        )
        assertFalse(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = true,
                hasNetworkCoverCandidate = true
            )
        )
        assertFalse(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = false,
                hasNetworkCoverCandidate = false
            )
        )
    }

    @Test
    fun `cancel cleanup survives invalidated generation until a new request takes over`() {
        assertTrue(
            shouldKeepCancellationCleanup(
                currentGeneration = 10L,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertTrue(
            shouldKeepCancellationCleanup(
                currentGeneration = null,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertFalse(
            shouldKeepCancellationCleanup(
                currentGeneration = 11L,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertFalse(
            shouldKeepCancellationCleanup(
                currentGeneration = null,
                cancellationGeneration = 10L,
                cancelled = false
            )
        )
    }

    @Test
    fun `downloaded song catalog keeps lightweight list fields in json cache`() {
        val song = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.mp3",
            fileSize = 2048L,
            downloadTime = 123456L,
            coverPath = "/music/Covers/song.jpg",
            coverUrl = "https://example.com/cover.jpg",
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translated",
            matchedLyricSource = "CLOUD_MUSIC",
            matchedSongId = "9001",
            userLyricOffsetMs = 120L,
            customCoverUrl = "https://example.com/custom.jpg",
            customName = "Custom Song",
            customArtist = "Custom Artist",
            originalName = "Original Song",
            originalArtist = "Original Artist",
            originalCoverUrl = "https://example.com/original.jpg",
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translated lyric",
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

        assertEquals(
            listOf(
                song.copy(
                    originalLyric = null,
                    originalTranslatedLyric = null,
                    originalRomanizedLyric = null
                )
            ),
            restored
        )
    }

    @Test
    fun `catalog upsert immediately replaces a custom cover with the restored cover`() {
        val customCoverSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.m4a",
            fileSize = 2048L,
            downloadTime = 123456L,
            coverPath = "file:///data/user/0/app/files/original-cover.jpg",
            customCoverUrl = "file:///data/user/0/app/files/custom-cover.jpg",
            originalCoverUrl = "file:///data/user/0/app/files/original-cover.jpg",
            mediaUri = "content://downloads/song.m4a",
            stableKey = "42|netease|"
        )
        val restoredCoverSong = customCoverSong.copy(
            customCoverUrl = null,
            coverPath = "file:///data/user/0/app/files/original-cover.jpg"
        )

        assertTrue(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = customCoverSong,
                updatedSong = restoredCoverSong
            )
        )
        assertEquals(
            listOf(restoredCoverSong),
            upsertDownloadedSongCatalog(
                currentSongs = listOf(customCoverSong),
                updatedSong = restoredCoverSong
            )
        )
    }

    @Test
    fun `resolveDownloadedLyricContent keeps embedded and local fallbacks compatible`() {
        assertEquals(
            "embedded lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = "embedded lyric",
                embeddedOriginalLyric = "original lyric",
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "original lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = "original lyric",
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "local lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = null,
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "indexed lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = null,
                localLyricContent = null,
                indexedLyricContent = "indexed lyric"
            )
        )
    }

    @Test
    fun `resolveDownloadedLyricOverride keeps explicit blank metadata over fallback lyrics`() {
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = null,
                embeddedMatchedLyric = "",
                embeddedOriginalLyric = "[00:00.00]original",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = "",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = "",
                embeddedMatchedLyric = "[00:00.00]embedded",
                embeddedOriginalLyric = "[00:00.00]original",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
    }

    @Test
    fun `download task remains cancellable during finalizing stage`() {
        val task = DownloadTask(
            song = SongItem(
                id = 7L,
                name = "Finalizing",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/finalizing"
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "7|Album|https://example.com/finalizing",
                songId = 7L,
                fileName = "Finalizing.flac",
                bytesRead = 1024L,
                totalBytes = 1024L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )

        assertTrue(isDownloadTaskFinalizing(task))
        assertTrue(isDownloadTaskCancellable(task))
    }

    @Test
    fun `task mutation applies only to matching attempt id`() {
        val task = DownloadTask(
            song = SongItem(
                id = 8L,
                name = "Attempt",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/attempt"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 42L
        )

        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = 42L))
        assertFalse(shouldApplyTaskMutation(task, expectedAttemptId = 7L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = null))
    }

    @Test
    fun `upsertDownloadedSongCatalog replaces same file and keeps newest first`() {
        val olderSong = DownloadedSong(
            id = 1L,
            name = "Older",
            artist = "Artist",
            album = "Album",
            filePath = "/music/older.flac",
            fileSize = 10L,
            downloadTime = 10L,
            durationMs = 1000L
        )
        val currentSong = DownloadedSong(
            id = 2L,
            name = "Current",
            artist = "Artist",
            album = "Album",
            filePath = "/music/current.flac",
            fileSize = 20L,
            downloadTime = 30L,
            durationMs = 2000L
        )
        val updatedCurrentSong = currentSong.copy(name = "Current V2", downloadTime = 40L)

        val merged = upsertDownloadedSongCatalog(
            currentSongs = listOf(olderSong, currentSong),
            updatedSong = updatedCurrentSong
        )

        assertEquals(listOf(updatedCurrentSong, olderSong), merged)
    }

    @Test
    fun `upsertDownloadedSongCatalog appends new file without disturbing existing items`() {
        val firstSong = DownloadedSong(
            id = 1L,
            name = "First",
            artist = "Artist",
            album = "Album",
            filePath = "/music/first.flac",
            fileSize = 10L,
            downloadTime = 50L,
            durationMs = 1000L
        )
        val secondSong = DownloadedSong(
            id = 2L,
            name = "Second",
            artist = "Artist",
            album = "Album",
            filePath = "/music/second.flac",
            fileSize = 20L,
            downloadTime = 40L,
            durationMs = 2000L
        )
        val thirdSong = DownloadedSong(
            id = 3L,
            name = "Third",
            artist = "Artist",
            album = "Album",
            filePath = "/music/third.flac",
            fileSize = 30L,
            downloadTime = 45L,
            durationMs = 3000L
        )

        val merged = upsertDownloadedSongCatalog(
            currentSongs = listOf(firstSong, secondSong),
            updatedSong = thirdSong
        )

        assertEquals(listOf(firstSong, thirdSong, secondSong), merged)
    }

    @Test
    fun `catalog order is stable when download times are equal`() {
        val first = DownloadedSong(
            id = 1L,
            name = "First",
            artist = "Artist",
            album = "Album",
            filePath = "/music/z.flac",
            fileSize = 1L,
            downloadTime = 100L
        )
        val second = first.copy(
            id = 2L,
            name = "Second",
            filePath = "/music/a.flac"
        )

        assertEquals(
            listOf(second, first),
            upsertDownloadedSongCatalog(listOf(first), second)
        )
    }

    @Test
    fun `pending download task helpers separate terminal failures`() {
        val downloadingTask = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Downloading",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/downloading"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )
        val completedTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 2L, name = "Completed"),
            status = DownloadStatus.COMPLETED
        )
        val failedTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 3L, name = "Failed"),
            status = DownloadStatus.FAILED
        )
        val cancelledTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 4L, name = "Cancelled"),
            status = DownloadStatus.CANCELLED
        )

        assertEquals(
            1,
            countPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertEquals(
            1,
            countFailedDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertTrue(
            hasPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertFalse(hasPendingDownloadTasks(listOf(failedTask)))
        assertFalse(hasPendingDownloadTasks(listOf(completedTask)))
        assertFalse(hasPendingDownloadTasks(listOf(cancelledTask)))

        val summary = buildDownloadTaskSummary(
            listOf(downloadingTask, completedTask, failedTask, cancelledTask)
        )
        assertEquals(1, summary.pendingTaskCount)
        assertEquals(1, summary.failedTaskCount)
        assertEquals(0, summary.queuedTaskCount)
        assertTrue(summary.hasActiveTasks)
        assertTrue(summary.hasActiveOperations)
        assertTrue(summary.hasFailedTasks)
        assertTrue(summary.hasDownloadManagerEntry)
    }

    @Test
    fun `active download helpers keep finalizing tasks cancellable`() {
        val finalizingTask = DownloadTask(
            song = SongItem(
                id = 4L,
                name = "Finalizing",
                artist = "Artist",
                album = "Album",
                albumId = 4L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/finalizing"
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "song:4",
                songId = 4L,
                fileName = "Finalizing.flac",
                bytesRead = 1_024L,
                totalBytes = 1_024L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )
        val completedTask = finalizingTask.copy(
            song = finalizingTask.song.copy(id = 5L, name = "Completed"),
            progress = null,
            status = DownloadStatus.COMPLETED
        )

        assertTrue(isDownloadTaskFinalizing(finalizingTask))
        assertTrue(isDownloadTaskCancellable(finalizingTask))
        assertTrue(hasActiveDownloadTasks(listOf(finalizingTask, completedTask)))
        assertFalse(hasActiveDownloadTasks(listOf(completedTask)))
    }

    @Test
    fun `active download operations keep directory changes blocked until download pipeline is fully idle`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 6L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 6L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued"
            ),
            progress = null,
            status = DownloadStatus.QUEUED
        )
        val completedTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 7L, name = "Completed"),
            status = DownloadStatus.COMPLETED
        )

        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = true,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = true
            )
        )
        assertFalse(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `recovery is not blocked by stale queued tasks without a running pipeline`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 8L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 8L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued-recovery"
            ),
            progress = null,
            status = DownloadStatus.QUEUED
        )
        val downloadingTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 9L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING
        )

        assertFalse(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(downloadingTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = true
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = emptyList(),
                isSingleDownloading = true,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `findDownloadedSongCatalogMatch prefers stable identity for remote favorites playback`() {
        val song = SongItem(
            id = 9L,
            name = "Favorite Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://music.163.com/song?id=9"
        )
        val downloaded = DownloadedSong(
            id = 100L,
            name = "renamed locally",
            artist = "local artist",
            album = "Downloads",
            filePath = "content://downloads/9",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = song.stableKey()
        )

        assertEquals(downloaded, findDownloadedSongCatalogMatch(song, listOf(downloaded)))
    }

    @Test
    fun `downloaded song catalog index keeps newest stable match first`() {
        val song = SongItem(
            id = 9L,
            name = "Favorite Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://music.163.com/song?id=9"
        )
        val newest = DownloadedSong(
            id = 100L,
            name = "renamed locally",
            artist = "local artist",
            album = "Downloads",
            filePath = "content://downloads/newest",
            fileSize = 10L,
            downloadTime = 20L,
            stableKey = song.stableKey()
        )
        val older = newest.copy(
            filePath = "content://downloads/older",
            downloadTime = 10L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(newest, older)
        )

        assertEquals(newest, index.find(song))
    }

    @Test
    fun `downloaded song catalog index still falls back to legacy identity when stable key entry mismatches`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Bilibili|2002",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null
        )
        val mismatchedStable = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/mismatch.flac",
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
        val legacyFallback = mismatchedStable.copy(
            filePath = "/music/legacy.flac",
            downloadTime = 10L,
            stableKey = null
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(mismatchedStable, legacyFallback)
        )

        assertEquals(legacyFallback, index.find(song))
    }

    @Test
    fun `downloaded song catalog keeps legacy remote entries without source identity`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val legacyDownloaded = DownloadedSong(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(legacyDownloaded))

        assertEquals(legacyDownloaded, index.find(song))
        assertTrue(matchesDownloadedSong(song, legacyDownloaded))
    }

    @Test
    fun `downloaded song catalog keeps a legacy local stable key entry for its remote song`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val legacyDownloaded = DownloadedSong(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = "42|__local_files__|/music/song.flac"
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(legacyDownloaded))

        assertEquals(legacyDownloaded, index.find(song))
        assertTrue(matchesDownloadedSong(song, legacyDownloaded))
    }

    @Test
    fun `downloaded catalog matches a local shaped queue item through its remote identity`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val downloaded = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = remoteSong.stableKey(),
            sourceChannelId = "netease",
            sourceAudioId = "42"
        )
        val localShapedQueueItem = remoteSong.copy(
            mediaUri = "content://old-tree/song",
            sourceStableKey = null
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(downloaded))

        assertEquals(downloaded, index.find(localShapedQueueItem))
        assertTrue(matchesDownloadedSong(localShapedQueueItem, downloaded))
    }

    @Test
    fun `downloaded catalog recovers a legacy local row after its SAF uri changes`() {
        val localSong = SongItem(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://new-tree/document/primary%3AMusic%2FSong.flac",
            localFileName = "Song.flac"
        )
        val legacyDownloaded = DownloadedSong(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/old-private/Song.flac",
            fileSize = 1024L,
            downloadTime = 10L,
            durationMs = 180_000L,
            coverPath = "/old-private/Covers/Song.jpg"
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(legacyDownloaded)
        )

        assertEquals(legacyDownloaded, index.find(localSong))
    }

    @Test
    fun `downloaded catalog refuses ambiguous legacy local filenames`() {
        val localSong = SongItem(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://new-tree/document/primary%3AMusic%2FSong.flac",
            localFileName = "Song.flac"
        )
        val first = DownloadedSong(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/old-private/Song.flac",
            fileSize = 1024L,
            downloadTime = 10L,
            durationMs = 180_000L
        )
        val second = first.copy(
            filePath = "/other-private/Song.flac",
            downloadTime = 11L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(first, second)
        )

        assertNull(index.find(localSong))
    }
}
