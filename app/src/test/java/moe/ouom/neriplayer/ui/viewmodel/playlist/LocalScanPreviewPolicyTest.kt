package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanPreviewPolicyTest {

    private val unknownArtist = "Unknown artist"
    private val localFilesAlbums = setOf("Local Files")
    private val isLocalFilesAlbum: (String) -> Boolean = { it in localFilesAlbums }

    @Test
    fun `a title that differs from the file name counts as metadata`() {
        assertTrue(meaningful(song(name = "Real title", fileName = "track01.mp3")))
        assertTrue(meaningful(song(name = "Title without a file")))
    }

    @Test
    fun `file name titles with placeholder artist album and no cover carry no metadata`() {
        val bare = song(name = "track01", fileName = "TRACK01.flac", artist = " unknown ARTIST ")

        assertFalse(meaningful(bare))
        assertFalse(meaningful(bare.copy(name = " ", localFileName = null, artist = "")))
        assertFalse(meaningful(bare.copy(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY)))
        assertFalse(meaningful(bare.copy(album = "Local Files")))
    }

    @Test
    fun `artist album or artwork alone make scan metadata meaningful`() {
        val bare = song(name = "track01", fileName = "track01.mp3", artist = unknownArtist)

        assertTrue(meaningful(bare.copy(artist = "Singer")))
        assertTrue(meaningful(bare.copy(album = " Album ")))
        assertTrue(meaningful(bare.copy(coverUrl = "content://cover")))
        assertTrue(meaningful(bare.copy(originalCoverUrl = "content://original")))
        assertFalse(meaningful(bare.copy(coverUrl = " ", originalCoverUrl = "")))
    }

    @Test
    fun `only placeholder artists request metadata hydration`() {
        val named = song(name = "track01", fileName = "track01.mp3", album = LocalSongSupport.LOCAL_ALBUM_IDENTITY)

        assertFalse(shouldHydrateLocalScanMetadata(named.copy(artist = "Singer"), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "  "), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "UNKNOWN ARTIST"), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "<Unknown>"), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "<unknown artist>"), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "未知艺术家"), unknownArtist))
        assertTrue(shouldHydrateLocalScanMetadata(named.copy(artist = "Unknown artist"), "Artiste inconnu"))
    }

    @Test
    fun `hidden preview keys combine every active filter`() {
        val pending = song(id = 1L, name = "a", fileName = "a.mp3", artist = unknownArtist)
        val bare = song(id = 2L, name = "b", fileName = "b.mp3", artist = unknownArtist)
        val tagged = song(id = 3L, name = "Tagged", artist = "Singer")
        val songs = listOf(pending, bare, tagged)

        val hidden = scanPreviewHiddenKeys(
            songs = songs,
            pendingKeys = setOf(pending.stableKey()),
            options = LocalScanPreviewState(
                metadataOnly = true,
                hideExistingLocalPlaylistSongs = true,
                hideDuplicateMetadataSongs = true
            ),
            existingLocalPlaylistKeys = setOf("existing"),
            duplicateMetadataKeys = setOf("duplicate"),
            hasMeaningfulMetadata = ::meaningful
        )
        val noFilters = scanPreviewHiddenKeys(
            songs = songs,
            pendingKeys = emptySet(),
            options = LocalScanPreviewState(metadataOnly = true),
            existingLocalPlaylistKeys = setOf("existing"),
            duplicateMetadataKeys = setOf("duplicate"),
            hasMeaningfulMetadata = null
        )

        assertEquals(setOf(bare.stableKey(), "existing", "duplicate"), hidden)
        assertTrue(noFilters.isEmpty())
    }

    @Test
    fun `completed scan preview selects every visible song in source order`() {
        val older = song(id = 1L, name = "Older", artist = "Singer")
        val newer = song(id = 2L, name = "Newer", artist = unknownArtist, fileName = "Newer.mp3")
        val progress = LocalAudioScanProgress(phase = LocalAudioScanPhase.COMPLETED, processed = 2, total = 2)

        val state = buildLocalScanPreviewState(
            songs = listOf(older, newer),
            localPlaylists = listOf(LocalPlaylist(id = 9L, name = "Mine", songs = mutableListOf(older))),
            options = LocalScanPreviewState(query = "ne", metadataOnly = true),
            metadataPendingKeys = setOf(newer.stableKey(), "gone"),
            selectedKeys = null,
            progress = progress,
            hasMeaningfulMetadata = ::meaningful
        )

        assertTrue(state.visible)
        assertFalse(state.isScanning)
        assertEquals("ne", state.query)
        assertEquals(setOf(older.stableKey(), newer.stableKey()), state.songs.map { it.stableKey() }.toSet())
        assertEquals(setOf(newer.stableKey()), state.metadataPendingKeys)
        assertEquals(setOf(older.stableKey()), state.existingLocalPlaylistKeys)
        assertEquals(setOf(older.stableKey(), newer.stableKey()), state.selectedKeys)
    }

    @Test
    fun `in-progress scan preview keeps the caller selection minus hidden songs`() {
        val kept = song(id = 1L, name = "Kept", artist = "Singer")
        val existing = song(id = 2L, name = "Existing", artist = "Singer")

        val state = buildLocalScanPreviewState(
            songs = listOf(kept, existing),
            localPlaylists = listOf(LocalPlaylist(id = 9L, name = "Mine", songs = mutableListOf(existing))),
            options = LocalScanPreviewState(hideExistingLocalPlaylistSongs = true),
            metadataPendingKeys = emptySet(),
            selectedKeys = setOf(kept.stableKey(), existing.stableKey(), "stale"),
            progress = LocalAudioScanProgress(phase = LocalAudioScanPhase.TRAVERSING),
            hasMeaningfulMetadata = ::meaningful
        )

        assertTrue(state.isScanning)
        assertTrue(state.hideExistingLocalPlaylistSongs)
        assertEquals(setOf(kept.stableKey()), state.selectedKeys)
    }

    @Test
    fun `hydration keeps newer preview progress and skips unknown targets`() {
        val first = song(id = 1L, name = "First", artist = "Singer")
        val second = song(id = 2L, name = "Second", artist = "Singer")
        val state = LocalScanPreviewState(
            songs = listOf(first, second),
            scanProgress = LocalAudioScanProgress(processed = 5),
            selectedKeys = setOf(first.stableKey(), second.stableKey())
        )
        val hydratedSecond = second.copy(album = "Album")

        val updated = applyHydratedSongsToScanPreview(
            state = state,
            hydratedSongs = listOf(hydratedSecond, first.copy(album = "Ignored")),
            progress = LocalAudioScanProgress(processed = 3),
            startIndex = 5,
            targetKeys = listOf(second.stableKey())
        )

        assertEquals(5, updated.scanProgress.processed)
        assertEquals(listOf(first, hydratedSecond), updated.songs)
        assertEquals(state.selectedKeys, updated.selectedKeys)
    }

    @Test
    fun `hydration with nothing to apply only advances progress`() {
        val state = LocalScanPreviewState(
            songs = listOf(song(id = 1L, name = "First")),
            scanProgress = LocalAudioScanProgress(processed = 1)
        )

        val unchanged = applyHydratedSongsToScanPreview(
            state = state,
            hydratedSongs = emptyList(),
            progress = LocalAudioScanProgress(processed = 4)
        )
        val emptyPreview = applyHydratedSongsToScanPreview(
            state = LocalScanPreviewState(),
            hydratedSongs = listOf(song(id = 2L, name = "Late")),
            progress = LocalAudioScanProgress(processed = 2)
        )

        assertEquals(state.songs, unchanged.songs)
        assertEquals(4, unchanged.scanProgress.processed)
        assertTrue(emptyPreview.songs.isEmpty())
        assertEquals(2, emptyPreview.scanProgress.processed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hydration rejects a negative batch offset`() {
        applyHydratedSongsToScanPreview(
            state = LocalScanPreviewState(songs = listOf(song(id = 1L, name = "First"))),
            hydratedSongs = listOf(null),
            progress = LocalAudioScanProgress(),
            startIndex = -1
        )
    }

    @Test
    fun `duration refresh needs a local file or local media reference`() {
        val missing = song(id = 1L, name = "Song").copy(durationMs = 0L, mediaUri = null)

        assertTrue(shouldScheduleLocalDurationRefresh(missing.copy(mediaUri = "CONTENT://media/1")))
        assertTrue(shouldScheduleLocalDurationRefresh(missing.copy(mediaUri = "/sdcard/Music/a.mp3")))
        assertTrue(shouldScheduleLocalDurationRefresh(missing.copy(localFilePath = "/sdcard/a.mp3")))
        assertFalse(shouldScheduleLocalDurationRefresh(missing.copy(localFilePath = " ")))
        assertFalse(shouldScheduleLocalDurationRefresh(missing.copy(mediaUri = "https://example.com/a.mp3")))
        assertFalse(
            shouldScheduleLocalDurationRefresh(
                missing.copy(durationMs = 1_000L, localFilePath = "/sdcard/a.mp3")
            )
        )
    }

    @Test
    fun `metadata duplicates ignore songs with incomplete fingerprints`() {
        val complete = song(id = 1L, name = "Song", artist = "Singer", album = "Album")
        val songs = listOf(
            complete,
            complete.copy(id = 2L, audioId = "2", mediaUri = "content://media/2", name = " "),
            complete.copy(id = 3L, audioId = "3", mediaUri = "content://media/3", artist = ""),
            complete.copy(id = 4L, audioId = "4", mediaUri = "content://media/4", album = " "),
            complete.copy(
                id = 5L,
                audioId = "5",
                mediaUri = "content://media/5",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY
            ),
            complete.copy(id = 6L, audioId = "6", mediaUri = "content://media/6", durationMs = 0L)
        )
        val duplicate = complete.copy(id = 7L, audioId = "7", mediaUri = "content://media/7", name = " SONG ")

        assertTrue(duplicateScannedSongKeysByMetadata(songs).isEmpty())
        assertEquals(setOf(duplicate.stableKey()), duplicateScannedSongKeysByMetadata(songs + duplicate))
    }

    private fun meaningful(song: SongItem): Boolean =
        hasMeaningfulLocalScanMetadata(song, unknownArtist, isLocalFilesAlbum)

    private fun song(
        id: Long = 1L,
        name: String,
        artist: String = "Artist",
        album: String = "",
        fileName: String? = null
    ) = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "content://media/external/audio/media/$id",
        channelId = "local",
        audioId = id.toString(),
        localFileName = fileName
    )
}
