package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverUrlStateTest {

    @Test
    fun `slow local cover fallback remains available during playback`() {
        assertTrue(
            shouldResolveSlowLocalCoverFallback(
                resolveLocalFallback = true
            )
        )
    }

    @Test
    fun `disabled local fallback remains disabled while idle`() {
        assertFalse(
            shouldResolveSlowLocalCoverFallback(
                resolveLocalFallback = false
            )
        )
    }

    @Test
    fun `cached local cover lookup does not reopen audio for list rows`() {
        assertFalse(
            shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = true,
                allowEmbeddedCoverFallback = false
            )
        )
    }

    @Test
    fun `fast cover reference accepts provider uri without opening descriptor`() {
        assertTrue(isFastCoverReference("content://provider/document/cover"))
    }

    @Test
    fun `fast cover reference rejects missing file path`() {
        assertFalse(isFastCoverReference("/path/that/does/not/exist.jpg"))
    }

    @Test
    fun `local song probes sidecar even when a remote cover is already present`() {
        assertTrue(
            shouldProbeFastLocalCoverCandidate(
                isLocalSong = true,
                immediateCover = "https://example.com/remote-cover.jpg"
            )
        )
    }

    @Test
    fun `remote song keeps its immediate cover without a local probe`() {
        assertFalse(
            shouldProbeFastLocalCoverCandidate(
                isLocalSong = false,
                immediateCover = "https://example.com/remote-cover.jpg"
            )
        )
    }

    @Test
    fun `remote cover fallback remains enabled for remote songs by default`() {
        assertTrue(shouldAllowRemoteCoverFallback(isLocalSong = false))
    }

    @Test
    fun `local songs disable remote fallback unless they have an explicit custom cover`() {
        assertFalse(shouldAllowRemoteCoverFallback(isLocalSong = true))
        assertTrue(
            shouldAllowRemoteCoverFallback(
                isLocalSong = true,
                hasExplicitCustomCover = true
            )
        )
    }

    @Test
    fun `local stale http cover is rejected as an immediate candidate`() {
        assertNull(
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "https://p4.music.126.net/stale.jpg",
                fallbackCoverUrl = "https://example.com/original.jpg",
                allowRemoteCoverFallback = false
            )
        )
    }

    @Test
    fun `local content cover remains an immediate candidate when remote fallback is disabled`() {
        assertEquals(
            "content://new-tree/Covers/song.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "content://new-tree/Covers/song.jpg",
                fallbackCoverUrl = "https://example.com/original.jpg",
                allowRemoteCoverFallback = false
            )
        )
    }

    @Test
    fun `explicit custom remote cover remains an immediate candidate`() {
        assertEquals(
            "https://example.com/custom.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "https://example.com/custom.jpg",
                fallbackCoverUrl = null,
                allowRemoteCoverFallback = true
            )
        )
    }

    @Test
    fun `local stale http cover is not prevalidated as a remote candidate`() {
        assertNull(
            resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = "https://p4.music.126.net/stale.jpg",
                fallbackCoverUrl = "https://example.com/original.jpg",
                allowRemoteCoverFallback = false
            )
        )
    }

    @Test
    fun `unverified SAF cover defers its remote fallback until validation finishes`() {
        assertNull(
            resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = "content://old-root/Covers/song.jpg",
                fallbackCoverUrl = "https://example.com/original.jpg"
            )
        )
    }

    @Test
    fun `remote fallback is immediate only when no primary cover is pending`() {
        assertEquals(
            "https://example.com/original.jpg",
            resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = null,
                fallbackCoverUrl = "https://example.com/original.jpg"
            )
        )
    }

    @Test
    fun `unverified local references are never published before validation`() {
        assertNull(
            resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = "content://old-root/Covers/song.jpg",
                fallbackCoverUrl = "file:///old-root/Covers/song.jpg"
            )
        )
    }

    @Test
    fun `immediate candidate keeps a SAF reference before provider validation`() {
        assertEquals(
            "content://old-root/Covers/song.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = " content://old-root/Covers/song.jpg ",
                fallbackCoverUrl = "https://example.com/original.jpg"
            )
        )
    }

    @Test
    fun `immediate candidate keeps a local file reference before existence validation`() {
        assertEquals(
            "file:///old-root/Covers/song.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "file:///old-root/Covers/song.jpg",
                fallbackCoverUrl = "https://example.com/original.jpg"
            )
        )
    }

    @Test
    fun `stale local candidate does not fall back to a remote preview`() {
        assertEquals(
            "content://old-root/Covers/missing.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "content://old-root/Covers/missing.jpg",
                fallbackCoverUrl = "https://example.com/preview.jpg"
            )
        )
    }

    @Test
    fun `failed cover resolution keeps an existing frame`() {
        assertEquals(
            "content://old-root/Covers/song.jpg",
            finishCoverResolution(
                currentCover = "content://old-root/Covers/song.jpg",
                resolvedCover = null,
                resolutionComplete = true
            )
        )
    }

    @Test
    fun `failed validation clears an invalid local reference`() {
        assertNull(
            finishCoverResolution(
                currentCover = "content://old-root/Covers/missing.jpg",
                resolvedCover = null,
                resolutionComplete = true,
                currentCoverUsable = false
            )
        )
    }

    @Test
    fun `embedded local cover fallback remains available outside scroll constrained rows`() {
        assertTrue(
            shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = true,
                allowEmbeddedCoverFallback = true
            )
        )
    }

    @Test
    fun `playlist fallback stops after an immediate cover is available`() {
        assertFalse(
            shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = true,
                hasImmediateCover = true
            )
        )
    }

    @Test
    fun `playlist fallback remains available during playback`() {
        assertTrue(
            shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = true,
                hasImmediateCover = false
            )
        )
    }

    @Test
    fun `playlist cover signature changes when a cover source changes`() {
        val original = song(coverUrl = null)
        val updated = original.copy(coverUrl = "file:///music/cover.jpg")

        assertNotEquals(
            playlistCoverResolutionSignature(listOf(original)),
            playlistCoverResolutionSignature(listOf(updated))
        )
    }

    @Test
    fun `playlist cover signature uses a bounded candidate prefix`() {
        val original = List(33) { index -> song(coverUrl = null).copy(id = index.toLong()) }
        val updated = original.toMutableList().apply {
            this[lastIndex] = last().copy(coverUrl = "file:///music/later-cover.jpg")
        }

        assertEquals(
            playlistCoverResolutionSignature(original),
            playlistCoverResolutionSignature(updated)
        )
    }

    @Test
    fun `song cover cache key survives local metadata hydration`() {
        val quickSong = SongItem(
            id = 7L,
            name = "track.mp3",
            artist = "Unknown",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/7",
            channelId = "local",
            audioId = "7"
        )
        val hydratedSong = quickSong.copy(
            name = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            localFilePath = "/music/Track.mp3"
        )

        assertEquals(
            quickSong.coverDisplayCacheKey(),
            hydratedSong.coverDisplayCacheKey()
        )
    }

    @Test
    fun `song cover cache key changes when the explicit cover changes`() {
        val original = song(coverUrl = "https://example.com/old.jpg")
        val updated = original.copy(coverUrl = "https://example.com/new.jpg")

        assertNotEquals(original.coverDisplayCacheKey(), updated.coverDisplayCacheKey())
    }

    @Test
    fun `local playback visual key survives private and SAF reference replacement`() {
        val privateSong = SongItem(
            id = 11L,
            name = "Track",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = "file:///data/user/0/moe.ouom.neriplayer/files/covers/track.jpg",
            mediaUri = "/data/user/0/moe.ouom.neriplayer/files/NeriPlayer-Download/Track.mp3",
            localFileName = "Track.mp3",
            localFilePath = "/data/user/0/moe.ouom.neriplayer/files/NeriPlayer-Download/Track.mp3",
            channelId = "local",
            audioId = "11"
        )
        val safSong = privateSong.copy(
            mediaUri = "content://com.android.externalstorage.documents/document/primary%3ANeriPlayer-Download%2FTrack.mp3",
            localFilePath = null
        )

        assertEquals(privateSong.playbackVisualKey(), safSong.playbackVisualKey())
        assertEquals(privateSong.coverDisplayCacheKey(), safSong.coverDisplayCacheKey())
    }

    @Test
    fun `cover display state key survives a rebuilt cover reference`() {
        val oldReference = song(
            coverUrl = "content://old-tree/Covers/track.jpg"
        ).copy(
            album = "__local_files__",
            originalCoverUrl = "content://old-tree/Covers/track.jpg",
            mediaUri = "file:///data/user/0/moe.ouom.neriplayer/files/Track.mp3",
            localFilePath = "/data/user/0/moe.ouom.neriplayer/files/Track.mp3",
            localFileName = "Track.mp3",
            channelId = "local",
            audioId = "1"
        )
        val rebuiltReference = oldReference.copy(
            coverUrl = "content://new-tree/Covers/track.jpg",
            originalCoverUrl = "content://new-tree/Covers/track.jpg",
            mediaUri = "content://com.android.externalstorage.documents/document/" +
                "primary%3ANeriPlayer%2FTrack.mp3",
            localFilePath = null
        )

        assertEquals(
            oldReference.coverDisplayStateKey(),
            rebuiltReference.coverDisplayStateKey()
        )
        assertNotEquals(
            oldReference.coverDisplayCacheKey(),
            rebuiltReference.coverDisplayCacheKey()
        )
    }

    @Test
    fun `local playback visual key keeps same filename tracks separated by metadata`() {
        val first = song(coverUrl = null).copy(
            album = "__local_files__",
            localFileName = "Track.mp3",
            mediaUri = "content://tree-a/Track.mp3",
            artist = "Artist A"
        )
        val second = first.copy(
            mediaUri = "content://tree-b/Track.mp3",
            artist = "Artist B"
        )

        assertNotEquals(first.playbackVisualKey(), second.playbackVisualKey())
    }

    @Test
    fun `local playback visual key uses the persisted audio id to separate identical metadata`() {
        val first = song(coverUrl = null).copy(
            album = "__local_files__",
            mediaUri = "content://tree-a/Track.mp3",
            localFileName = "Track.mp3",
            channelId = "local",
            audioId = "audio-a"
        )
        val second = first.copy(
            mediaUri = "content://tree-b/Track.mp3",
            audioId = "audio-b"
        )

        assertNotEquals(first.playbackVisualKey(), second.playbackVisualKey())
    }

    @Test
    fun `local visual aliases do not collapse same metadata from different audio ids`() {
        val first = song(coverUrl = null).copy(
            album = "__local_files__",
            localFileName = "Track.mp3",
            mediaUri = "content://tree-a/Track.mp3",
            channelId = "local",
            audioId = "audio-a"
        )
        val second = first.copy(
            mediaUri = "content://tree-b/Track.mp3",
            audioId = "audio-b"
        )

        assertTrue(first.playbackVisualKeyAliases().intersect(
            second.playbackVisualKeyAliases()
        ).isEmpty())
    }

    @Test
    fun `local visual aliases retain both persisted source and audio identities`() {
        val song = song(coverUrl = null).copy(
            album = "__local_files__",
            mediaUri = "content://tree/Track.mp3",
            localFileName = "Track.mp3",
            channelId = "local",
            audioId = "audio-a",
            sourceStableKey = "42|netease|"
        )

        assertTrue(
            song.playbackVisualKeyAliases().containsAll(
                listOf("local-source:42|netease|", "local-audio:audio-a")
            )
        )
    }

    @Test
    fun `playlist cover cache key changes when additional candidates change`() {
        val playlist = LocalPlaylist(
            id = 7L,
            name = "playlist",
            songs = mutableListOf()
        )
        val withoutCover = song(coverUrl = null)
        val withCover = withoutCover.copy(coverUrl = "file:///music/cover.jpg")

        assertNotEquals(
            playlistCoverResolutionCacheKey(playlist, listOf(withoutCover)),
            playlistCoverResolutionCacheKey(playlist, listOf(withCover))
        )
    }

    @Test
    fun `versioned cover cache key separates download generations`() {
        val baseKey = "song:local-track"

        assertEquals(
            "$baseKey|generation=3",
            versionedCoverCacheKey(baseKey, generation = 3)
        )
        assertNotEquals(
            versionedCoverCacheKey(baseKey, generation = 3),
            versionedCoverCacheKey(baseKey, generation = 4)
        )
    }

    @Test
    fun `versioned cover cache key keeps absent keys absent`() {
        assertNull(versionedCoverCacheKey(null, generation = 1))
        assertNull(versionedCoverCacheKey("  ", generation = 1))
    }

    private fun song(coverUrl: String?): SongItem {
        return SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl
        )
    }
}
