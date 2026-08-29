package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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
    fun `working file identity does not rely on colliding string hash codes`() {
        val first = ManagedDownloadStorage.buildWorkingFileName(
            songKey = "FB",
            fileName = "Song.flac"
        )
        val second = ManagedDownloadStorage.buildWorkingFileName(
            songKey = "Ea",
            fileName = "Song.flac"
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `operation staging isolates files and discovers nested resume metadata`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val context = mock(android.content.Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val firstOperation = "operation-a"
        val secondOperation = "operation-b"
        val first = ManagedDownloadStorage.createWorkingFile(
            context = context,
            songKey = "same-song",
            fileName = "Song.m4a",
            operationId = firstOperation
        ).apply { writeText("partial-a") }
        val second = ManagedDownloadStorage.createWorkingFile(
            context = context,
            songKey = "same-song",
            fileName = "Song.m4a",
            operationId = secondOperation
        ).apply { writeText("partial-b") }
        ManagedDownloadStorage.saveWorkingResumeMetadata(
            first,
            queuedSong(id = 201L, name = "Song"),
            operationId = firstOperation
        )
        ManagedDownloadStorage.saveWorkingResumeMetadata(
            second,
            queuedSong(id = 202L, name = "Song 2"),
            operationId = secondOperation
        )

        val pending = ManagedDownloadStorage.listPendingResumableDownloadsInDirectory(stagingDir)

        assertEquals(2, pending.size)
        assertNotEquals(first.parentFile?.absolutePath, second.parentFile?.absolutePath)
        assertTrue(pending.all { it.operationId != null })
    }

    @Test
    fun `resume discovery keeps concurrent operations for the same stable key`() {
        val context = mock(android.content.Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val stagingDir = File(tempFolder.root, "download_staging")
        val song = queuedSong(id = 901L, name = "Same")
        val first = ManagedDownloadStorage.createWorkingFile(
            context = context,
            songKey = song.stableKey(),
            fileName = "same.flac",
            operationId = "operation-a"
        )
        val second = ManagedDownloadStorage.createWorkingFile(
            context = context,
            songKey = song.stableKey(),
            fileName = "same.flac",
            operationId = "operation-b"
        )
        first.writeBytes(byteArrayOf(1))
        second.writeBytes(byteArrayOf(2))
        ManagedDownloadStorage.saveWorkingResumeMetadata(first, song, operationId = "operation-a")
        ManagedDownloadStorage.saveWorkingResumeMetadata(second, song, operationId = "operation-b")

        val discovered = ManagedDownloadStorage.listPendingResumableDownloadsInDirectory(stagingDir)

        assertEquals(setOf("operation-a", "operation-b"), discovered.mapNotNull { it.operationId }.toSet())
        assertEquals(2, discovered.size)
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
        assertTrue(legacyRandomFile.exists())
        assertFalse(orphanCheckpoint.exists())
        assertEquals(3, result.cleanedCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `legacy prepared residue is deleted without protecting arbitrary staging files`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val legacySidecar = File(
            stagingDir,
            "npdl_sidecar_${"a".repeat(64)}_123e4567-e89b-12d3-a456-426614174000.cover"
        ).apply {
            writeText("legacy-cover")
        }
        val legacyManifest = File(stagingDir, "npdl_sidecar_manifest_${"a".repeat(8)}.json").apply {
            writeText(
                """{"version":1,"songKey":"legacy-song","cover":{"file":"${legacySidecar.absolutePath}"}}"""
            )
        }
        val operationDirectory = File(
            stagingDir,
            "123e4567-e89b-12d3-a456-426614174000"
        ).apply { mkdirs() }
        val operationSidecar = File(
            operationDirectory,
            "npdl_sidecar_${"b".repeat(64)}_123e4567-e89b-12d3-a456-426614174000.lyric"
        ).apply {
            writeText("legacy-lyric")
        }
        val operationManifest = File(operationDirectory, "operation.json").apply {
            writeText("""{"version":2,"songKey":"legacy-song"}""")
        }
        val unrelatedFile = File(stagingDir, "notes.txt").apply {
            writeText("keep user data")
        }
        val unrelatedDirectory = File(stagingDir, "notes").apply { mkdirs() }
        val unrelatedManifest = File(unrelatedDirectory, "operation.json").apply {
            writeText("{")
        }

        ManagedDownloadStorage.cleanupStagingFilesInDirectory(
            stagingDir = stagingDir,
            nowMs = System.currentTimeMillis()
        )

        assertFalse(legacyManifest.exists())
        assertFalse(legacySidecar.exists())
        assertFalse(operationManifest.exists())
        assertFalse(operationSidecar.exists())
        assertTrue(unrelatedFile.exists())
        assertTrue(unrelatedManifest.exists())
    }

    @Test
    fun `operation resume metadata survives legacy manifest cleanup`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val context = mock(android.content.Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val song = queuedSong(id = 902L, name = "Resume")
        val operationId = "123e4567-e89b-12d3-a456-426614174001"
        val workingFile = ManagedDownloadStorage.createWorkingFile(
            context = context,
            songKey = song.stableKey(),
            fileName = "resume.m4a",
            operationId = operationId
        ).apply {
            writeText("partial")
        }
        ManagedDownloadStorage.saveWorkingResumeMetadata(
            workingFile,
            song,
            operationId = operationId
        )
        val operationManifest = File(workingFile.parentFile, "operation.json").apply {
            writeText("""{"version":2,"songKey":"${song.stableKey()}"}""")
        }
        val legacySidecar = File(
            workingFile.parentFile,
            "npdl_sidecar_${"c".repeat(64)}_123e4567-e89b-12d3-a456-426614174000.cover"
        ).apply {
            writeText("legacy-cover")
        }

        ManagedDownloadStorage.cleanupStagingFilesInDirectory(
            stagingDir = stagingDir,
            nowMs = System.currentTimeMillis()
        )

        assertTrue(workingFile.exists())
        assertTrue(ManagedDownloadStorage.buildWorkingResumeMetadataFile(workingFile).exists())
        assertFalse(operationManifest.exists())
        assertFalse(legacySidecar.exists())
        val recovered = ManagedDownloadStorage.listPendingResumableDownloadsInDirectory(stagingDir)
        assertEquals(1, recovered.size)
        assertEquals(operationId, recovered.single().operationId)
    }

    @Test
    fun `staging cleanup ignores a directory outside the managed staging root`() {
        val unrelatedDirectory = tempFolder.newFolder("not-download-staging")
        val partialFile = File(
            unrelatedDirectory,
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "unrelated-song",
                fileName = "song.m4a"
            )
        ).apply {
            writeText("partial")
        }

        val result = ManagedDownloadStorage.cleanupStagingFilesInDirectory(
            stagingDir = unrelatedDirectory,
            nowMs = System.currentTimeMillis()
        )

        assertTrue(partialFile.exists())
        assertEquals(0, result.cleanedCount)
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
    fun `resume metadata preserves remote fingerprint when song payload is refreshed`() {
        val workingFile = tempFolder.newFile(
            ManagedDownloadStorage.buildWorkingFileName(
                songKey = "song-fingerprint",
                fileName = "Artist - Song.m4a"
            )
        )
        val song = queuedSong(id = 601L, name = "Song")
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = "https://example.com/audio.m4a",
            etag = "\"etag-601\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 65_536L
        )

        ManagedDownloadStorage.saveWorkingResumeMetadata(workingFile, song)
        ManagedDownloadStorage.updateWorkingResumeFingerprint(workingFile, fingerprint)
        ManagedDownloadStorage.saveWorkingResumeMetadata(workingFile, song.copy(customName = "Custom Song"))

        assertEquals(fingerprint, ManagedDownloadStorage.readWorkingResumeFingerprint(workingFile))
        assertEquals(
            song.copy(customName = "Custom Song"),
            ManagedDownloadStorage.parseWorkingResumeMetadataSong(
                ManagedDownloadStorage.buildWorkingResumeMetadataFile(workingFile)
                    .readText(Charsets.UTF_8)
            )
        )
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
    fun `pending cleanup removes owned sidecars in root and operation directories`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val targetSong = queuedSong(id = 73L, name = "Target")
        val keptSong = queuedSong(id = 74L, name = "Kept")
        val targetHash = ManagedDownloadRecoveryFiles.buildWorkingSongKeyHash(targetSong.stableKey())
        val keptHash = ManagedDownloadRecoveryFiles.buildWorkingSongKeyHash(keptSong.stableKey())
        val targetUuid = "123e4567-e89b-12d3-a456-426614174000"
        val keptUuid = "123e4567-e89b-12d3-a456-426614174001"

        val targetRootSidecar = File(
            stagingDir,
            "npdl_sidecar_${targetHash}_$targetUuid.cover"
        ).apply { writeText("target-root") }
        val targetOperationDirectory = File(stagingDir, "operation-target").apply { mkdirs() }
        val targetOperationSidecar = File(
            targetOperationDirectory,
            "npdl_sidecar_${targetHash}_$targetUuid.lyric"
        ).apply { writeText("target-operation") }
        val keptSidecar = File(
            stagingDir,
            "npdl_sidecar_${keptHash}_$keptUuid.cover"
        ).apply { writeText("kept") }
        val shortHashSidecar = File(
            stagingDir,
            "npdl_sidecar_${targetHash.take(8)}_$targetUuid.cover"
        ).apply { writeText("legacy-short-hash") }
        val malformedUuidSidecar = File(
            stagingDir,
            "npdl_sidecar_${targetHash}_not-a-uuid.cover"
        ).apply { writeText("malformed-uuid") }
        val ordinaryFile = File(stagingDir, "notes.txt").apply { writeText("keep user data") }

        val deletedKeys = ManagedDownloadStorage.deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir = stagingDir,
            songKeys = setOf(targetSong.stableKey())
        )

        assertEquals(setOf(targetSong.stableKey()), deletedKeys)
        assertFalse(targetRootSidecar.exists())
        assertFalse(targetOperationSidecar.exists())
        assertFalse(targetOperationDirectory.exists())
        assertTrue(keptSidecar.exists())
        assertTrue(shortHashSidecar.exists())
        assertTrue(malformedUuidSidecar.exists())
        assertTrue(ordinaryFile.exists())
    }

    @Test
    fun `broken resume metadata does not grant ownership for deletion`() {
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

        assertTrue(deletedKeys.isEmpty())
        assertTrue(targetWorkingFile.exists())
        assertTrue(targetCheckpoint.exists())
        assertTrue(ManagedDownloadStorage.buildWorkingResumeMetadataFile(targetWorkingFile).exists())
        assertTrue(keptWorkingFile.exists())
        assertTrue(ManagedDownloadStorage.buildWorkingResumeMetadataFile(keptWorkingFile).exists())
    }

    @Test
    fun `legacy pending queue codec keeps queued songs for bootstrap`() {
        val firstSong = queuedSong(id = 101L, name = "First")
        val secondSong = queuedSong(id = 102L, name = "Second")
        val entries = listOf(
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = firstSong.stableKey(), song = firstSong, order = 0, queuedAtMs = 10L
            ),
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = secondSong.stableKey(), song = secondSong, order = 1, queuedAtMs = 10L
            )
        )

        val payload = ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
            entries = entries,
            updatedAtMs = 10L
        )
        val restored = ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(payload)

        assertEquals(listOf(firstSong, secondSong), restored.map { it.song })
        assertEquals(listOf(0, 1), restored.map { it.order })
        assertEquals(listOf(10L, 10L), restored.map { it.queuedAtMs })
    }

    @Test
    fun `legacy pending queue codec preserves order after bootstrap filtering`() {
        val firstSong = queuedSong(id = 201L, name = "First")
        val secondSong = queuedSong(id = 202L, name = "Second")
        val thirdSong = queuedSong(id = 203L, name = "Third")
        val payload = ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
            entries = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = firstSong.stableKey(), song = firstSong, order = 0, queuedAtMs = 20L
                ),
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = secondSong.stableKey(), song = secondSong, order = 1, queuedAtMs = 20L
                ),
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = thirdSong.stableKey(), song = thirdSong, order = 2, queuedAtMs = 20L
                )
            ),
            updatedAtMs = 20L
        )
        val restored = ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(payload)
            .filterNot { it.stableKey == secondSong.stableKey() }
            .mapIndexed { index, entry -> entry.copy(order = index) }
        assertEquals(listOf(firstSong, thirdSong), restored.map { it.song })
        assertEquals(listOf(0, 1), restored.map { it.order })
    }

    @Test
    fun `legacy cancellation codec keeps keys until bootstrap consumes them`() {
        val firstKey = queuedSong(id = 301L, name = "First").stableKey()
        val secondKey = queuedSong(id = 302L, name = "Second").stableKey()

        val payload = ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
            songKeys = setOf(firstKey, secondKey),
            updatedAtMs = 40L
        )
        val restored = ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(payload)
        assertEquals(setOf(firstKey, secondKey), restored)
    }

    @Test
    fun `broken legacy queue payload is rejected instead of becoming runtime state`() {
        val queueError = runCatching {
            ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload("{")
        }.exceptionOrNull()
        val cancelledError = runCatching {
            ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload("{")
        }.exceptionOrNull()
        assertTrue(queueError != null)
        assertTrue(cancelledError != null)
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
