package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.player.persistence.EditableMetadataWriteMode
import moe.ouom.neriplayer.core.player.persistence.resolveEditableMetadataWriteMode
import moe.ouom.neriplayer.core.player.persistence.shouldPersistLyricsSidecarsSynchronously
import moe.ouom.neriplayer.core.player.persistence.shouldSyncDownloadedMetadataAfterMetadataUpdate
import moe.ouom.neriplayer.core.player.persistence.shouldSyncDownloadedMetadataAfterLyricsUpdate
import moe.ouom.neriplayer.core.player.persistence.shouldSyncDownloadedMetadataAfterPlaybackHydration
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
    fun `local cover sidecar decision is independent from embedded metadata writeback`() {
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = false,
                nextCustomCover = "content://local/covers/new.jpg",
                previousCustomCover = null
            )
        )
        assertTrue(
            shouldWriteLocalCoverMetadata(
                restoreBaseCover = true,
                nextCustomCover = null,
                previousCustomCover = "content://local/covers/old.jpg"
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
                restoreBaseCover = true,
                nextCustomCover = null,
                previousCustomCover = null
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
    fun `local restore materializes an explicit remote cover before write-back`() {
        assertTrue(
            shouldMaterializeRemoteLocalCover(
                isLocalSong = true,
                requestedCoverReference = "https://example.com/original-cover.jpg",
                restoreBaseCover = true,
                persistManualRemoteCover = false
            )
        )
        assertFalse(
            shouldMaterializeRemoteLocalCover(
                isLocalSong = true,
                requestedCoverReference = "https://example.com/original-cover.jpg",
                restoreBaseCover = false,
                persistManualRemoteCover = false
            )
        )
        assertFalse(
            shouldMaterializeRemoteLocalCover(
                isLocalSong = false,
                requestedCoverReference = "https://example.com/original-cover.jpg",
                restoreBaseCover = true,
                persistManualRemoteCover = false
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
    fun `restoring a custom cover keeps it when no other base reference exists`() {
        assertEquals(
            "file:///cache/custom-cover.jpg",
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
    fun `restoring a local cover keeps the local reference over remote metadata`() {
        assertEquals(
            "file:///storage/emulated/0/neriplayer-download/Covers/Song.jpg",
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = "https://example.com/original-cover.jpg",
                baseCoverUrl = "https://example.com/current-cover.jpg",
                currentCustomCoverUrl = "https://example.com/custom-cover.jpg",
                preferredLocalCoverUrl =
                    "file:///storage/emulated/0/neriplayer-download/Covers/Song.jpg"
            )
        )
    }

    @Test
    fun `restoring a local cover prefers the explicit restored reference over an old sidecar`() {
        assertEquals(
            "file:///cache/restored-from-provider.jpg",
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = "file:///cache/original-cover.jpg",
                baseCoverUrl = "file:///cache/current-base.jpg",
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg",
                preferredLocalCoverUrl = "file:///cache/old-sidecar.jpg",
                requestedRestoreCoverUrl = "file:///cache/restored-from-provider.jpg",
                localOnly = true
            )
        )
    }

    @Test
    fun `restoring a local song without a local cover does not keep a remote url`() {
        assertNull(
            resolveRestoredBaseCoverUrl(
                originalCoverUrl = "https://example.com/original-cover.jpg",
                baseCoverUrl = "https://example.com/current-cover.jpg",
                currentCustomCoverUrl = "file:///cache/custom-cover.jpg",
                localOnly = true
            )
        )
    }

    @Test
    fun `current loaded song keeps playing during metadata writes`() {
        assertEquals(
            LocalMetadataWritePlaybackAction.NONE,
            resolveLocalMetadataWritePlaybackAction()
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
    fun `paused metadata writes also keep playback untouched`() {
        assertEquals(
            LocalMetadataWritePlaybackAction.NONE,
            resolveLocalMetadataWritePlaybackAction()
        )
        assertEquals(
            LocalMetadataWritePlaybackAction.NONE,
            resolveLocalMetadataWritePlaybackAction()
        )
    }

    @Test
    fun `local metadata writes use the background persistence path`() {
        assertEquals(
            EditableMetadataWriteMode.BACKGROUND_LOCAL_WRITE,
            resolveEditableMetadataWriteMode(writeLocalMetadata = true)
        )
        assertEquals(
            EditableMetadataWriteMode.APP_ONLY,
            resolveEditableMetadataWriteMode(writeLocalMetadata = false)
        )
    }

    @Test
    fun `local metadata write skips the synchronous lyric sidecar path`() {
        assertTrue(
            shouldPersistLyricsSidecarsSynchronously(
                writeLocalMetadata = false,
                persistLocalSidecars = true
            )
        )
        assertFalse(
            shouldPersistLyricsSidecarsSynchronously(
                writeLocalMetadata = true,
                persistLocalSidecars = true
            )
        )
        assertFalse(
            shouldPersistLyricsSidecarsSynchronously(
                writeLocalMetadata = false,
                persistLocalSidecars = false
            )
        )
    }

    @Test
    fun `local lyric update syncs downloaded catalog after embedded write`() {
        assertTrue(
            shouldSyncDownloadedMetadataAfterLyricsUpdate(
                writeLocalMetadata = false,
                isLocalSong = true,
                syncDownloadedMetadata = true
            )
        )
        assertTrue(
            shouldSyncDownloadedMetadataAfterLyricsUpdate(
                writeLocalMetadata = true,
                isLocalSong = true,
                syncDownloadedMetadata = true
            )
        )
        assertFalse(
            shouldSyncDownloadedMetadataAfterLyricsUpdate(
                writeLocalMetadata = false,
                isLocalSong = true,
                syncDownloadedMetadata = false
            )
        )
    }

    @Test
    fun `local embedded metadata write still syncs downloaded catalog`() {
        assertTrue(
            shouldSyncDownloadedMetadataAfterMetadataUpdate(
                writeLocalMetadata = true,
                isLocalSong = true
            )
        )
        assertTrue(
            shouldSyncDownloadedMetadataAfterMetadataUpdate(
                writeLocalMetadata = false,
                isLocalSong = true
            )
        )
        assertFalse(
            shouldSyncDownloadedMetadataAfterMetadataUpdate(
                writeLocalMetadata = true,
                isLocalSong = false
            )
        )
    }

    @Test
    fun `playback hydration never syncs downloaded metadata`() {
        assertFalse(shouldSyncDownloadedMetadataAfterPlaybackHydration())
    }
}
