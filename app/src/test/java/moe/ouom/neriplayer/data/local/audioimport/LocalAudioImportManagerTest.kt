package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudioImportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `copyNearbySidecars keeps track specific cover ahead of generic folder art`() {
        val sourceDir = tempFolder.newFolder("source-track-cover")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.png").writeText("track-cover")
        File(sourceDir, "cover.jpg").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-track-cover")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val copiedTrackCover = File(targetDir, "imported_song.png")
        val copiedGenericCover = File(File(targetDir, "Covers"), "imported_song.jpg")
        val resolvedCover = LocalMediaSupport.findNearbyCover(targetAudio)

        assertTrue(copiedTrackCover.exists())
        assertTrue(copiedGenericCover.exists())
        assertNotNull(resolvedCover)
        assertEquals(copiedTrackCover.canonicalPath, resolvedCover?.canonicalPath)
    }

    @Test
    fun `copyNearbySidecars stores generic folder art in Covers fallback path`() {
        val sourceDir = tempFolder.newFolder("source-generic-cover")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "folder.jpg").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-generic-cover")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val unexpectedSiblingCover = File(targetDir, "imported_song.jpg")
        val copiedGenericCover = File(File(targetDir, "Covers"), "imported_song.jpg")
        val resolvedCover = LocalMediaSupport.findNearbyCover(targetAudio)

        assertFalse(unexpectedSiblingCover.exists())
        assertTrue(copiedGenericCover.exists())
        assertEquals(copiedGenericCover.canonicalPath, resolvedCover?.canonicalPath)
    }

    @Test
    fun `buildNearbySidecarCopyPlans keeps source Covers artwork as track specific target`() {
        val sourceDir = tempFolder.newFolder("source-cover-dir")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(File(sourceDir, "Covers").apply { mkdirs() }, "song.jpg").writeText("track-cover")
        File(sourceDir, "cover.png").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-cover-dir")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        val plans = buildNearbySidecarCopyPlans(
            sourceFile = sourceAudio,
            targetFile = targetAudio,
            lyricExtensions = listOf("lrc", "txt"),
            imageExtensions = listOf("jpg", "jpeg", "png", "webp"),
            coverNames = listOf("cover", "folder", "front")
        )

        assertTrue(
            plans.any { plan ->
                plan.source.name == "song.jpg" &&
                    plan.target.canonicalPath == File(targetDir, "imported_song.jpg").canonicalPath
            }
        )
        assertTrue(
            plans.any { plan ->
                plan.source.name == "cover.png" &&
                    plan.target.canonicalPath == File(File(targetDir, "Covers"), "imported_song.png").canonicalPath
            }
        )
    }

    @Test
    fun `buildQuickImportedSong falls back to file name and local placeholder metadata`() {
        val importedFile = tempFolder.newFile("001_demo_track.flac")

        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = importedFile.absolutePath,
                displayName = importedFile.name,
                title = "content://provider/audio/42",
                artist = "",
                album = "",
                durationMs = null,
                localFile = importedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("001_demo_track", song.name)
        assertEquals("Unknown Artist", song.artist)
        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, song.album)
        assertEquals(0L, song.durationMs)
        assertEquals(importedFile.absolutePath, song.mediaUri)
        assertEquals(importedFile.absolutePath, song.localFilePath)
    }

    @Test
    fun `buildQuickImportedSong keeps content playback uri when source came from media store`() {
        val importedFile = tempFolder.newFile("media_store_track.flac")

        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/42",
                displayName = importedFile.name,
                title = "MediaStore Title",
                artist = "MediaStore Artist",
                album = "MediaStore Album",
                durationMs = 245_000L,
                localFile = importedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("content://media/external/audio/media/42", song.mediaUri)
        assertEquals(importedFile.absolutePath, song.localFilePath)
    }

    @Test
    fun `buildQuickImportedSong keeps cheap query metadata and nearby cover`() {
        val importedFile = tempFolder.newFile("cover_demo.mp3")
        val nearbyCover = File(importedFile.parentFile, "cover_demo.jpg").apply {
            writeText("cover")
        }

        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = importedFile.absolutePath,
                displayName = importedFile.name,
                title = "Quick Title",
                artist = "Quick Artist",
                album = "Quick Album",
                durationMs = 123_000L,
                localFile = importedFile,
                nearbyCoverUri = nearbyCover.toURI().toString()
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("Quick Title", song.name)
        assertEquals("Quick Artist", song.artist)
        assertEquals("Quick Album", song.album)
        assertEquals(123_000L, song.durationMs)
        assertEquals(nearbyCover.toURI().toString(), song.coverUrl)
        assertEquals(nearbyCover.toURI().toString(), song.originalCoverUrl)
    }

    @Test
    fun `mergeImportedSongMetadata keeps quick identity while adopting richer metadata`() {
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/7",
                displayName = "demo.mp3",
                title = "Quick Title",
                artist = "Quick Artist",
                album = null,
                durationMs = 0L
            ),
            unknownArtistLabel = "Unknown Artist"
        )
        val detailedSong = quickSong.copy(
            name = "content://bad-title",
            artist = "Detailed Artist",
            album = "Detailed Album",
            durationMs = 245_000L,
            coverUrl = "file:///covers/demo.jpg",
            matchedLyric = "[00:00.00]demo",
            originalArtist = "Detailed Artist",
            originalCoverUrl = "file:///covers/demo.jpg"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quickSong, detailedSong)

        assertEquals(quickSong.id, merged.id)
        assertEquals(quickSong.audioId, merged.audioId)
        assertEquals(quickSong.mediaUri, merged.mediaUri)
        assertEquals(quickSong.localFilePath, merged.localFilePath)
        assertEquals("Quick Title", merged.name)
        assertEquals("Detailed Artist", merged.artist)
        assertEquals("Detailed Album", merged.album)
        assertEquals(245_000L, merged.durationMs)
        assertEquals("file:///covers/demo.jpg", merged.coverUrl)
        assertEquals("[00:00.00]demo", merged.matchedLyric)
    }
}
