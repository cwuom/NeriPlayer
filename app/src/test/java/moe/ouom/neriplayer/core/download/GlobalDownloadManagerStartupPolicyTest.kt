package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

class GlobalDownloadManagerStartupPolicyTest {

    @Test
    fun `startup scan is skipped only when snapshot and catalog are both ready`() {
        assertEquals(false, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = false))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = false))
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

        assertEquals(
            2,
            countPendingDownloadTasks(listOf(downloadingTask, completedTask, failedTask))
        )
        assertTrue(
            hasPendingDownloadTasks(listOf(downloadingTask, completedTask, failedTask))
        )
        assertFalse(hasPendingDownloadTasks(listOf(completedTask)))
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
}
