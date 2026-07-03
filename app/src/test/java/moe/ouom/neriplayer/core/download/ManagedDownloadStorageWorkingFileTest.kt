package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.api.search.MusicPlatform
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
        val staleFile = tempFolder.newFile(
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-5",
                fileName = "Artist - Song.m4a"
            )
        ).apply {
            writeText("partial")
            setLastModified(nowMs - TimeUnit.DAYS.toMillis(8))
        }
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
}
