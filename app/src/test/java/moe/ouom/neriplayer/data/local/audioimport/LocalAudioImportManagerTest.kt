package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.CancellationException

class LocalAudioImportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `empty media store result falls back to SAF traversal`() {
        val indexedSong = SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertFalse(
            shouldUseMediaStoreScanResult(
                LocalAudioImportResult(
                    songs = emptyList(),
                    failedCount = 0,
                    completed = true
                )
            )
        )
        assertTrue(
            shouldUseMediaStoreScanResult(
                LocalAudioImportResult(
                    songs = listOf(indexedSong),
                    failedCount = 0,
                    completed = true
                )
            )
        )
    }

    @Test
    fun `scan traversal does not fall back after cancellation`() {
        assertFalse(
            shouldFallbackToDocumentFileAfterTraversalFailure(
                CancellationException("scan cancelled")
            )
        )
        assertTrue(
            shouldFallbackToDocumentFileAfterTraversalFailure(
                IllegalStateException("provider failed")
            )
        )
    }

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
    fun `copyNearbySidecars preserves translated lyric sidecar`() {
        val sourceDir = tempFolder.newFolder("source-translated-lyrics")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song_trans.lrc").writeText("translated")

        val targetDir = tempFolder.newFolder("imports-translated-lyrics")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val copiedTranslated = File(targetDir, "imported_song_trans.lrc")
        assertTrue(copiedTranslated.exists())
        assertEquals("translated", copiedTranslated.readText())
    }

    @Test
    fun `copyNearbySidecars preserves translated lrc txt suffix`() {
        val sourceDir = tempFolder.newFolder("source-translated-lrc-txt")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song_trans.lrc.txt").writeText("translated")

        val targetDir = tempFolder.newFolder("imports-translated-lrc-txt")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val copiedTranslated = File(targetDir, "imported_song_trans.lrc.txt")
        assertTrue(copiedTranslated.exists())
        assertEquals("translated", copiedTranslated.readText())
    }

    @Test
    fun `copyNearbySidecars preserves romanized lyric sidecar`() {
        val sourceDir = tempFolder.newFolder("source-romanized-lyrics")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(File(sourceDir, "Lyrics").apply { mkdirs() }, "song_roma.lrc")
            .writeText("romanized")

        val targetDir = tempFolder.newFolder("imports-romanized-lyrics")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val copiedRomanized = File(targetDir, "imported_song_roma.lrc")
        assertTrue(copiedRomanized.exists())
        assertEquals("romanized", copiedRomanized.readText())
    }

    @Test
    fun `copyNearbySidecars preserves source directory lyric selection priority`() {
        val sourceDir = tempFolder.newFolder("source-lyrics-priority")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.txt").writeText("source original")
        File(sourceDir, "song_trans.txt").writeText("source translation")
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        File(lyricsDir, "song.lrc").writeText("nested original")
        File(lyricsDir, "song_trans.lrc").writeText("nested translation")

        val targetDir = tempFolder.newFolder("imports-lyrics-priority")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        assertEquals("source original", File(targetDir, "imported_song.txt").readText())
        assertFalse(File(targetDir, "imported_song.lrc").exists())
        assertEquals("source translation", File(targetDir, "imported_song_trans.txt").readText())
        assertFalse(File(targetDir, "imported_song_trans.lrc").exists())
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
    fun `buildQuickImportedSong preserves legacy stable id calculation`() {
        val sourceRef = "content://provider/audio/legacy-id"
        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = sourceRef,
                displayName = "legacy-id.flac",
                title = null,
                artist = null,
                album = null,
                durationMs = null
            ),
            unknownArtistLabel = "Unknown Artist"
        )
        val expectedId = MessageDigest.getInstance("SHA-256")
            .digest(sourceRef.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
            .toULong(16)
            .toLong()

        assertEquals(expectedId, song.id)
    }

    @Test
    fun `buildQuickImportedSong keeps numeric title metadata`() {
        val importedFile = tempFolder.newFile("fallback_name.flac")

        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = importedFile.absolutePath,
                displayName = importedFile.name,
                title = "88617",
                artist = "Artist",
                album = "Album",
                durationMs = 10_000L,
                localFile = importedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("88617", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
    }

    @Test
    fun `buildQuickImportedSong keeps numeric title metadata with spaces`() {
        val importedFile = tempFolder.newFile("fallback_name_with_space.flac")

        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = importedFile.absolutePath,
                displayName = importedFile.name,
                title = "886 17",
                artist = "Artist",
                album = "Album",
                durationMs = 10_000L,
                localFile = importedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("886 17", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
    }

    @Test
    fun `buildQuickImportedSong applies custom naming template when query metadata is missing`() {
        val previousTemplate = ManagedDownloadStorage.currentDownloadFileNameTemplate()
        ManagedDownloadStorage.updateDownloadFileNameTemplate("%album% - %title%")
        try {
            val importedFile = tempFolder.newFile("叶惠美 - 晴天.flac")

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

            assertEquals("晴天", song.name)
            assertEquals("叶惠美", song.album)
            assertEquals("Unknown Artist", song.artist)
        } finally {
            ManagedDownloadStorage.updateDownloadFileNameTemplate(previousTemplate)
        }
    }

    @Test
    fun `buildQuickImportedSong cleans legacy source prefix from filename album fallback`() {
        val previousTemplate = ManagedDownloadStorage.currentDownloadFileNameTemplate()
        ManagedDownloadStorage.updateDownloadFileNameTemplate(
            "%title% - %artist% - %album% - %source%"
        )
        try {
            val importedFile = tempFolder.newFile("茫 - 李润祺 - Netease茫 - netease.flac")

            val song = LocalAudioImportManager.buildQuickImportedSong(
                seed = QuickImportedSongSeed(
                    sourceRef = importedFile.absolutePath,
                    displayName = importedFile.name,
                    title = "content://provider/audio/123",
                    artist = "",
                    album = "",
                    durationMs = null,
                    localFile = importedFile
                ),
                unknownArtistLabel = "Unknown Artist"
            )

            assertEquals("茫", song.name)
            assertEquals("李润祺", song.artist)
            assertEquals("茫", song.album)
        } finally {
            ManagedDownloadStorage.updateDownloadFileNameTemplate(previousTemplate)
        }
    }

    @Test
    fun `buildQuickImportedSong does not treat source prefix as artist`() {
        val previousTemplate = ManagedDownloadStorage.currentDownloadFileNameTemplate()
        ManagedDownloadStorage.updateDownloadFileNameTemplate("%source% - %artist% - %title%")
        try {
            val importedFile = tempFolder.newFile("netease - Mrs. GREEN APPLE - lulu..flac")

            val song = LocalAudioImportManager.buildQuickImportedSong(
                seed = QuickImportedSongSeed(
                    sourceRef = importedFile.absolutePath,
                    displayName = importedFile.name,
                    title = "lulu.",
                    artist = "netease",
                    album = "",
                    durationMs = null,
                    localFile = importedFile
                ),
                unknownArtistLabel = "Unknown Artist"
            )

            assertEquals("lulu.", song.name)
            assertEquals("Mrs. GREEN APPLE", song.artist)
        } finally {
            ManagedDownloadStorage.updateDownloadFileNameTemplate(previousTemplate)
        }
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
    fun `quick SAF scan keeps display name metadata without local file resolution`() {
        val previousTemplate = ManagedDownloadStorage.currentDownloadFileNameTemplate()
        ManagedDownloadStorage.updateDownloadFileNameTemplate("%artist% - %title%")
        try {
            val song = LocalAudioImportManager.buildQuickImportedSong(
                seed = QuickImportedSongSeed(
                    sourceRef = "content://tree/music/document/track-42",
                    displayName = "Artist - Track 42.mp3",
                    title = null,
                    artist = null,
                    album = null,
                    durationMs = null
                ),
                unknownArtistLabel = "Unknown Artist"
            )

            assertEquals("Track 42", song.name)
            assertEquals("Artist", song.artist)
            assertEquals("content://tree/music/document/track-42", song.mediaUri)
            assertEquals("Artist - Track 42.mp3", song.localFileName)
        } finally {
            ManagedDownloadStorage.updateDownloadFileNameTemplate(previousTemplate)
        }
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

    @Test
    fun `mergeImportedSongMetadata keeps quick cover stable during background hydration`() {
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "/music/demo.flac",
                displayName = "demo.flac",
                title = "Demo",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000L,
                nearbyCoverUri = "file:///music/demo.jpg"
            ),
            unknownArtistLabel = "Unknown Artist"
        )
        val detailedSong = quickSong.copy(
            coverUrl = "file:///data/local_audio_covers/embedded.jpg"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quickSong, detailedSong)

        assertEquals("file:///music/demo.jpg", merged.coverUrl)
    }

    @Test
    fun `mergeImportedSongMetadata adopts cover discovered after a coverless quick scan`() {
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://tree/music/document/demo.flac",
                displayName = "demo.flac",
                title = "Demo",
                artist = "Artist",
                album = null,
                durationMs = 180_000L
            ),
            unknownArtistLabel = "Unknown Artist"
        )
        val detailedSong = quickSong.copy(
            coverUrl = "file:///data/local_audio_covers/demo.jpg",
            originalCoverUrl = "file:///data/local_audio_covers/demo.jpg"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quickSong, detailedSong)

        assertEquals("file:///data/local_audio_covers/demo.jpg", merged.coverUrl)
        assertEquals("file:///data/local_audio_covers/demo.jpg", merged.originalCoverUrl)
    }

    @Test
    fun `mergeImportedSongMetadata adopts resolved local path when quick scan only had content uri`() {
        val resolvedFile = tempFolder.newFile("resolved_demo.mp3")
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://tree/music/document/resolved_demo.mp3",
                displayName = "resolved_demo.mp3",
                title = "Quick Title",
                artist = "Quick Artist",
                album = null,
                durationMs = 0L
            ),
            unknownArtistLabel = "Unknown Artist"
        )
        val detailedSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = resolvedFile.absolutePath,
                displayName = resolvedFile.name,
                title = "Detailed Title",
                artist = "Detailed Artist",
                album = "Detailed Album",
                durationMs = 200_000L,
                localFile = resolvedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quickSong, detailedSong)

        assertEquals(detailedSong.id, merged.id)
        assertEquals(detailedSong.audioId, merged.audioId)
        assertEquals(resolvedFile.absolutePath, merged.localFilePath)
        assertEquals("content://tree/music/document/resolved_demo.mp3", merged.mediaUri)
        assertEquals("Detailed Title", merged.name)
        assertEquals("Detailed Artist", merged.artist)
    }

    @Test
    fun `mergeImportedSongMetadata keeps quick original metadata snapshots`() {
        val importedFile = tempFolder.newFile("snapshot_demo.mp3")
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = importedFile.absolutePath,
                displayName = importedFile.name,
                title = "Current Title",
                artist = "Current Artist",
                album = "Current Album",
                durationMs = 120_000L,
                localFile = importedFile
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(
            originalName = "Original Title",
            originalArtist = "Original Artist",
            originalCoverUrl = "file:///private/original-cover.jpg",
            originalLyric = "[00:01.00]original",
            originalTranslatedLyric = "[00:01.00]原文"
        )
        val detailedSong = quickSong.copy(
            name = "Scanned Title",
            artist = "Scanned Artist",
            coverUrl = "file:///cache/new-cover.jpg",
            originalName = "Scanned Original Title",
            originalArtist = "Scanned Original Artist",
            originalCoverUrl = "file:///cache/new-cover.jpg",
            originalLyric = "[00:01.00]scanned",
            originalTranslatedLyric = "[00:01.00]扫描"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(
            quickSong = quickSong,
            detailedSong = detailedSong
        )

        assertEquals("Original Title", merged.originalName)
        assertEquals("Original Artist", merged.originalArtist)
        assertEquals("file:///private/original-cover.jpg", merged.originalCoverUrl)
        assertEquals("[00:01.00]original", merged.originalLyric)
        assertEquals("[00:01.00]原文", merged.originalTranslatedLyric)
    }
}
