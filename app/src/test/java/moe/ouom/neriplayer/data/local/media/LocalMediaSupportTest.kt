package moe.ouom.neriplayer.data.local.media

import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaSupportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `prepareShareableFileInDirectory stages arbitrary local file outside download directory`() {
        val sourceFile = tempFolder.newFile("library_track.flac").apply {
            writeText("lossless-audio")
            setLastModified(2_000L)
        }
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }

        val stagedFile = LocalMediaSupport.prepareShareableFileInDirectory(sourceFile, shareDir)

        assertEquals(shareDir.canonicalPath, stagedFile.parentFile?.canonicalPath)
        assertEquals(
            LocalMediaSupport.shareableStageFileName(sourceFile),
            stagedFile.name
        )
        assertNotEquals(sourceFile.canonicalPath, stagedFile.canonicalPath)
        assertArrayEquals(sourceFile.readBytes(), stagedFile.readBytes())
        assertEquals(sourceFile.lastModified(), stagedFile.lastModified())
    }

    @Test
    fun `prepareShareableFileInDirectory reuses file already staged in share directory`() {
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }
        val stagedSource = File(shareDir, "track.mp3").apply {
            writeText("already-staged")
            setLastModified(3_000L)
        }

        val preparedFile = LocalMediaSupport.prepareShareableFileInDirectory(stagedSource, shareDir)

        assertEquals(stagedSource.canonicalPath, preparedFile.canonicalPath)
        assertEquals("already-staged", preparedFile.readText())
    }

    @Test
    fun `prepareShareableFileInDirectory refreshes stale staged copy`() {
        val sourceFile = tempFolder.newFile("album_track.mp3").apply {
            writeText("fresh-audio")
            setLastModified(4_000L)
        }
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }
        val stagedFile = File(shareDir, LocalMediaSupport.shareableStageFileName(sourceFile)).apply {
            writeText("stale-audio")
            setLastModified(1_000L)
        }

        val preparedFile = LocalMediaSupport.prepareShareableFileInDirectory(sourceFile, shareDir)

        assertEquals(stagedFile.canonicalPath, preparedFile.canonicalPath)
        assertEquals("fresh-audio", preparedFile.readText())
        assertEquals(sourceFile.lastModified(), preparedFile.lastModified())
    }

    @Test
    fun `prepareShareableFileInDirectory rejects directory input`() {
        val sourceDir = tempFolder.newFolder("not-a-file")
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            LocalMediaSupport.prepareShareableFileInDirectory(sourceDir, shareDir)
        }
    }

    @Test
    fun `resolveContentShareFallbackReference prefers explicit media content uri`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "file:///storage/emulated/0/Music/song.flac",
            mediaUri = "content://media/external/audio/media/42"
        )

        assertEquals("content://media/external/audio/media/42", fallbackUri)
    }

    @Test
    fun `resolveContentShareFallbackReference falls back to local content uri`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "content://media/external/audio/media/99",
            mediaUri = "/storage/emulated/0/Music/demo.flac"
        )

        assertEquals("content://media/external/audio/media/99", fallbackUri)
    }

    @Test
    fun `resolveContentShareFallbackReference returns null when no content uri is available`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "file:///storage/emulated/0/Music/song.flac",
            mediaUri = "/storage/emulated/0/Music/demo.flac"
        )

        assertNull(fallbackUri)
    }

    @Test
    fun `preferredLocalMediaReference prefers content media uri over direct file path`() {
        val preferred = preferredLocalMediaReference(
            localFilePath = "/storage/emulated/0/Download/Oto music/dependant.ogg",
            mediaUri = "content://media/external/audio/media/42"
        )

        assertEquals("content://media/external/audio/media/42", preferred)
    }

    @Test
    fun `selectQuickLocalMetadata falls back to defaults when query metadata is sparse`() {
        val selection = LocalMediaSupport.selectQuickLocalMetadata(
            title = "Track Name",
            queriedArtist = "   ",
            queriedAlbum = null,
            queriedDurationMs = null,
            unknownArtistLabel = "Unknown Artist",
            defaultAlbumLabel = "Local Files"
        )

        assertEquals("Track Name", selection.title)
        assertEquals("Unknown Artist", selection.artist)
        assertEquals("Local Files", selection.album)
        assertEquals(true, selection.usesFallbackAlbum)
        assertEquals(0L, selection.durationMs)
    }

    @Test
    fun `selectQuickLocalMetadata keeps explicit metadata and clamps negative duration`() {
        val selection = LocalMediaSupport.selectQuickLocalMetadata(
            title = "Track Name",
            queriedArtist = "Artist",
            queriedAlbum = "Album",
            queriedDurationMs = -42L,
            unknownArtistLabel = "Unknown Artist",
            defaultAlbumLabel = "Local Files"
        )

        assertEquals("Artist", selection.artist)
        assertEquals("Album", selection.album)
        assertEquals(false, selection.usesFallbackAlbum)
        assertEquals(0L, selection.durationMs)
    }

    @Test
    fun `findNearbyLyricFiles discovers original and translated sidecars separately`() {
        val sourceDir = tempFolder.newFolder("nearby-lyrics")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val original = File(sourceDir, "song.lrc").apply { writeText("original") }
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        val translated = File(lyricsDir, "song_trans.lrc").apply { writeText("translated") }

        val found = LocalMediaSupport.findNearbyLyricFiles(audioFile)

        assertEquals(original.canonicalPath, found.original?.canonicalPath)
        assertEquals(translated.canonicalPath, found.translated?.canonicalPath)
    }

    @Test
    fun `findNearbyCover retries when artwork appears after an empty lookup`() {
        val sourceDir = tempFolder.newFolder("nearby-cover-retry")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val originalDirectoryModified = sourceDir.lastModified()

        assertNull(LocalMediaSupport.findNearbyCover(audioFile))

        val coverFile = File(sourceDir, "song.jpg").apply { writeText("cover") }
        sourceDir.setLastModified(originalDirectoryModified)

        assertEquals(coverFile.canonicalPath, LocalMediaSupport.findNearbyCover(audioFile)?.canonicalPath)
    }

    @Test
    fun `fast lyric inspection reads direct file sidecars without content resolver`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.lrc").writeText("[00:01.00]local")
        val song = SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            channelId = "local"
        )

        val nearby = LocalMediaSupport.findNearbyLyricFiles(audioFile)
        assertEquals(
            File(sourceDir, "song.lrc").canonicalPath,
            nearby.original?.canonicalPath
        )
        assertEquals("[00:01.00]local", LocalMediaSupport.readTextFile(nearby.original!!))
        val lyrics = LocalMediaSupport.inspectLyricsFast(song)

        assertEquals("[00:01.00]local", lyrics.lyric)
    }

    @Test
    fun `fast lyric inspection keeps stored text and fills missing sidecar variants`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-variants")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song_trans.lrc").writeText("[00:02.00]translated")
        File(sourceDir, "song_roma.lrc").writeText("[00:03.00]romanized")
        val song = SongItem(
            id = 8L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            matchedLyric = "[00:01.00]stored",
            channelId = "local"
        )

        val lyrics = LocalMediaSupport.inspectLyricsFast(song)

        assertEquals("[00:01.00]stored", lyrics.lyric)
        assertEquals("[00:02.00]translated", lyrics.translatedLyric)
        assertEquals("[00:03.00]romanized", lyrics.romanizedLyric)
    }

    @Test
    fun `findNearbyLyricFiles keeps lrc txt compatibility for translated sidecars`() {
        val sourceDir = tempFolder.newFolder("nearby-lyrics-lrc-txt")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val translated = File(sourceDir, "song_trans.lrc.txt").apply { writeText("translated") }

        val found = LocalMediaSupport.findNearbyLyricFiles(audioFile)

        assertEquals(translated.canonicalPath, found.translated?.canonicalPath)
    }

    @Test
    fun `findNearbyLyricFiles discovers romanized sidecar in Lyrics directory`() {
        val sourceDir = tempFolder.newFolder("nearby-romanized-lyrics")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        val romanized = File(lyricsDir, "song_roma.lrc").apply { writeText("romanized") }

        val found = LocalMediaSupport.findNearbyLyricFiles(audioFile)

        assertEquals(romanized.canonicalPath, found.romanized?.canonicalPath)
    }

    @Test
    fun `resolveEffectiveLocalLyricContent falls back to embedded lyrics for blank sidecar`() {
        assertEquals(
            "[00:00.00]embedded",
            LocalMediaSupport.resolveEffectiveLocalLyricContent(
                sidecarContent = "  \n",
                embeddedContent = "[00:00.00]embedded"
            )
        )
        assertEquals(
            "[00:00.00]sidecar",
            LocalMediaSupport.resolveEffectiveLocalLyricContent(
                sidecarContent = "[00:00.00]sidecar",
                embeddedContent = "[00:00.00]embedded"
            )
        )
        assertEquals(
            null,
            LocalMediaSupport.resolveEffectiveLocalLyricContent(
                sidecarContent = "",
                embeddedContent = " "
            )
        )
    }

    @Test
    fun `resolveEffectiveLocalLyricPath hides unreadable sidecar references`() {
        assertNull(
            LocalMediaSupport.resolveEffectiveLocalLyricPath(
                reference = "content://lyrics/empty",
                content = "  \n"
            )
        )
        assertEquals(
            "content://lyrics/readable",
            LocalMediaSupport.resolveEffectiveLocalLyricPath(
                reference = "content://lyrics/readable",
                content = "[00:01.00]line"
            )
        )
    }

    @Test
    fun `resolveEffectiveLocalLyricPath ignores embedded fallback content`() {
        assertNull(
            LocalMediaSupport.resolveEffectiveLocalLyricPath(
                reference = "content://lyrics/empty",
                content = null
            )
        )
    }

    @Test
    fun `findNearbyLyricFiles keeps source directory priority over Lyrics fallback`() {
        val sourceDir = tempFolder.newFolder("nearby-lyrics-priority")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val original = File(sourceDir, "song.txt").apply { writeText("source original") }
        val translated = File(sourceDir, "song_trans.txt").apply { writeText("source translation") }
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        File(lyricsDir, "song.lrc").writeText("nested original")
        File(lyricsDir, "song_trans.lrc").writeText("nested translation")

        val found = LocalMediaSupport.findNearbyLyricFiles(audioFile)

        assertEquals(original.canonicalPath, found.original?.canonicalPath)
        assertEquals(translated.canonicalPath, found.translated?.canonicalPath)
    }

    @Test
    fun `local metadata sidecar keeps fields independent and preserves existing values`() {
        val existing = """
            {"matchedLyric":"matched","originalLyric":"original",
             "matchedRomanizedLyric":"romanized","custom":"keep"}
        """.trimIndent()
        val updated = LocalMediaSupport.buildLocalLyricsMetadataJson(
            existingRaw = existing,
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 0L,
                durationMs = 1_000L,
                coverUrl = null,
                matchedLyric = "new matched",
                matchedTranslatedLyric = "new translated"
            )
        )
        val parsed = LocalMediaSupport.parseLocalMetadataSidecar("/tmp/song.npmeta.json", updated)

        assertEquals("new matched", parsed?.matchedLyric)
        assertEquals("original", parsed?.originalLyric)
        assertEquals("new translated", parsed?.matchedTranslatedLyric)
        assertEquals(null, parsed?.originalTranslatedLyric)
        assertEquals("romanized", parsed?.matchedRomanizedLyric)
        assertEquals(true, org.json.JSONObject(updated).has("custom"))
    }

    @Test
    fun `local metadata sidecar accepts explicit blank lyric overrides`() {
        val updated = LocalMediaSupport.buildLocalLyricsMetadataJson(
            existingRaw = "{\"matchedLyric\":\"old\",\"originalLyric\":\"base\"}",
            song = SongItem(
                id = 2L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 0L,
                durationMs = 1_000L,
                coverUrl = null,
                matchedLyric = "",
                matchedTranslatedLyric = ""
            )
        )
        val parsed = LocalMediaSupport.parseLocalMetadataSidecar("/tmp/song.npmeta.json", updated)

        assertEquals("", parsed?.matchedLyric)
        assertEquals("base", parsed?.originalLyric)
        assertEquals("", parsed?.matchedTranslatedLyric)
    }
}
