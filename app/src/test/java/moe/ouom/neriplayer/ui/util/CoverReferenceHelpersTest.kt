package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverReferenceHelpersTest {

    private val resolvedKeys = mutableListOf<String>()

    @After
    fun forgetResolvedCovers() {
        resolvedKeys.forEach(::forgetResolvedCover)
    }

    @Test
    fun `preferred playlist cover is trimmed and falls back when missing or rejected`() {
        assertEquals("preferred", choosePreferredPlaylistCover(" preferred ", " fallback ", preferredCoverUsable = true))
        assertEquals("fallback", choosePreferredPlaylistCover(" ", " fallback ", preferredCoverUsable = true))
        assertEquals("fallback", choosePreferredPlaylistCover("preferred", " fallback ", preferredCoverUsable = false))
        assertEquals("fallback", choosePreferredPlaylistCover(null, "fallback", preferredCoverUsable = null))
        assertNull(choosePreferredPlaylistCover(" ", null, preferredCoverUsable = false))
    }

    @Test
    fun `allowed cover candidates reject blanks shared album art and disabled remote covers`() {
        assertFalse(isAllowedCoverCandidate(null))
        assertFalse(isAllowedCoverCandidate("  "))
        assertFalse(isAllowedCoverCandidate("content://media/external/audio/albumart/42"))
        assertTrue(isAllowedCoverCandidate("https://example.com/cover.jpg"))
        assertFalse(isAllowedCoverCandidate("https://example.com/cover.jpg", allowRemoteCoverFallback = false))
        assertTrue(isAllowedCoverCandidate(" file:///music/cover.jpg ", allowRemoteCoverFallback = false))
    }

    @Test
    fun `local songs only allow remote covers that were set explicitly`() {
        val remote = song()
        val local = song().copy(localFilePath = "/music/track.mp3")

        assertTrue(remote.allowsRemoteCoverFallback())
        assertFalse(local.allowsRemoteCoverFallback())
        assertFalse(local.copy(customCoverUrl = "  ").allowsRemoteCoverFallback())
        assertFalse(local.copy(customCoverUrl = "/music/custom.jpg").allowsRemoteCoverFallback())
        assertTrue(local.copy(customCoverUrl = " https://example.com/custom.jpg ").allowsRemoteCoverFallback())
    }

    @Test
    fun `prevalidated candidates prefer the primary reference and only publish remote urls`() {
        assertEquals(
            "https://example.com/fallback.jpg",
            resolvePrevalidatedCoverCandidate(primaryCoverUrl = " ", fallbackCoverUrl = " https://example.com/fallback.jpg ")
        )
        assertEquals(
            "HTTP://example.com/primary.jpg",
            resolvePrevalidatedCoverCandidate(primaryCoverUrl = "HTTP://example.com/primary.jpg", fallbackCoverUrl = null)
        )
        assertNull(resolvePrevalidatedCoverCandidate(primaryCoverUrl = null, fallbackCoverUrl = null))
        assertNull(
            resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = null,
                fallbackCoverUrl = "https://example.com/fallback.jpg",
                allowRemoteCoverFallback = false
            )
        )
    }

    @Test
    fun `immediate candidates skip blank and non uri primaries`() {
        assertNull(resolveImmediateCoverCandidate(primaryCoverUrl = null, fallbackCoverUrl = null))
        assertEquals(
            "/music/cover.jpg",
            resolveImmediateCoverCandidate(primaryCoverUrl = "   ", fallbackCoverUrl = " /music/cover.jpg ")
        )
        assertEquals(
            "https://example.com/fallback.jpg",
            resolveImmediateCoverCandidate(primaryCoverUrl = "cover.jpg", fallbackCoverUrl = "https://example.com/fallback.jpg")
        )
        assertNull(resolveImmediateCoverCandidate(primaryCoverUrl = "cover.jpg", fallbackCoverUrl = "  "))
    }

    @Test
    fun `cover source kind distinguishes provider files and metadata only songs`() {
        assertEquals("content", coverSourceKind(song().copy(mediaUri = "CONTENT://provider/audio/1")))
        assertEquals("file", coverSourceKind(song().copy(mediaUri = "https://example.com/a.mp3", localFilePath = "/music/a.mp3")))
        assertEquals("metadata", coverSourceKind(song().copy(localFilePath = " ")))
        assertEquals("metadata", coverSourceKind(song()))
    }

    @Test
    fun `cover resolution log message reports stage timing and resolved state`() {
        val message = coverResolutionLogMessage(
            song = song().copy(localFilePath = "/music/a.mp3"),
            stage = "fallback",
            elapsedMs = 250L,
            immediateCover = " ",
            resolvedCover = "file:///music/cover.jpg"
        )

        assertTrue(message.startsWith("songKeyHash="))
        assertTrue(
            message.endsWith(
                "stage=fallback, elapsed=250ms, hasImmediate=false, hasResolved=true, sourceKind=file"
            )
        )
        assertTrue(
            coverResolutionLogMessage(song(), "song", 1L, "https://example.com/a.jpg", null)
                .endsWith("hasImmediate=true, hasResolved=false, sourceKind=metadata")
        )
    }

    @Test
    fun `playlist cover signature keeps the original rolling hash`() {
        val first = song().copy(
            customCoverUrl = "custom",
            originalCoverUrl = "original",
            localFilePath = "/music/a.mp3",
            mediaUri = "content://a",
            channelId = "local",
            audioId = "1",
            subAudioId = "2",
            sourceStableKey = "source"
        )
        val second = song().copy(id = 2L, album = "other", albumId = 9L)

        assertEquals(1_125_899_906_842_597L, playlistCoverResolutionSignature(emptyList()))
        assertEquals(legacySignature(listOf(first, second)), playlistCoverResolutionSignature(listOf(first, second)))
    }

    @Test
    fun `fast cover probe key tracks file state generation and remote policy`() {
        val file = File.createTempFile("cover-probe", ".mp3").apply {
            writeText("audio")
            deleteOnExit()
        }
        val local = song().copy(localFilePath = file.absolutePath, localFileName = file.name)
        val key = fastCoverProbeCacheKey(local, probeGeneration = 1, allowRemoteCoverFallback = false)

        assertTrue(key.contains("|${file.length()}:${file.lastModified()}:"))
        assertTrue(key.endsWith("|generation=1|allowRemote=false"))
        assertNotEquals(key, fastCoverProbeCacheKey(local, probeGeneration = 2, allowRemoteCoverFallback = false))
        assertNotEquals(key, fastCoverProbeCacheKey(local, probeGeneration = 1, allowRemoteCoverFallback = true))

        val covered = local.copy(
            customCoverUrl = "custom",
            coverUrl = "cover",
            originalCoverUrl = "original"
        )
        assertTrue(
            fastCoverProbeCacheKey(covered, probeGeneration = 1, allowRemoteCoverFallback = false)
                .contains("|custom|cover|original|${file.name}|")
        )

        val provider = song().copy(localFilePath = "content://provider/audio/1")
        assertTrue(
            fastCoverProbeCacheKey(provider, probeGeneration = 0, allowRemoteCoverFallback = true)
                .contains("||reference=")
        )
        file.delete()
    }

    @Test
    fun `resolved cover cache ignores blank entries and forgets on request`() {
        val key = "cover-helpers-test:resolved".also(resolvedKeys::add)

        rememberResolvedCover(key, " ")
        assertNull(cachedResolvedCover(key))
        rememberResolvedCover(null, "https://example.com/a.jpg")
        rememberResolvedCover(key, "https://example.com/a.jpg")
        assertEquals("https://example.com/a.jpg", cachedResolvedCover(key))
        assertNull(cachedResolvedCover(" "))

        forgetResolvedCover(" ")
        forgetResolvedCover(key)
        assertNull(cachedResolvedCover(key))
    }

    @Test
    fun `stable cover cache matches validation keys and remote policy per alias`() {
        val alias = "cover-helpers-test:stable-alias"
        val otherAlias = "cover-helpers-test:stable-other"

        rememberStableResolvedCover(emptyList(), "https://example.com/a.jpg", "v1")
        rememberStableResolvedCover(listOf(alias), " ", "v1")
        assertNull(cachedStableResolvedCover(listOf(alias), "v1"))

        rememberStableResolvedCover(listOf(" ", alias), "https://example.com/a.jpg", "v1")
        assertNull(cachedStableResolvedCover(emptyList(), "v1"))
        assertNull(cachedStableResolvedCover(listOf(" "), "v1"))
        assertNull(cachedStableResolvedCover(listOf(alias), "v2"))
        assertNull(cachedStableResolvedCover(listOf(alias), "v1", allowRemoteCoverFallback = false))
        assertEquals("https://example.com/a.jpg", cachedStableResolvedCover(listOf(otherAlias, alias), "v1"))
    }

    private fun legacySignature(songs: List<SongItem>): Long {
        var signature = 1_125_899_906_842_597L
        songs.forEach { song ->
            signature = 31L * signature + song.id
            signature = 31L * signature + song.album.hashCode()
            signature = 31L * signature + song.albumId
            listOf(
                song.customCoverUrl,
                song.coverUrl,
                song.originalCoverUrl,
                song.localFilePath,
                song.mediaUri,
                song.channelId,
                song.audioId,
                song.subAudioId,
                song.sourceStableKey
            ).forEach { value -> signature = 31L * signature + value.orEmpty().hashCode() }
        }
        return signature
    }

    private fun song(): SongItem = SongItem(
        id = 1L,
        name = "song",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 1_000L,
        coverUrl = null
    )
}
