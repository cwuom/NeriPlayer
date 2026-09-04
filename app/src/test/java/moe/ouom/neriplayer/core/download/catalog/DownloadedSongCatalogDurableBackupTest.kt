package moe.ouom.neriplayer.core.download.catalog

import java.io.File
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongCatalogDurableBackupTest {
    @Test
    fun `durable catalog round trip preserves complete song metadata`() {
        val song = DownloadedSong(
            id = 42L,
            name = "title",
            artist = "artist",
            album = "album",
            filePath = "content://downloads/audio/42.flac",
            fileSize = 123L,
            downloadTime = 456L,
            coverPath = "content://downloads/cover/42.jpg",
            coverUrl = "https://example.test/cover.jpg",
            matchedLyric = "matched",
            matchedTranslatedLyric = "translated",
            matchedRomanizedLyric = "romanized",
            matchedLyricSource = "netease",
            matchedSongId = "song-42",
            userLyricOffsetMs = 17L,
            customCoverUrl = "content://downloads/cover/custom.jpg",
            customName = "custom title",
            customArtist = "custom artist",
            originalName = "original title",
            originalArtist = "original artist",
            originalCoverUrl = "https://example.test/original.jpg",
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translated",
            originalRomanizedLyric = "original romanized",
            mediaUri = "content://downloads/audio/42.flac",
            durationMs = 180_000L,
            stableKey = "42|netease|",
            sourceIdentityAlbum = "netease",
            sourceMediaUri = "https://music.example/42",
            sourceChannelId = "netease",
            sourceAudioId = "42",
            sourceSubAudioId = "sq",
            sourcePlaylistContextId = "playlist-1"
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = serializeDownloadedSongsCatalog(
                cacheKey = "root",
                songs = listOf(song),
                includeOriginalLyrics = true
            ),
            expectedCacheKey = "root",
            includeOriginalLyrics = true
        )

        assertEquals(listOf(song), restored)
    }

    @Test
    fun `legacy payload reader can recover original lyrics when fields are present`() {
        val song = song().copy(
            originalLyric = "original",
            originalTranslatedLyric = "translated",
            originalRomanizedLyric = "romanized"
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = serializeDownloadedSongsCatalog(
                cacheKey = "root",
                songs = listOf(song),
                includeOriginalLyrics = true
            ),
            expectedCacheKey = "root",
            includeOriginalLyrics = true
        )

        assertEquals(song, restored?.single())
    }

    @Test
    fun `preview merge keeps complete backup metadata and adds current locator`() {
        val backup = song().copy(
            filePath = "content://old/audio",
            mediaUri = "content://old/audio",
            coverPath = "content://old/covers/song.jpg",
            originalLyric = "original"
        )
        val preview = song().copy(
            filePath = "content://new/audio",
            mediaUri = "content://new/audio",
            coverPath = "content://new/covers/song.jpg",
            fileSize = 999L,
            downloadTime = 888L
        )

        val merged = mergeCatalogBackupWithPreviews(
            backupSongs = listOf(backup),
            previewSongs = listOf(preview)
        )

        assertEquals(1, merged.size)
        assertEquals("content://new/audio", merged.single().filePath)
        assertEquals(999L, merged.single().fileSize)
        assertEquals("original", merged.single().originalLyric)
        assertEquals("content://new/covers/song.jpg", merged.single().coverPath)
        assertEquals(backup.name, merged.single().name)
    }

    @Test
    fun `legacy full payload wins over a minimal room preview`() {
        val legacy = song().copy(
            id = 42L,
            album = "Legacy album",
            coverPath = "content://covers/legacy.jpg",
            originalLyric = "legacy lyric",
            sourceChannelId = "netease",
            sourceAudioId = "42",
            stableKey = "42|netease|"
        )
        val roomPreview = DownloadedSong(
            id = 0L,
            name = "Preview title",
            artist = "Preview artist",
            album = "",
            filePath = "content://downloads/current.flac",
            fileSize = 99L,
            downloadTime = 100L,
            mediaUri = "content://downloads/current.flac",
            stableKey = "42|netease|"
        )

        val restored = mergeCatalogBackupWithPreviews(
            backupSongs = listOf(legacy),
            previewSongs = listOf(roomPreview)
        ).single()

        assertEquals(42L, restored.id)
        assertEquals("Legacy album", restored.album)
        assertEquals("legacy lyric", restored.originalLyric)
        assertEquals("content://downloads/current.flac", restored.filePath)
        assertEquals("netease", restored.sourceChannelId)
        assertEquals("42", restored.sourceAudioId)
    }

    @Test
    fun `empty full payload does not hide room previews`() {
        val preview = song().copy(filePath = "content://downloads/preview.flac")

        assertEquals(
            listOf(preview),
            mergeCatalogBackupWithPreviews(
                backupSongs = emptyList(),
                previewSongs = listOf(preview)
            )
        )
    }

    @Test
    fun `preview without stable key is consumed by its exact locator`() {
        val backup = song().copy(
            stableKey = null,
            filePath = "content://old-tree/audio/song.flac"
        )
        val preview = song().copy(
            stableKey = null,
            filePath = "content://new-tree/audio/song.flac",
            mediaUri = "content://old-tree/audio/song.flac"
        )

        val merged = mergeCatalogBackupWithPreviews(
            backupSongs = listOf(backup),
            previewSongs = listOf(preview)
        )

        assertEquals(1, merged.size)
        assertEquals("content://new-tree/audio/song.flac", merged.single().filePath)
    }

    @Test
    fun `duplicate preview rows are not appended twice`() {
        val preview = song().copy(filePath = "content://downloads/song.flac")

        val merged = mergeCatalogBackupWithPreviews(
            backupSongs = listOf(preview.copy(filePath = "content://old/song.flac")),
            previewSongs = listOf(preview, preview.copy(name = "duplicate row"))
        )

        assertEquals(1, merged.size)
        assertEquals("content://downloads/song.flac", merged.single().filePath)
    }

    @Test
    fun `large catalog merge keeps every unmatched preview once`() {
        val backups = (0 until 1_000).map { index ->
            song().copy(
                id = index.toLong(),
                stableKey = "stable-$index",
                filePath = "content://old/$index.flac"
            )
        }
        val previews = (0 until 1_000).map { index ->
            song().copy(
                id = index.toLong(),
                stableKey = "stable-$index",
                filePath = "content://new/$index.flac"
            )
        } + song().copy(
            id = 2_000L,
            stableKey = "stable-extra",
            filePath = "content://new/extra.flac"
        )

        val merged = mergeCatalogBackupWithPreviews(backups, previews)

        assertEquals(1_001, merged.size)
        assertEquals("content://new/999.flac", merged[999].filePath)
        assertEquals("content://new/extra.flac", merged.last().filePath)
    }

    @Test
    fun `failed backup write does not replace an existing payload`() {
        val temporaryDirectory = Files.createTempDirectory("neri-catalog-backup").toFile()
        val existing = File(temporaryDirectory, "backup.json")
        existing.writeText("old-payload")

        try {
            assertFalse(
                writeManagedCatalogBackupFile(
                    file = existing,
                    rootKey = "root",
                    songs = listOf(song()),
                    writeAtomically = { _, _ ->
                        error("simulated atomic write failure")
                    }
                )
            )
            assertEquals("old-payload", existing.readText())
            assertTrue(existing.isFile)
        } finally {
            existing.delete()
            temporaryDirectory.delete()
        }
    }

    private fun song(): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            filePath = "content://audio",
            fileSize = 1L,
            downloadTime = 2L,
            stableKey = "1|local|"
        )
    }
}
