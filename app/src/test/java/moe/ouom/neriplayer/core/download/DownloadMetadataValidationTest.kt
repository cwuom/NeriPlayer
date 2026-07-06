package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

/**
 * 回归测试: #121 (SD 卡 JSON 元数据写入失败) 和 #126 (本地存在同名歌曲时重复下载)
 */
class DownloadMetadataValidationTest {

    // --- shouldRepairMetadataLessManagedDownload 测试 ---

    @Test
    fun `unfinalized metadata only treats explicit false as incomplete`() {
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                ManagedDownloadStorage.DownloadedAudioMetadata(downloadFinalized = false)
            )
        )
        assertFalse(
            isUnfinalizedDownloadedMetadata(
                ManagedDownloadStorage.DownloadedAudioMetadata(downloadFinalized = true)
            )
        )
        assertFalse(
            isUnfinalizedDownloadedMetadata(
                ManagedDownloadStorage.DownloadedAudioMetadata(downloadFinalized = null)
            )
        )
        assertFalse(isUnfinalizedDownloadedMetadata(null))
    }

    @Test
    fun `shouldRepair returns true when actual title is null`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = null,
                actualArtist = "Artist",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when actual artist is null`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = null,
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when actual duration is zero`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 0L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when both title and artist are blank`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "",
                actualArtist = "",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns false when all fields match`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns false when title matches any expected variant`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Custom Name", "Original Name", "Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Original Name",
                actualArtist = "Artist",
                actualDurationMs = 179_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns false when duration difference is within tolerance`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 183_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when duration difference exceeds tolerance`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 200_000L
            )
        )
    }

    @Test
    fun `shouldRepair is case insensitive`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist Name"),
                expectedDurationMs = 180_000L,
                actualTitle = "song title",
                actualArtist = "artist name",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair normalizes whitespace`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song  Title"),
                expectedArtists = setOf("Artist  Name"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist Name",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when title does not match any expected`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title", "Custom Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Completely Different Song",
                actualArtist = "Artist",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair skips duration check when expected is zero`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 0L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 999_999L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when expected titles is empty`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = emptySet(),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 180_000L
            )
        )
    }

    @Test
    fun `shouldRepair returns true when expected artists is empty`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = emptySet(),
                expectedDurationMs = 180_000L,
                actualTitle = "Song Title",
                actualArtist = "Artist",
                actualDurationMs = 180_000L
            )
        )
    }

    // --- buildExpectedDownloadTitles / buildExpectedDownloadArtists ---

    @Test
    fun `buildExpectedDownloadTitles includes custom and original names`() {
        val song = testSong(name = "Song", customName = "Custom Song", originalName = "Original Song")
        val titles = buildExpectedDownloadTitles(song)
        assertTrue("Custom Song" in titles)
        assertTrue("Song" in titles)
        assertTrue("Original Song" in titles)
    }

    @Test
    fun `buildExpectedDownloadTitles uses name when customName is null`() {
        val song = testSong(name = "Song", customName = null)
        val titles = buildExpectedDownloadTitles(song)
        assertTrue("Song" in titles)
    }

    @Test
    fun `buildExpectedDownloadArtists includes custom and original artists`() {
        val song = testSong(artist = "Artist", customArtist = "Custom Artist", originalArtist = "Original Artist")
        val artists = buildExpectedDownloadArtists(song)
        assertTrue("Custom Artist" in artists)
        assertTrue("Artist" in artists)
        assertTrue("Original Artist" in artists)
    }

    // --- 回归: SAF 无法读取标签时不应回滚 (#126) ---

    @Test
    fun `shouldRepair returns true for all-null metadata simulating SAF read failure`() {
        assertTrue(
            "当无法读取音频标签时 shouldRepair 应返回 true，由调用方通过文件名匹配决定是否保留",
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("Song Title"),
                expectedArtists = setOf("Artist"),
                expectedDurationMs = 180_000L,
                actualTitle = null,
                actualArtist = null,
                actualDurationMs = 0L
            )
        )
    }

    private fun testSong(
        name: String = "Test Song",
        artist: String = "Test Artist",
        customName: String? = null,
        customArtist: String? = null,
        originalName: String? = null,
        originalArtist: String? = null
    ): SongItem = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = "",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist
    )
}
