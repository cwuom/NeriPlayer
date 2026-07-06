package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadStorageWorkingFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `working file name is stable for same song and file`() {
        val first = ManagedDownloadStorage.buildWorkingFileName(
            songKey = "stable-song-key",
            fileName = "Artist - Song.flac"
        )
        val second = ManagedDownloadStorage.buildWorkingFileName(
            songKey = "stable-song-key",
            fileName = "Artist - Song.flac"
        )
        val differentSong = ManagedDownloadStorage.buildWorkingFileName(
            songKey = "other-song-key",
            fileName = "Artist - Song.flac"
        )

        assertEquals(first, second)
        assertNotEquals(first, differentSong)
        assertTrue(first.startsWith("npdl_"))
        assertTrue(first.endsWith(".flac.download"))
    }

    @Test
    fun `staging cleanup keeps fresh resumable partial and removes stale leftovers`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val nowMs = System.currentTimeMillis()
        val preservedFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-1",
                fileName = "Artist - Song.flac"
            )
        ).apply {
            writeText("partial-audio")
            setLastModified(nowMs - 5_000L)
        }
        ManagedDownloadStorage.saveWorkingResumeMetadata(preservedFile, queuedSong(id = 1L, name = "Song"))
        val preservedCheckpoint = ManagedDownloadStorage.buildWorkingHlsCheckpointFile(
            preservedFile
        ).apply {
            writeText("""{"playlistFingerprint":1,"nextSegmentIndex":2,"downloadedBytes":123}""")
            setLastModified(nowMs - 5_000L)
        }
        val staleResumeFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-2",
                fileName = "Artist - Old.flac"
            )
        ).apply {
            writeText("old-partial")
            setLastModified(nowMs - TimeUnit.DAYS.toMillis(8))
        }
        val zeroByteResumeFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-3",
                fileName = "Artist - Empty.flac"
            )
        ).apply {
            createNewFile()
            setLastModified(nowMs - 3_000L)
        }
        val legacyRandomFile = File(stagingDir, "Artist_Song_123.flac.download").apply {
            writeText("legacy")
            setLastModified(nowMs - 1_000L)
        }
        val orphanCheckpoint = File(
            stagingDir,
            "npdl_deadbeef_Artist_-_Ghost.flac.download.hls.json"
        ).apply {
            writeText("""{"playlistFingerprint":7,"nextSegmentIndex":3,"downloadedBytes":321}""")
            setLastModified(nowMs - 1_000L)
        }

        val result = ManagedDownloadStorage.cleanupStagingFilesInDirectory(
            stagingDir = stagingDir,
            nowMs = nowMs
        )

        assertTrue(preservedFile.exists())
        assertTrue(preservedCheckpoint.exists())
        assertFalse(staleResumeFile.exists())
        assertFalse(zeroByteResumeFile.exists())
        assertFalse(legacyRandomFile.exists())
        assertFalse(orphanCheckpoint.exists())
        assertEquals(4, result.cleanedCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `resume preservation only accepts fresh named non empty download files`() {
        val nowMs = System.currentTimeMillis()
        val file = tempFolder.newFile(
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-4",
                fileName = "Artist - Song.m4a"
            )
        ).apply {
            writeText("partial")
            setLastModified(nowMs - 1_000L)
        }
        ManagedDownloadStorage.saveWorkingResumeMetadata(file, queuedSong(id = 4L, name = "Song"))
        val staleFile = tempFolder.newFile(
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-5",
                fileName = "Artist - Song.m4a"
            )
        ).apply {
            writeText("partial")
            setLastModified(nowMs - TimeUnit.DAYS.toMillis(8))
        }
        ManagedDownloadStorage.saveWorkingResumeMetadata(staleFile, queuedSong(id = 5L, name = "Song"))
        val unnamedFile = tempFolder.newFile("legacy.download").apply {
            writeText("partial")
            setLastModified(nowMs - 1_000L)
        }
        val checkpointFile = ManagedDownloadStorage.buildWorkingHlsCheckpointFile(file).apply {
            writeText("""{"playlistFingerprint":4,"nextSegmentIndex":1,"downloadedBytes":99}""")
            setLastModified(nowMs - 1_000L)
        }
        val staleCheckpointFile = ManagedDownloadStorage.buildWorkingHlsCheckpointFile(staleFile).apply {
            writeText("""{"playlistFingerprint":4,"nextSegmentIndex":1,"downloadedBytes":99}""")
            setLastModified(nowMs - TimeUnit.DAYS.toMillis(8))
        }
        val orphanCheckpoint = tempFolder.newFile("npdl_orphan_song.m4a.download.hls.json").apply {
            writeText("""{"playlistFingerprint":5,"nextSegmentIndex":2,"downloadedBytes":88}""")
            setLastModified(nowMs - 1_000L)
        }

        assertTrue(ManagedDownloadStorage.shouldPreserveWorkingFileForResume(file, nowMs))
        assertFalse(ManagedDownloadStorage.shouldPreserveWorkingFileForResume(staleFile, nowMs))
        assertFalse(ManagedDownloadStorage.shouldPreserveWorkingFileForResume(unnamedFile, nowMs))
        assertTrue(ManagedDownloadStorage.shouldPreserveWorkingCheckpointForResume(checkpointFile, nowMs))
        assertFalse(ManagedDownloadStorage.shouldPreserveWorkingCheckpointForResume(staleCheckpointFile, nowMs))
        assertFalse(ManagedDownloadStorage.shouldPreserveWorkingCheckpointForResume(orphanCheckpoint, nowMs))
    }

    @Test
    fun `startup staging cleanup removes files without valid resume metadata`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val nowMs = System.currentTimeMillis()
        val missingResumeFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-missing-resume",
                fileName = "Missing.m4a"
            )
        ).apply {
            writeText("partial")
            setLastModified(nowMs - 1_000L)
        }
        val brokenResumeFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-broken-resume",
                fileName = "Broken.m4a"
            )
        ).apply {
            writeText("partial")
            setLastModified(nowMs - 1_000L)
        }
        ManagedDownloadStorage.buildWorkingResumeMetadataFile(brokenResumeFile).writeText("{")

        val result = ManagedDownloadStorage.cleanupStagingFilesInDirectory(
            stagingDir = stagingDir,
            nowMs = nowMs
        )

        assertFalse(missingResumeFile.exists())
        assertFalse(brokenResumeFile.exists())
        assertFalse(ManagedDownloadStorage.buildWorkingResumeMetadataFile(brokenResumeFile).exists())
        assertEquals(3, result.cleanedCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `resume metadata song round trips through json parser`() {
        val workingFile = tempFolder.newFile(
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-6",
                fileName = "Artist - Song.m4a"
            )
        )
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 12_345L,
            coverUrl = "https://example.com/cover.jpg",
            mediaUri = "https://example.com/audio.m4a",
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translated",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "9001",
            userLyricOffsetMs = 321L,
            customCoverUrl = "https://example.com/custom.jpg",
            customName = "Custom Song",
            customArtist = "Custom Artist",
            originalName = "Original Song",
            originalArtist = "Original Artist",
            originalCoverUrl = "https://example.com/original.jpg",
            originalLyric = "orig lyric",
            originalTranslatedLyric = "orig translated",
            localFileName = "Song.m4a",
            localFilePath = "/music/Song.m4a",
            channelId = "ytmusic",
            audioId = "vid",
            subAudioId = "itag",
            playlistContextId = "playlist",
            streamUrl = "https://example.com/stream.m4a",
            neteaseArtists = listOf(NeteaseArtistSummary(id = 1L, name = "Artist"))
        )

        ManagedDownloadStorage.saveWorkingResumeMetadata(workingFile, song)
        val metadataFile = ManagedDownloadStorage.buildWorkingResumeMetadataFile(workingFile)
        val restored = ManagedDownloadStorage.parseWorkingResumeMetadataSong(
            metadataFile.readText(Charsets.UTF_8)
        )

        assertEquals(song, restored)
        assertNull(ManagedDownloadStorage.parseWorkingResumeMetadataSong("{"))
    }

    @Test
    fun `pending resumable download scan only returns valid paired metadata entries`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val workingFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-7",
                fileName = "Artist - Song.m4a"
            )
        ).apply {
            writeText("partial")
        }
        val song = SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )
        ManagedDownloadStorage.saveWorkingResumeMetadata(workingFile, song)

        File(
            stagingDir,
            "npdl_orphan_song.m4a.download.resume.json"
        ).writeText("""{"id":99,"name":"Ghost","artist":"Ghost","album":"Ghost"}""")

        val pending = ManagedDownloadStorage.listPendingResumableDownloadsInDirectory(stagingDir)

        assertEquals(1, pending.size)
        assertEquals(song, pending.single().song)
        assertEquals(workingFile.absolutePath, pending.single().workingFile.absolutePath)
    }

    @Test
    fun `pending working artifacts are deleted by song key`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val targetSong = queuedSong(id = 71L, name = "Target")
        val keptSong = queuedSong(id = 72L, name = "Kept")
        val targetWorkingFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = targetSong.stableKey(),
                fileName = "Target.m4a"
            )
        ).apply {
            writeText("partial")
        }
        val keptWorkingFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = keptSong.stableKey(),
                fileName = "Kept.m4a"
            )
        ).apply {
            writeText("partial")
        }
        ManagedDownloadStorage.saveWorkingResumeMetadata(targetWorkingFile, targetSong)
        ManagedDownloadStorage.saveWorkingResumeMetadata(keptWorkingFile, keptSong)
        val targetCheckpoint = ManagedDownloadStorage.buildWorkingHlsCheckpointFile(targetWorkingFile).apply {
            writeText("""{"playlistFingerprint":1,"nextSegmentIndex":1,"downloadedBytes":7}""")
        }

        val deletedKeys = ManagedDownloadStorage.deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir = stagingDir,
            songKeys = setOf(targetSong.stableKey())
        )

        assertEquals(setOf(targetSong.stableKey()), deletedKeys)
        assertFalse(targetWorkingFile.exists())
        assertFalse(targetCheckpoint.exists())
        assertFalse(ManagedDownloadStorage.buildWorkingResumeMetadataFile(targetWorkingFile).exists())
        assertTrue(keptWorkingFile.exists())
        assertTrue(ManagedDownloadStorage.buildWorkingResumeMetadataFile(keptWorkingFile).exists())
    }

    @Test
    fun `pending working artifacts are deleted by hash when resume metadata is broken`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val targetSong = queuedSong(id = 81L, name = "Target")
        val keptSong = queuedSong(id = 82L, name = "Kept")
        val targetWorkingFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = targetSong.stableKey(),
                fileName = "Target.m4a"
            )
        ).apply {
            writeText("partial")
        }
        val keptWorkingFile = File(
            stagingDir,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = keptSong.stableKey(),
                fileName = "Kept.m4a"
            )
        ).apply {
            writeText("partial")
        }
        ManagedDownloadStorage.buildWorkingResumeMetadataFile(targetWorkingFile).writeText("{")
        ManagedDownloadStorage.saveWorkingResumeMetadata(keptWorkingFile, keptSong)
        val targetCheckpoint = ManagedDownloadStorage.buildWorkingHlsCheckpointFile(targetWorkingFile).apply {
            writeText("""{"playlistFingerprint":1,"nextSegmentIndex":1,"downloadedBytes":7}""")
        }

        val deletedKeys = ManagedDownloadStorage.deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir = stagingDir,
            songKeys = setOf(targetSong.stableKey())
        )

        assertEquals(setOf(targetSong.stableKey()), deletedKeys)
        assertFalse(targetWorkingFile.exists())
        assertFalse(targetCheckpoint.exists())
        assertFalse(ManagedDownloadStorage.buildWorkingResumeMetadataFile(targetWorkingFile).exists())
        assertTrue(keptWorkingFile.exists())
        assertTrue(ManagedDownloadStorage.buildWorkingResumeMetadataFile(keptWorkingFile).exists())
    }

    @Test
    fun `pending download queue keeps queued songs across process death`() {
        val queueFile = tempFolder.newFile("pending_download_queue_v1.json")
        val firstSong = queuedSong(id = 101L, name = "First")
        val secondSong = queuedSong(id = 102L, name = "Second")

        ManagedDownloadStorage.upsertPendingDownloadQueueInFile(
            queueFile = queueFile,
            songs = listOf(firstSong, secondSong, firstSong),
            nowMs = 10L
        )

        val restored = ManagedDownloadStorage.listPendingQueuedDownloadsFromFile(queueFile)

        assertEquals(listOf(firstSong, secondSong), restored.map { it.song })
        assertEquals(listOf(0, 1), restored.map { it.order })
        assertEquals(listOf(10L, 10L), restored.map { it.queuedAtMs })
    }

    @Test
    fun `pending download queue removes settled songs without disturbing order`() {
        val queueFile = tempFolder.newFile("pending_download_queue_v1.json")
        val firstSong = queuedSong(id = 201L, name = "First")
        val secondSong = queuedSong(id = 202L, name = "Second")
        val thirdSong = queuedSong(id = 203L, name = "Third")
        ManagedDownloadStorage.upsertPendingDownloadQueueInFile(
            queueFile = queueFile,
            songs = listOf(firstSong, secondSong, thirdSong),
            nowMs = 20L
        )

        ManagedDownloadStorage.removePendingDownloadQueueEntriesFromFile(
            queueFile = queueFile,
            songKeys = setOf(secondSong.stableKey()),
            nowMs = 30L
        )

        val restored = ManagedDownloadStorage.listPendingQueuedDownloadsFromFile(queueFile)
        assertEquals(listOf(firstSong, thirdSong), restored.map { it.song })
        assertEquals(listOf(0, 1), restored.map { it.order })
    }

    @Test
    fun `cancelled download keys survive restart until recovery consumes them`() {
        val keysFile = tempFolder.newFile("cancelled_download_keys_v1.json")
        val firstKey = queuedSong(id = 301L, name = "First").stableKey()
        val secondKey = queuedSong(id = 302L, name = "Second").stableKey()

        ManagedDownloadStorage.markCancelledDownloadKeysInFile(
            keysFile = keysFile,
            songKeys = setOf(firstKey, secondKey),
            nowMs = 40L
        )
        ManagedDownloadStorage.removeCancelledDownloadKeysFromFile(
            keysFile = keysFile,
            songKeys = setOf(firstKey),
            nowMs = 50L
        )

        assertEquals(setOf(secondKey), ManagedDownloadStorage.listCancelledDownloadKeysFromFile(keysFile))
        ManagedDownloadStorage.clearCancelledDownloadKeysFile(keysFile)
        assertTrue(ManagedDownloadStorage.listCancelledDownloadKeysFromFile(keysFile).isEmpty())
    }

    @Test
    fun `broken pending queue files are ignored instead of breaking startup`() {
        val queueFile = tempFolder.newFile("pending_download_queue_v1.json").apply {
            writeText("{", Charsets.UTF_8)
        }
        val keysFile = tempFolder.newFile("cancelled_download_keys_v1.json").apply {
            writeText("{", Charsets.UTF_8)
        }

        assertTrue(ManagedDownloadStorage.listPendingQueuedDownloadsFromFile(queueFile).isEmpty())
        assertTrue(ManagedDownloadStorage.listCancelledDownloadKeysFromFile(keysFile).isEmpty())
    }

    private fun queuedSong(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/$id"
        )
    }
}
