package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

class GlobalDownloadManagerStartupPolicyTest {

    @Test
    fun `startup scan is skipped only when snapshot and catalog are both ready`() {
        assertEquals(false, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = false))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = false))
        assertEquals(
            true,
            shouldRunInitialDownloadScan(
                snapshotReady = true,
                catalogReady = true,
                hasRecoveredEntries = true
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
                    matchedLyric = null,
                    matchedTranslatedLyric = null,
                    originalLyric = null,
                    originalTranslatedLyric = null
                )
            ),
            restored
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
    fun `pending download task helpers ignore completed items`() {
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
            2,
            countPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertTrue(
            hasPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertFalse(hasPendingDownloadTasks(listOf(completedTask)))
        assertFalse(hasPendingDownloadTasks(listOf(cancelledTask)))
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
    fun `completed download finalization keeps missing audio fallback when not cancelled`() {
        assertEquals(
            CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO,
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = false,
                cancelled = false
            )
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
    fun `lyric only downloaded playback hydration is deferred`() {
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
            4_000L,
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
}
