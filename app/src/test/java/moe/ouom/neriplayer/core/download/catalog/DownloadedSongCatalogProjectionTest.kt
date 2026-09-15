package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongCatalogProjectionTest {

    @Test
    fun `restoring a remote original cover clears the stale downloaded sidecar`() {
        val originalCover = "https://example.com/original.jpg"

        val projected = projectDownloadedSongMetadata(
            existing = downloadedSong(
                coverPath = "content://downloads/Covers/custom.jpg",
                customCoverUrl = "file:///data/user/0/app/custom.jpg"
            ),
            updatedSong = localSong(
                coverUrl = originalCover,
                originalCoverUrl = originalCover
            )
        )

        assertNull(projected.coverPath)
        assertNull(projected.customCoverUrl)
        assertEquals(originalCover, projected.coverUrl)
    }

    @Test
    fun `restoring a local original cover keeps it as the downloaded list cover`() {
        val originalCover = "file:///data/user/0/app/original.jpg"

        val projected = projectDownloadedSongMetadata(
            existing = downloadedSong(
                coverPath = "content://downloads/Covers/custom.jpg",
                customCoverUrl = "file:///data/user/0/app/custom.jpg"
            ),
            updatedSong = localSong(
                coverUrl = originalCover,
                originalCoverUrl = originalCover
            )
        )

        assertEquals(originalCover, projected.coverPath)
        assertNull(projected.customCoverUrl)
    }

    @Test
    fun `local metadata update keeps downloaded remote source and originals`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "Remote title",
            artist = "Remote artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/original.jpg",
            channelId = "netease",
            audioId = "42"
        )
        val existing = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Downloads",
            filePath = "content://downloads/song.m4a",
            fileSize = 1L,
            downloadTime = 1L,
            coverUrl = remoteSong.coverUrl,
            originalName = remoteSong.name,
            originalArtist = remoteSong.artist,
            originalCoverUrl = remoteSong.coverUrl,
            mediaUri = "content://downloads/song.m4a",
            stableKey = remoteSong.stableKey(),
            sourceIdentityAlbum = remoteSong.identity().album,
            sourceChannelId = remoteSong.channelId,
            sourceAudioId = remoteSong.audioId
        )
        val localEdit = localSong(
            coverUrl = remoteSong.coverUrl.orEmpty(),
            originalCoverUrl = ""
        ).copy(
            name = "Edited title",
            artist = "Edited artist",
            album = "Remote Album",
            channelId = "local",
            audioId = "100"
        )

        val projected = projectDownloadedSongMetadata(existing, localEdit)
        val persistedSong = projected.toMetadataPersistenceSong(localEdit)

        assertEquals(remoteSong.id, projected.id)
        assertEquals("Remote Album", projected.album)
        assertEquals(remoteSong.stableKey(), projected.stableKey)
        assertEquals("netease", projected.sourceChannelId)
        assertEquals("42", projected.sourceAudioId)
        assertEquals("Remote title", projected.originalName)
        assertEquals("Remote artist", projected.originalArtist)
        assertEquals(remoteSong.coverUrl, projected.originalCoverUrl)
        assertEquals(remoteSong.id, persistedSong.id)
        assertEquals(remoteSong.stableKey(), persistedSong.sourceStableKey)
        assertEquals("netease", persistedSong.channelId)
        assertEquals("42", persistedSong.audioId)
    }

    @Test
    fun `legacy catalog round trip keeps translated romanized and cleared lyrics`() {
        val song = downloadedSong(coverPath = null, customCoverUrl = null).copy(
            matchedLyric = "",
            matchedTranslatedLyric = "[00:01.00]译",
            matchedRomanizedLyric = "[00:01.00]yi",
            originalLyric = "[00:01.00]原",
            originalTranslatedLyric = "",
            originalRomanizedLyric = "[00:01.00]yuan"
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = serializeDownloadedSongsCatalog("root", listOf(song)),
            expectedCacheKey = "root"
        )

        assertEquals(
            song.copy(
                originalLyric = null,
                originalTranslatedLyric = null,
                originalRomanizedLyric = null
            ),
            restored?.single()
        )
    }

    @Test
    fun `downloaded lyric fallback skips slow inspection when any lyric variant is indexed`() {
        assertFalse(
            shouldInspectDownloadedLocalLyrics(
                loadLyricContents = true,
                fileLyric = null,
                fileTranslatedLyric = null,
                fileRomanizedLyric = null,
                matchedLyric = null,
                originalLyric = null,
                matchedTranslatedLyric = null,
                originalTranslatedLyric = null,
                matchedRomanizedLyric = null,
                originalRomanizedLyric = null,
                indexedLyric = null,
                indexedTranslatedLyric = "[00:00.00]译",
                indexedRomanizedLyric = null
            )
        )
        assertFalse(
            shouldInspectDownloadedLocalLyrics(
                loadLyricContents = true,
                fileLyric = null,
                fileTranslatedLyric = null,
                fileRomanizedLyric = "",
                matchedLyric = null,
                originalLyric = null,
                matchedTranslatedLyric = null,
                originalTranslatedLyric = null,
                matchedRomanizedLyric = null,
                originalRomanizedLyric = null,
                indexedLyric = null,
                indexedTranslatedLyric = null,
                indexedRomanizedLyric = null
            )
        )
    }

    @Test
    fun `downloaded lyric fallback stays enabled only when every source is absent`() {
        assertTrue(
            shouldInspectDownloadedLocalLyrics(
                loadLyricContents = true,
                fileLyric = null,
                fileTranslatedLyric = null,
                fileRomanizedLyric = null,
                matchedLyric = null,
                originalLyric = null,
                matchedTranslatedLyric = null,
                originalTranslatedLyric = null,
                matchedRomanizedLyric = null,
                originalRomanizedLyric = null,
                indexedLyric = null,
                indexedTranslatedLyric = null,
                indexedRomanizedLyric = null
            )
        )
    }

    @Test
    fun `playback reference probe skips stale private path and uses accessible media uri`() {
        val stalePrivatePath = "/data/user/0/moe.ouom.neriplayer/files/old/Song.mp3"
        val currentSafReference = "content://downloads-current/document/Song.mp3"
        val downloaded = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = stalePrivatePath,
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = currentSafReference
        )

        assertEquals(
            listOf(stalePrivatePath, currentSafReference),
            downloadedSongPlaybackReferenceCandidates(downloaded)
        )
        assertEquals(
            currentSafReference,
            resolveDownloadedSongPlaybackReference(downloaded) { reference ->
                reference == currentSafReference
            }
        )
    }

    @Test
    fun `playback reference candidates do not treat a remote media uri as local`() {
        val downloaded = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/data/user/0/moe.ouom.neriplayer/files/Song.mp3",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "https://cdn.example/Song.mp3"
        )

        assertEquals(
            listOf(downloaded.filePath),
            downloadedSongPlaybackReferenceCandidates(downloaded)
        )
    }

    @Test
    fun `playback reference candidates reject a remote legacy file path`() {
        val downloaded = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "https://cdn.example/Song.mp3",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = null
        )

        assertTrue(downloadedSongPlaybackReferenceCandidates(downloaded).isEmpty())
    }

    private fun downloadedSong(
        coverPath: String?,
        customCoverUrl: String?
    ): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "content://downloads/song.m4a",
            fileSize = 1L,
            downloadTime = 1L,
            coverPath = coverPath,
            customCoverUrl = customCoverUrl,
            mediaUri = "content://downloads/song.m4a",
            stableKey = "1|local|"
        )
    }

    private fun localSong(
        coverUrl: String,
        originalCoverUrl: String
    ): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = coverUrl,
            mediaUri = "content://downloads/song.m4a",
            originalCoverUrl = originalCoverUrl
        )
    }
}
