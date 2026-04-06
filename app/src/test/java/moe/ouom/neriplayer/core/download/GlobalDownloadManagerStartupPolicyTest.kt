package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalDownloadManagerStartupPolicyTest {

    @Test
    fun `startup scan is skipped only when snapshot and catalog are both ready`() {
        assertEquals(false, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = true, catalogReady = false))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(snapshotReady = false, catalogReady = false))
    }

    @Test
    fun `downloaded song catalog round trips through json cache`() {
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

        assertEquals(listOf(song), restored)
    }
}
