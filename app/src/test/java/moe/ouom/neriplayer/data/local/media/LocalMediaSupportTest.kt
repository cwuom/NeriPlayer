package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import java.io.File
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LocalMediaSupportTest {

    @Test
    fun `sidecar name matching accepts provider numbering before extension`() {
        assertTrue(
            LocalMediaSupport.sidecarNameMatches(
                actualName = "言って。 - Neri - 言って。 - netease.mp3.npmeta (1).json",
                canonicalName = "言って。 - Neri - 言って。 - netease.mp3.npmeta.json"
            )
        )
        assertTrue(
            LocalMediaSupport.sidecarNameMatches(
                actualName = "言って。 - Neri - 言って。 - netease-837d410c (1).jpg",
                canonicalName = "言って。 - Neri - 言って。 - netease-837d410c.jpg"
            )
        )
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `embedded lyric inspection reads text without decoding artwork or audio properties`() {
        assertFalse(embeddedLyricsReadOptions.includeEmbeddedAssets)
        assertTrue(embeddedLyricsReadOptions.includeEmbeddedLyrics)
        assertFalse(embeddedLyricsReadOptions.includeAudioProperties)
    }

    @Test
    fun `embedded lyric metadata counts as a resolved local lyrics source`() {
        assertTrue(
            isLocalLyricsSourceResolved(
                scannedSource = false,
                embeddedSource = true
            )
        )
        assertTrue(
            isLocalLyricsSourceResolved(
                scannedSource = true,
                embeddedSource = false
            )
        )
        assertFalse(
            isLocalLyricsSourceResolved(
                scannedSource = false,
                embeddedSource = false
            )
        )
    }

    @Test
    fun `media store uri skips direct sidecar and parent path access`() {
        assertEquals(
            true,
            isMediaStoreAuthority("media")
        )
        assertEquals(
            true,
            isMediaStoreAuthority("com.android.providers.media.documents")
        )
        assertEquals(
            false,
            isMediaStoreAuthority("com.android.externalstorage.documents")
        )
    }

    @Test
    fun `media store sidecar references are rejected before provider access`() {
        assertTrue(
            isMediaStoreSidecarReference(
                "content://media/external_primary/file/61458"
            )
        )
        assertTrue(
            isMediaStoreSidecarReference(
                "content://com.android.providers.media.documents/document/61458"
            )
        )
        assertFalse(
            isMediaStoreSidecarReference(
                "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.npmeta.json"
            )
        )
        assertFalse(
            isMediaStoreSidecarReference("/storage/emulated/0/Music/song.npmeta.json")
        )
    }

    @Test
    fun `media store album art references are trusted as indexed local covers`() {
        assertTrue(
            isMediaStoreCoverReference(
                "content://media/external/audio/albumart/42"
            )
        )
        assertFalse(
            isMediaStoreCoverReference(
                "content://media/external/audio/media/42"
            )
        )
        assertFalse(
            isMediaStoreCoverReference(
                "content://com.example.documents/document/cover.jpg"
            )
        )
    }

    @Test
    fun `media store cover sidecar is skipped only when no local file was resolved`() {
        val localFile = tempFolder.newFile("resolved-song.mp3")

        assertTrue(
            LocalMediaSupport.shouldSkipLocalCoverSidecar(
                sourceReference = "content://media/external/audio/media/1",
                file = null
            )
        )
        assertFalse(
            LocalMediaSupport.shouldSkipLocalCoverSidecar(
                sourceReference = "content://media/external/audio/media/1",
                file = localFile
            )
        )
        assertFalse(
            LocalMediaSupport.shouldSkipLocalCoverSidecar(
                sourceReference = "content://com.example.documents/document/1",
                file = null
            )
        )
    }

    @Test
    fun `local cover sidecar naming matches downloaded cover naming`() {
        val baseName = "Artist - Song - netease"
        val stableKey = "1|netease|"

        assertEquals(
            AudioDownloadManager.buildCoverSidecarFileName(baseName, stableKey),
            LocalMediaSupport.localCoverSidecarName(
                baseName = baseName,
                extension = "jpg",
                stableIdentityKey = stableKey
            )
        )
    }

    @Test
    fun `local cover sidecar naming keeps legacy name without identity`() {
        assertEquals(
            "Artist - Song.jpg",
            LocalMediaSupport.localCoverSidecarName(
                baseName = "Artist - Song",
                extension = "jpg",
                stableIdentityKey = null
            )
        )
    }

    @Test
    fun `managed sidecar directory matching reuses numbered directory`() {
        assertTrue(
            LocalMediaSupport.isManagedSidecarDirectoryName(
                actualName = "Lyrics (1)",
                desiredName = "Lyrics"
            )
        )
        assertFalse(
            LocalMediaSupport.isManagedSidecarDirectoryName(
                actualName = "Lyrics (draft)",
                desiredName = "Lyrics"
            )
        )
    }

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
    fun `embedded cover cache sampling bounds oversized images`() {
        assertEquals(
            8,
            LocalMediaSupport.embeddedCoverCacheSampleSize(
                width = 4096,
                height = 3072
            )
        )
    }

    @Test
    fun `embedded cover cache sampling keeps small images unchanged`() {
        assertEquals(
            1,
            LocalMediaSupport.embeddedCoverCacheSampleSize(
                width = 512,
                height = 320
            )
        )
    }

    @Test
    fun `MediaStore album art uri uses the stable external audio endpoint`() {
        assertEquals(
            "content://media/external/audio/albumart/42",
            LocalMediaSupport.mediaStoreAlbumArtUri(42L)
        )
    }

    @Test
    fun `absolute metadata sidecar probe rejects inaccessible MediaStore paths`() {
        val deniedMetadata = File(
            "/storage/emulated/0/neriplayer-download/song.mp3.npmeta.json"
        )

        assertFalse(
            LocalMediaSupport.shouldProbeAbsoluteMetadataSidecar(
                sourceUri = Uri.parse("content://media/external/audio/media/42"),
                metadataFile = deniedMetadata
            )
        )
    }

    @Test
    fun `absolute metadata sidecar probe skips shared storage SAF paths`() {
        val sharedStorageMetadata = File(
            "/storage/emulated/0/neriplayer-download/song.mp3.npmeta.json"
        )

        assertFalse(
            LocalMediaSupport.shouldProbeAbsoluteMetadataSidecar(
                sourceUri = Uri.parse(
                    "content://com.android.externalstorage.documents/" +
                        "tree/primary%3Aneriplayer-download/document/" +
                        "primary%3Aneriplayer-download%2Fsong.mp3"
                ),
                metadataFile = sharedStorageMetadata
            )
        )
    }

    @Test
    fun `external storage documents keep sidecar writes on the document protocol`() {
        val safUri = mock(Uri::class.java)
        val fileUri = mock(Uri::class.java)
        `when`(safUri.scheme).thenReturn("content")
        `when`(safUri.authority).thenReturn("com.android.externalstorage.documents")
        `when`(fileUri.scheme).thenReturn("file")

        assertTrue(
            shouldUseDocumentSidecarMutation(safUri)
        )
        assertFalse(
            shouldUseDocumentSidecarMutation(fileUri)
        )
    }

    @Test
    fun `retriever probe skips MediaStore rows without a readable file`() {
        assertFalse(
            LocalMediaSupport.shouldProbeRetrieverTextMetadata(
                sourceReference = "content://media/external/audio/media/42",
                file = null
            )
        )
        assertTrue(
            LocalMediaSupport.shouldProbeRetrieverTextMetadata(
                sourceReference = "content://com.example.documents/audio/42",
                file = null
            )
        )
    }

    @Test
    fun `embedded cover cache lookup keeps both local path and SAF source`() {
        val song = SongItem(
            id = 12L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            localFilePath = "/storage/emulated/0/Music/song.flac",
            mediaUri = "content://com.example.documents/document/song.flac",
            channelId = "local"
        )

        assertEquals(
            listOf(
                "/storage/emulated/0/Music/song.flac",
                "content://com.example.documents/document/song.flac"
            ),
            LocalMediaSupport.embeddedCoverCacheLookupKeys(song)
        )
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
    fun `selectQuickLocalMetadata ignores provider placeholders`() {
        val selection = LocalMediaSupport.selectQuickLocalMetadata(
            title = "Track Name",
            queriedArtist = "<unknown>",
            queriedAlbum = "unknown album",
            queriedDurationMs = 12_000L,
            unknownArtistLabel = "Unknown Artist",
            defaultAlbumLabel = "Local Files"
        )

        assertEquals("Unknown Artist", selection.artist)
        assertEquals("Local Files", selection.album)
        assertEquals(true, selection.usesFallbackAlbum)
        assertEquals(12_000L, selection.durationMs)
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
    fun `findNearbyCover prefers song specific Covers artwork over generic cover`() {
        val sourceDir = tempFolder.newFolder("nearby-cover-specific")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "cover.jpg").writeText("generic")
        val coversDir = File(sourceDir, "Covers").apply { mkdirs() }
        val specific = File(coversDir, "song.png").apply { writeText("specific") }

        assertEquals(
            specific.canonicalPath,
            LocalMediaSupport.findNearbyCover(audioFile)?.canonicalPath
        )
    }

    @Test
    fun `clearing cover lookup cache observes a replacement with a new preferred name`() {
        val sourceDir = tempFolder.newFolder("nearby-cover-clear")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val generic = File(sourceDir, "cover.jpg").apply { writeText("generic") }

        assertEquals(generic.canonicalPath, LocalMediaSupport.findNearbyCover(audioFile)?.canonicalPath)

        val specific = File(sourceDir, "song.jpg").apply { writeText("specific") }
        LocalMediaSupport.clearCoverLookupCache()

        assertEquals(specific.canonicalPath, LocalMediaSupport.findNearbyCover(audioFile)?.canonicalPath)
    }

    @Test
    fun `findNearbyCover accepts a lowercase covers directory`() {
        val sourceDir = tempFolder.newFolder("nearby-cover-lowercase")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val coversDir = File(sourceDir, "covers").apply { mkdirs() }
        val specific = File(coversDir, "song.jpg").apply { writeText("specific") }

        assertEquals(
            specific.canonicalPath,
            LocalMediaSupport.findNearbyCover(audioFile)?.canonicalPath
        )
    }

    @Test
    fun `invalid local cover reference is rejected so fallback can continue`() {
        val invalidCover = tempFolder.newFile("song.jpg").apply {
            writeText("not an image")
        }

        assertFalse(
            isUsableCoverReference(
                context = mock(Context::class.java),
                reference = invalidCover.toURI().toString()
            )
        )
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
    fun `fast lyric inspection falls back to readable local sidecars when content source has none`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-content-fallback")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.lrc").writeText("content fallback original")
        File(sourceDir, "song_trans.lrc").writeText("content fallback translation")
        val song = SongItem(
            id = 81L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/81",
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            channelId = "local"
        )

        val lyrics = LocalMediaSupport.inspectLyricsFast(
            song = song,
            includeStoredFallback = false
        )

        assertEquals("content fallback original", lyrics.lyric)
        assertEquals("content fallback translation", lyrics.translatedLyric)
        assertTrue(lyrics.hasOriginalSidecar)
        assertTrue(lyrics.hasTranslatedSidecar)
    }

    @Test
    fun `fast lyric inspection prefers all sidecar variants over stored lyrics`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-sidecar-priority")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.lrc").writeText("source fallback original")
        File(sourceDir, "song_trans.lrc").writeText("source fallback translation")
        File(sourceDir, "song_roma.lrc").writeText("source fallback romanized")
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        File(lyricsDir, "song.lrc").writeText("[00:01.00]sidecar original")
        File(lyricsDir, "song_trans.lrc").writeText("[00:02.00]sidecar translation")
        File(lyricsDir, "song_roma.lrc").writeText("[00:03.00]sidecar romanized")
        val song = SongItem(
            id = 10L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            matchedLyric = "stored original",
            matchedTranslatedLyric = "stored translation",
            matchedRomanizedLyric = "stored romanized",
            channelId = "local"
        )

        val lyrics = LocalMediaSupport.inspectLyricsFast(song)

        assertEquals("[00:01.00]sidecar original", lyrics.lyric)
        assertEquals("[00:02.00]sidecar translation", lyrics.translatedLyric)
        assertEquals("[00:03.00]sidecar romanized", lyrics.romanizedLyric)
        assertEquals(true, lyrics.hasOriginalSidecar)
        assertEquals(true, lyrics.hasTranslatedSidecar)
        assertEquals(true, lyrics.hasRomanizedSidecar)
    }

    @Test
    fun `fast lyric inspection refreshes after sidecar edit deletion and recreation`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-refresh")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val original = File(sourceDir, "song.lrc")
        val translated = File(sourceDir, "song_trans.lrc")
        val romanized = File(sourceDir, "song_roma.lrc")
        original.writeText("[00:01.00]first original")
        translated.writeText("[00:02.00]first translation")
        romanized.writeText("[00:03.00]first romanized")
        val song = SongItem(
            id = 11L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            matchedLyric = "stored original",
            matchedTranslatedLyric = "stored translation",
            matchedRomanizedLyric = "stored romanized",
            channelId = "local"
        )

        val first = LocalMediaSupport.inspectLyricsFast(song)
        assertEquals("[00:01.00]first original", first.lyric)
        assertEquals("[00:02.00]first translation", first.translatedLyric)
        assertEquals("[00:03.00]first romanized", first.romanizedLyric)

        original.writeText("[00:11.00]updated original")
        translated.writeText("[00:12.00]updated translation")
        romanized.writeText("[00:13.00]updated romanized")
        original.setLastModified(11_000L)
        translated.setLastModified(12_000L)
        romanized.setLastModified(13_000L)

        val updated = LocalMediaSupport.inspectLyricsFast(song)
        assertEquals("[00:11.00]updated original", updated.lyric)
        assertEquals("[00:12.00]updated translation", updated.translatedLyric)
        assertEquals("[00:13.00]updated romanized", updated.romanizedLyric)

        assertEquals(true, original.delete())
        assertEquals(true, translated.delete())
        assertEquals(true, romanized.delete())

        val afterDeletion = LocalMediaSupport.inspectLyricsFast(song)
        assertEquals("stored original", afterDeletion.lyric)
        assertEquals("stored translation", afterDeletion.translatedLyric)
        assertEquals("stored romanized", afterDeletion.romanizedLyric)
        assertEquals(false, afterDeletion.hasOriginalSidecar)
        assertEquals(false, afterDeletion.hasTranslatedSidecar)
        assertEquals(false, afterDeletion.hasRomanizedSidecar)

        original.writeText("[00:21.00]recreated original")
        translated.writeText("[00:22.00]recreated translation")
        romanized.writeText("[00:23.00]recreated romanized")
        LocalMediaSupport.clearLyricsLookupCache()

        val recreated = LocalMediaSupport.inspectLyricsFast(song)
        assertEquals("[00:21.00]recreated original", recreated.lyric)
        assertEquals("[00:22.00]recreated translation", recreated.translatedLyric)
        assertEquals("[00:23.00]recreated romanized", recreated.romanizedLyric)
        assertEquals(true, recreated.hasOriginalSidecar)
        assertEquals(true, recreated.hasTranslatedSidecar)
        assertEquals(true, recreated.hasRomanizedSidecar)
    }

    @Test
    fun `fast lyric inspection without stored fallback clears deleted sidecars`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-clear")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.lrc").writeText("original")
        val song = SongItem(
            id = 13L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            matchedLyric = "stale network lyric",
            channelId = "local"
        )

        assertEquals(
            "original",
            LocalMediaSupport.inspectLyricsFast(
                song = song,
                includeStoredFallback = false
            ).lyric
        )
        File(sourceDir, "song.lrc").delete()

        val cleared = LocalMediaSupport.inspectLyricsFast(
            song = song,
            includeStoredFallback = false
        )
        assertNull(cleared.lyric)
        assertEquals(true, cleared.sourceResolved)
    }

    @Test
    fun `clearing lyric lookup cache observes sidecar created after an empty lookup`() {
        val sourceDir = tempFolder.newFolder("download-lyrics-cache")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val song = SongItem(
            id = 9L,
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

        LocalMediaSupport.clearLyricsLookupCache()
        assertNull(LocalMediaSupport.inspectLyricsFast(song).lyric)
        File(sourceDir, "song.lrc").writeText("[00:01.00]downloaded")
        LocalMediaSupport.clearLyricsLookupCache()

        assertEquals(
            "[00:01.00]downloaded",
            LocalMediaSupport.inspectLyricsFast(song).lyric
        )
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
    fun `resolveEffectiveLocalLyricContent keeps blank sidecar as an explicit clear`() {
        assertEquals(
            "  \n",
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
            "",
            LocalMediaSupport.resolveEffectiveLocalLyricContent(
                sidecarContent = "",
                embeddedContent = " "
            )
        )
    }

    @Test
    fun `resolveEffectiveLocalLyricPath keeps readable empty sidecar references`() {
        assertEquals(
            "content://lyrics/empty",
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
    fun `findNearbyLyricFiles keeps Lyrics directory priority over source fallback`() {
        val sourceDir = tempFolder.newFolder("nearby-lyrics-priority")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val original = File(sourceDir, "song.txt").apply { writeText("source original") }
        val translated = File(sourceDir, "song_trans.txt").apply { writeText("source translation") }
        val lyricsDir = File(sourceDir, "Lyrics").apply { mkdirs() }
        File(lyricsDir, "song.lrc").writeText("nested original")
        File(lyricsDir, "song_trans.lrc").writeText("nested translation")

        val found = LocalMediaSupport.findNearbyLyricFiles(audioFile)

        assertEquals(File(lyricsDir, "song.lrc").canonicalPath, found.original?.canonicalPath)
        assertEquals(
            File(lyricsDir, "song_trans.lrc").canonicalPath,
            found.translated?.canonicalPath
        )
    }

    @Test
    fun `recreating deleted sidecars keeps an existing Lyrics directory authoritative`() {
        val sourceDir = tempFolder.newFolder("lyrics-recreate-target")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val lyricsDirectory = File(sourceDir, "Lyrics").apply { mkdirs() }

        val target = LocalMediaSupport.resolveLocalLyricsTargetDirectory(
            file = audioFile,
            nearby = NearbyLyricFiles(null, null, null)
        )

        assertEquals(lyricsDirectory.canonicalPath, target.canonicalPath)
    }

    @Test
    fun `recreating managed download sidecars uses the managed Lyrics root`() {
        val managedRoot = tempFolder.newFolder("managed-download")
        val audioDirectory = File(managedRoot, "Artist").apply { mkdirs() }
        val audioFile = File(audioDirectory, "song.flac").apply { writeText("audio") }
        val expectedLyricsDirectory = File(managedRoot, "Lyrics")

        val target = LocalMediaSupport.resolveLocalLyricsTargetDirectory(
            file = audioFile,
            nearby = NearbyLyricFiles(null, null, null),
            legacyRoot = managedRoot,
            isLegacyDownload = true
        )

        assertEquals(expectedLyricsDirectory.canonicalPath, target.canonicalPath)
    }

    @Test
    fun `fast lyric cache follows an updated stored lyric model`() {
        val sourceDir = tempFolder.newFolder("fast-lyrics-model-state")
        val audioFile = File(sourceDir, "song.flac").apply { writeText("audio") }
        val song = SongItem(
            id = 14L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = audioFile.toURI().toString(),
            localFileName = audioFile.name,
            localFilePath = audioFile.absolutePath,
            matchedLyric = "first",
            channelId = "local"
        )

        assertEquals("first", LocalMediaSupport.inspectLyricsFast(song).lyric)
        assertEquals(
            "second",
            LocalMediaSupport.inspectLyricsFast(song.copy(matchedLyric = "second")).lyric
        )
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
    fun `download metadata sidecar preserves identity fields for local scans`() {
        val parsed = LocalMediaSupport.parseLocalMetadataSidecar(
            "/tmp/song.mp3.npmeta.json",
            """
                {
                  "name":"好想爱这个世界啊",
                  "artist":"华晨宇",
                  "album":"neriplayer-download",
                  "originalName":"旧标题",
                  "originalArtist":"旧歌手",
                  "identityAlbum":"旧专辑",
                  "coverPath":"content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download/document/primary%3Aneriplayer-download%2FCovers%2Fsong.jpg"
                }
            """.trimIndent()
        )

        assertEquals("好想爱这个世界啊", parsed?.name)
        assertEquals("华晨宇", parsed?.artist)
        assertEquals("neriplayer-download", parsed?.album)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download/document/primary%3Aneriplayer-download%2FCovers%2Fsong.jpg",
            parsed?.coverPath
        )
    }

    @Test
    fun `fast local metadata sidecar lookup avoids opening the audio file`() {
        val audio = tempFolder.newFile("song.mp3")
        File(audio.parentFile, audio.name + ".npmeta.json").writeText(
            """
                {"name":"好想爱这个世界啊","artist":"华晨宇",
                 "album":"neriplayer-download","channelId":"netease",
                 "audioId":"123"}
            """.trimIndent()
        )
        val song = SongItem(
            id = 1L,
            name = audio.nameWithoutExtension,
            artist = "未知艺术家",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = audio.absolutePath,
            localFileName = audio.name,
            localFilePath = audio.absolutePath
        )

        val metadata = LocalMediaSupport.readLocalMetadataSidecarFast(
            context = mock(Context::class.java),
            song = song
        )

        assertEquals("华晨宇", metadata?.artist)
        assertEquals("netease", metadata?.channelId)
        assertEquals("123", metadata?.audioId)
    }

    @Test
    fun `fast local metadata lookup falls back when an indexed reference was deleted`() {
        val audio = tempFolder.newFile("song.mp3")
        val adjacent = File(audio.parentFile, audio.name + ".npmeta.json").apply {
            writeText("""{"artist":"华晨宇","name":"好想爱这个世界啊"}""")
        }
        val song = SongItem(
            id = 1L,
            name = audio.nameWithoutExtension,
            artist = "未知艺术家",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = audio.absolutePath,
            localFileName = audio.name,
            localFilePath = audio.absolutePath
        )

        val metadata = LocalMediaSupport.readLocalMetadataSidecarFast(
            context = mock(Context::class.java),
            song = song,
            metadataReference = File(audio.parentFile, "deleted.npmeta.json").absolutePath
        )

        assertEquals(adjacent.absolutePath, metadata?.reference)
        assertEquals("华晨宇", metadata?.artist)
    }

    @Test
    fun `fast local metadata lookup ignores stale MediaStore reference`() {
        val audio = tempFolder.newFile("song.mp3")
        val adjacent = File(audio.parentFile, audio.name + ".npmeta.json").apply {
            writeText("""{"artist":"华晨宇","name":"好想爱这个世界啊"}""")
        }
        val song = SongItem(
            id = 1L,
            name = audio.nameWithoutExtension,
            artist = "未知艺术家",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/42",
            localFileName = audio.name,
            localFilePath = audio.absolutePath
        )

        val metadata = LocalMediaSupport.readLocalMetadataSidecarFast(
            context = mock(Context::class.java),
            song = song,
            metadataReference = "content://media/external_primary/file/61458"
        )

        assertEquals(adjacent.absolutePath, metadata?.reference)
        assertEquals("华晨宇", metadata?.artist)
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
