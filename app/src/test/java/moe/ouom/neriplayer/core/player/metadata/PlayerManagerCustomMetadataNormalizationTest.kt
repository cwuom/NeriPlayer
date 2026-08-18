package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerCustomMetadataNormalizationTest {

    @Test
    fun `restore original title still needs custom value when base title was replaced`() {
        val baseName = "搜索匹配后的标题"
        val originalName = "原始标题"
        val normalized = normalizeCustomMetadataValue(
            desiredValue = originalName,
            baseValue = baseName
        )

        assertEquals(originalName, normalized)
    }

    @Test
    fun `matching base title clears custom value`() {
        val normalized = normalizeCustomMetadataValue(
            desiredValue = "当前标题",
            baseValue = "当前标题"
        )

        assertNull(normalized)
    }

    @Test
    fun `writing an unchanged base cover keeps its selected cover reference`() {
        val baseCover = "file:///cache/embedded-cover.jpg"
        val normalizedCustomCover = normalizeCustomMetadataValue(
            desiredValue = baseCover,
            baseValue = baseCover
        )

        assertNull(normalizedCustomCover)
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = false,
                nextCustomCover = baseCover,
                previousCustomCover = null
            )
        )
        assertEquals(
            baseCover,
            resolveLocalCoverWriteReference(
                restoreBaseCover = false,
                requestedCoverReference = baseCover,
                restoredBaseCoverReference = null
            )
        )
    }

    @Test
    fun `restoring or replacing a custom cover requests cover write-back`() {
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = true,
                nextCustomCover = null,
                previousCustomCover = "file:///cache/custom-cover.jpg"
            )
        )
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = false,
                nextCustomCover = "file:///cache/new-cover.jpg",
                previousCustomCover = "file:///cache/old-cover.jpg"
            )
        )
    }

    @Test
    fun `second metadata write skips an unchanged custom cover`() {
        val customCover = "file:///cache/custom-cover.jpg"

        assertFalse(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = false,
                nextCustomCover = customCover,
                previousCustomCover = customCover
            )
        )
        assertEquals(
            customCover,
            resolveLocalCoverWriteReference(
                restoreBaseCover = false,
                requestedCoverReference = customCover,
                restoredBaseCoverReference = null
            )
        )
    }

    @Test
    fun `returning a custom cover to the displayed base keeps a replacement reference`() {
        val baseCover = "file:///cache/original-cover.jpg"
        val customCover = "file:///cache/custom-cover.jpg"
        val nextCustomCover = normalizeCustomMetadataValue(
            desiredValue = baseCover,
            baseValue = baseCover
        )

        assertNull(nextCustomCover)
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = false,
                nextCustomCover = nextCustomCover,
                previousCustomCover = customCover
            )
        )
        assertEquals(
            baseCover,
            resolveLocalCoverWriteReference(
                restoreBaseCover = false,
                requestedCoverReference = baseCover,
                restoredBaseCoverReference = null
            )
        )
    }

    @Test
    fun `restoring a custom cover uses the preserved original cover`() {
        assertEquals(
            "file:///cache/original-cover.jpg",
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = "file:///cache/original-cover.jpg",
                baseCoverUrl = "file:///cache/current-base.jpg",
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg"
            )
        )
    }

    @Test
    fun `restoring a custom cover never promotes the custom image to base`() {
        assertNull(
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = "file:///cache/custom-cover.jpg",
                baseCoverUrl = "file:///cache/custom-cover.jpg",
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg"
            )
        )
    }

    @Test
    fun `restoring without a known original cover keeps the visible cover`() {
        assertEquals(
            "file:///cache/custom-cover.jpg",
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = null,
                baseCoverUrl = null,
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg"
            )
        )
    }

    @Test
    fun `restoring a remote base cover keeps it for display`() {
        assertEquals(
            "https://example.com/original-cover.jpg",
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = null,
                baseCoverUrl = "https://example.com/original-cover.jpg",
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg"
            )
        )
    }

    @Test
    fun `current loaded song is released and resumed for metadata writes`() {
        assertEquals(
            LocalMetadataWritePlaybackAction.RELEASE_AND_RESUME,
            resolveLocalMetadataWritePlaybackAction(
                isTargetCurrentSong = true,
                hasLoadedMedia = true,
                shouldResumePlayback = true
            )
        )
    }

    @Test
    fun `unchanged song metadata save can skip the expensive persistence fanout`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertTrue(shouldSkipSongMetadataMutation(song, song, writeLyrics = false))
        assertFalse(shouldSkipSongMetadataMutation(song, song, writeLyrics = true))
    }

    @Test
    fun `paused or unrelated metadata writes do not resume playback`() {
        assertEquals(
            LocalMetadataWritePlaybackAction.RELEASE_ONLY,
            resolveLocalMetadataWritePlaybackAction(
                isTargetCurrentSong = true,
                hasLoadedMedia = true,
                shouldResumePlayback = false
            )
        )
        assertEquals(
            LocalMetadataWritePlaybackAction.NONE,
            resolveLocalMetadataWritePlaybackAction(
                isTargetCurrentSong = false,
                hasLoadedMedia = true,
                shouldResumePlayback = true
            )
        )
    }
}
